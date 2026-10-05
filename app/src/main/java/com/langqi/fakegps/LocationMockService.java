package com.langqi.fakegps;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Owns the run independently of any Activity, including its single scheduled callback. */
public class LocationMockService extends Service {
    private static final String TAG = "LocationMockService";
    private static final String CHANNEL = "mock_gps_channel";
    private static final String START = "com.langqi.fakegps.START";
    private static final String PAUSE = "com.langqi.fakegps.PAUSE";
    private static final String RESUME = "com.langqi.fakegps.RESUME";
    private static final String STOP = "com.langqi.fakegps.STOP";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<RunController.Listener> listeners = new ArrayList<>();
    private final LocalBinder binder = new LocalBinder();
    private RunController controller;
    private RunResultStore results;
    private LocationManager locationManager;
    private AppOpsManager appOps;
    private final AppOpsManager.OnOpChangedListener authorizationListener = (op, packageName) -> {
        if (packageName == null || packageName.equals(getPackageName())) {
            handler.post(this::authorizationChanged);
        }
    };
    private PowerManager.WakeLock wakeLock;
    private boolean gpsAdded;
    private boolean networkAdded;
    private boolean foreground;
    private long lastNotificationTime;
    private RunController.State lastNotificationState;

    public final class LocalBinder extends Binder {
        LocationMockService service() { return LocationMockService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        results = new RunResultStore(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FakeGPS:Run");
        wakeLock.setReferenceCounted(false);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "模拟位置运行", NotificationManager.IMPORTANCE_LOW));
        controller = new RunController(SystemClock::elapsedRealtime, new RunController.Scheduler() {
            @Override public void post(Runnable task, long delayMs) { handler.postDelayed(task, delayMs); }
            @Override public void cancel(Runnable task) { handler.removeCallbacks(task); }
        }, new RunController.Sink() {
            // Criteria constants have the same values as ProviderProperties (added in API 31).
            @android.annotation.SuppressLint("WrongConstant")
            @Override public void open() {
                // Enter the foreground before any provider operation which could fail.
                startForeground(1, notification(controller.snapshot()));
                foreground = true;
                checkAuthorization();
                locationManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
                gpsAdded = true;
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
                locationManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false, true,
                        true, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
                networkAdded = true;
                locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }

            @Override public void write(GeoUtils.TrackPosition position, double speed) {
                // Android may silently ignore a write when the mock AppOp is revoked.
                // Check both sides of the Binder calls before committing any progress.
                checkAuthorization();
                writeLocation(LocationManager.GPS_PROVIDER, position, speed, 6f);
                writeLocation(LocationManager.NETWORK_PROVIDER, position, speed, 15f);
                checkAuthorization();
            }

            @Override public void close() { releaseResources(); }
        }, this::publish);
        appOps = getSystemService(AppOpsManager.class);
        appOps.startWatchingMode(AppOpsManager.OPSTR_MOCK_LOCATION, getPackageName(),
                authorizationListener);
    }

    void addListener(RunController.Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        listener.changed(controller.snapshot());
    }

    void removeListener(RunController.Listener listener) { listeners.remove(listener); }

    void startRun(RunController.Config config) {
        if (!controller.prepare(config)) return;
        try {
            ContextCompat.startForegroundService(this, new Intent(this, LocationMockService.class)
                    .setAction(START));
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to start foreground service", e);
            controller.fail(e);
        }
    }

    void pauseRun() { controller.pause(); }
    void resumeRun() { controller.resume(); }
    void stopRun() { controller.stop(); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (START.equals(action)) controller.start();
        else if (PAUSE.equals(action)) controller.pause();
        else if (RESUME.equals(action)) controller.resume();
        else if (STOP.equals(action)) controller.stop();
        if (!controller.snapshot().active()) stopSelf(startId);
        // A killed process must never silently restart a run with missing configuration.
        return START_NOT_STICKY;
    }

    private void checkAuthorization() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("缺少定位权限");
        }
        AppOpsManager manager = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        if (manager.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), getPackageName())
                != AppOpsManager.MODE_ALLOWED) {
            throw new SecurityException("未被选为模拟位置应用");
        }
    }

    private void authorizationChanged() {
        // Also terminate paused sessions, which have no scheduled location writes.
        if (!controller.snapshot().active()) return;
        try {
            checkAuthorization();
        } catch (SecurityException e) {
            controller.fail(e);
        }
    }

    private void writeLocation(String provider, GeoUtils.TrackPosition position,
                               double speed, float accuracy) {
        Location location = new Location(provider);
        location.setLatitude(position.latitude);
        location.setLongitude(position.longitude);
        location.setAltitude(position.altitude);
        location.setBearing(position.bearing);
        location.setSpeed((float) speed);
        location.setAccuracy(accuracy);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        locationManager.setTestProviderLocation(provider, location);
    }

    @android.annotation.SuppressLint({"WakelockTimeout", "Wakelock"})
    private void publish(RunController.Snapshot snapshot) {
        results.save(snapshot);
        // Held only while actively advancing. Every pause, stop, error and destruction releases it.
        if (snapshot.state == RunController.State.RUNNING) {
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } else if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        long now = SystemClock.elapsedRealtime();
        if (foreground && (snapshot.state != lastNotificationState || now - lastNotificationTime >= 1000)) {
            getSystemService(NotificationManager.class).notify(1, notification(snapshot));
            lastNotificationState = snapshot.state;
            lastNotificationTime = now;
        }
        for (RunController.Listener listener : new ArrayList<>(listeners)) listener.changed(snapshot);
    }

    private Notification notification(RunController.Snapshot snapshot) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, FakeGPSActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean paused = snapshot.state == RunController.State.PAUSED;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(paused ? "FakeGPS · 已暂停" : "FakeGPS · 模拟位置运行中")
                .setContentText(snapshot.config == null ? "正在准备" : String.format(Locale.US,
                        "%s · %.2f / %.2f km", snapshot.config.routeName,
                        snapshot.distance / 1000, snapshot.config.totalDistance / 1000))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true);
        if (snapshot.state == RunController.State.RUNNING || paused) {
            builder.addAction(0, paused ? "恢复" : "暂停", command(paused ? RESUME : PAUSE, 1));
        }
        return builder.addAction(0, "停止", command(STOP, 2)).build();
    }

    private PendingIntent command(String action, int requestCode) {
        return PendingIntent.getService(this, requestCode,
                new Intent(this, LocationMockService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void releaseResources() {
        if (wakeLock.isHeld()) wakeLock.release();
        if (gpsAdded) removeProvider(LocationManager.GPS_PROVIDER);
        if (networkAdded) removeProvider(LocationManager.NETWORK_PROVIDER);
        gpsAdded = false;
        networkAdded = false;
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        stopSelf();
    }

    private void removeProvider(String provider) {
        try {
            locationManager.removeTestProvider(provider);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to remove test provider " + provider, e);
        }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        appOps.stopWatchingMode(authorizationListener);
        controller.stop();
        handler.removeCallbacksAndMessages(null);
        releaseResources();
        listeners.clear();
        super.onDestroy();
    }
}
