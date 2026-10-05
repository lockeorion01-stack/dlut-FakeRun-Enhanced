package com.langqi.fakegps;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RunLifecycleTest {
    private Context context;
    private ActivityScenario<FakeGPSActivity> scenario;
    private LocationMockService service;
    private ServiceConnection connection;
    private final AtomicReference<RunController.Snapshot> snapshot = new AtomicReference<>();
    private final RunController.Listener listener = snapshot::set;
    private int previousMockMode;
    private Map<String, ?> originalSettings;
    private Map<String, ?> originalResults;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalSettings = new HashMap<>(context.getSharedPreferences("run_settings", 0).getAll());
        SharedPreferences results = context.getSharedPreferences(RunResultStore.PREFERENCES, 0);
        originalResults = new HashMap<>(results.getAll());
        assertTrue(results.edit().clear().commit());
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        previousMockMode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(), context.getPackageName());
        shell("pm grant " + context.getPackageName() + " " + Manifest.permission.ACCESS_COARSE_LOCATION);
        shell("pm grant " + context.getPackageName() + " " + Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) {
            shell("pm grant " + context.getPackageName() + " " + Manifest.permission.POST_NOTIFICATIONS);
        }
        shell("appops set " + context.getPackageName() + " android:mock_location allow");
        scenario = ActivityScenario.launch(FakeGPSActivity.class);
        CountDownLatch connected = new CountDownLatch(1);
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service = ((LocationMockService.LocalBinder) binder).service();
                service.addListener(listener);
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        assertTrue(context.bindService(new Intent(context, LocationMockService.class),
                connection, Context.BIND_AUTO_CREATE));
        assertTrue(connected.await(10, TimeUnit.SECONDS));
        await(() -> {
            AtomicBoolean ready = new AtomicBoolean();
            scenario.onActivity(activity -> ready.set(activity.findViewById(R.id.btn_start).isEnabled()));
            return ready.get();
        });
        scenario.onActivity(activity -> {
            ((EditText) activity.findViewById(R.id.input_pace)).setText("5:00");
            ((EditText) activity.findViewById(R.id.input_repetitions)).setText("1");
            ((EditText) activity.findViewById(R.id.input_interval)).setText("0.1");
        });
    }

    @After public void cleanup() throws Exception {
        if (service != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            service.stopRun();
            service.removeListener(listener);
        });
        if (scenario != null) scenario.close();
        if (connection != null) context.unbindService(connection);
        if (context != null && originalSettings != null) {
            SharedPreferences.Editor editor = context.getSharedPreferences("run_settings", 0).edit().clear();
            for (Map.Entry<String, ?> entry : originalSettings.entrySet()) {
                editor.putString(entry.getKey(), (String) entry.getValue());
            }
            assertTrue(editor.commit());
        }
        if (context != null && originalResults != null) {
            // stopService also covers an unbound run if a background assertion failed.
            context.stopService(new Intent(context, LocationMockService.class));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SharedPreferences.Editor editor = context.getSharedPreferences(RunResultStore.PREFERENCES, 0)
                    .edit().clear();
            for (Map.Entry<String, ?> entry : originalResults.entrySet()) {
                editor.putString(entry.getKey(), (String) entry.getValue());
            }
            assertTrue(editor.commit());
        }
        if (context != null) shell("appops set " + context.getPackageName() + " android:mock_location "
                + (previousMockMode == AppOpsManager.MODE_ALLOWED ? "allow"
                : previousMockMode == AppOpsManager.MODE_IGNORED ? "ignore"
                : previousMockMode == AppOpsManager.MODE_ERRORED ? "deny" : "default"));
    }

    @Test public void rotationReopenAndBackgroundPreserveOneRunningSession() throws Exception {
        click(R.id.btn_start);
        await(() -> snapshot.get().state == RunController.State.RUNNING && snapshot.get().distance > 0);
        double before = snapshot.get().distance;
        RunController.Config config = snapshot.get().config;
        scenario.recreate();
        await(() -> status().equals("运行中"));
        assertSame(config, snapshot.get().config);
        assertTrue(snapshot.get().distance >= before);
        click(R.id.btn_pause);
        await(() -> snapshot.get().state == RunController.State.PAUSED);
        double paused = snapshot.get().distance;
        SystemClock.sleep(400);
        assertEquals(paused, snapshot.get().distance, 0);
        scenario.close();
        scenario = ActivityScenario.launch(FakeGPSActivity.class);
        await(() -> status().equals("已暂停"));
        assertSame(config, snapshot.get().config);
        click(R.id.btn_resume);
        await(() -> snapshot.get().state == RunController.State.RUNNING);
        scenario.moveToState(Lifecycle.State.CREATED);
        double background = snapshot.get().distance;
        await(() -> snapshot.get().distance > background + 1);
        scenario.moveToState(Lifecycle.State.RESUMED);
        await(() -> status().equals("运行中"));
        click(R.id.btn_stop);
        await(() -> snapshot.get().state == RunController.State.STOPPED);
        assertTrue(snapshot.get().distance > 0);
        assertEquals("已停止", status());
    }

    @Test public void missingMockAuthorizationIsVisibleAndDoesNotAdvance() throws Exception {
        shell("appops set " + context.getPackageName() + " android:mock_location ignore");
        click(R.id.btn_start);
        await(() -> snapshot.get().state == RunController.State.ERROR);
        assertEquals(0, snapshot.get().distance, 0);
        assertEquals("运行失败", status());
        scenario.onActivity(activity -> {
            assertEquals("0%", ((TextView) activity.findViewById(R.id.progress_text)).getText().toString());
            assertTrue(activity.findViewById(R.id.btn_start).isEnabled());
        });
    }

    @Test public void fiveSecondIntervalAdvancesForFiveSecondsOnDevice() throws Exception {
        scenario.onActivity(activity -> ((EditText) activity.findViewById(R.id.input_interval)).setText("5"));
        click(R.id.btn_start);
        await(() -> snapshot.get().elapsedMs >= 5000);
        assertEquals(RunController.State.RUNNING, snapshot.get().state);
        assertTrue(snapshot.get().distance >= 16.6);
    }

    @Test public void revokingAuthorizationWhileRunningStopsBeforeTheNextSlowTick() throws Exception {
        scenario.onActivity(activity -> ((EditText) activity.findViewById(R.id.input_interval)).setText("5"));
        click(R.id.btn_start);
        await(() -> snapshot.get().state == RunController.State.RUNNING && snapshot.get().distance > 0);
        RunController.Snapshot accepted = snapshot.get();
        shell("appops set " + context.getPackageName() + " android:mock_location ignore");
        await(() -> snapshot.get().state == RunController.State.ERROR);
        assertEquals(accepted.distance, snapshot.get().distance, 0);
        assertEquals(accepted.elapsedMs, snapshot.get().elapsedMs);
        assertEquals("运行失败", status());
        SystemClock.sleep(300);
        assertEquals(accepted.distance, snapshot.get().distance, 0);
        assertEquals(RunController.State.ERROR, new RunResultStore(context).read().state);
    }

    @Test public void revokingAuthorizationWhilePausedTerminatesWithoutWaitingForResume() throws Exception {
        click(R.id.btn_start);
        await(() -> snapshot.get().state == RunController.State.RUNNING && snapshot.get().distance > 0);
        click(R.id.btn_pause);
        await(() -> snapshot.get().state == RunController.State.PAUSED);
        RunController.Snapshot paused = snapshot.get();
        shell("appops set " + context.getPackageName() + " android:mock_location ignore");
        await(() -> snapshot.get().state == RunController.State.ERROR);
        assertEquals(paused.distance, snapshot.get().distance, 0);
        assertEquals(paused.elapsedMs, snapshot.get().elapsedMs);
    }

    @Test public void resumeRechecksAuthorizationBeforeTheWatcherCallbackCanRun() throws Exception {
        click(R.id.btn_start);
        await(() -> snapshot.get().state == RunController.State.RUNNING && snapshot.get().distance > 0);
        click(R.id.btn_pause);
        RunController.Snapshot paused = snapshot.get();
        // Hold the main thread so that resume happens before the queued AppOp listener.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                shell("appops set " + context.getPackageName() + " android:mock_location ignore");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            service.resumeRun();
            assertEquals(RunController.State.ERROR, snapshot.get().state);
        });
        assertEquals(paused.distance, snapshot.get().distance, 0);
        assertEquals(paused.elapsedMs, snapshot.get().elapsedMs);
    }

    @Test public void backgroundAuthorizationRevocationPersistsFailureAfterServiceDestruction() throws Exception {
        click(R.id.btn_start);
        await(() -> snapshot.get().state == RunController.State.RUNNING && snapshot.get().distance > 0);
        String routeName = snapshot.get().config.routeName;
        closeAllClients();
        shell("appops set " + context.getPackageName() + " android:mock_location ignore");
        awaitSavedResult(RunController.State.ERROR);
        assertTrue(new RunResultStore(context).read().distance > 0);
        reopenAndCheckSummary(routeName, "运行失败");
    }

    @Test public void versionOneRouteSelectionMigratesAndSurvivesReopen() throws Exception {
        scenario.close();
        // The old default route ID must map to three_km even if the new resource table moves.
        assertTrue(context.getSharedPreferences("run_settings", 0).edit()
                .putString("route", "raw:" + 0x7f0f0005).commit());
        scenario = ActivityScenario.launch(FakeGPSActivity.class);
        awaitRoute("raw:three_km");
        assertEquals("raw:three_km", context.getSharedPreferences("run_settings", 0).getString("route", null));
        scenario.close();
        scenario = ActivityScenario.launch(FakeGPSActivity.class);
        awaitRoute("raw:three_km");
    }

    private void awaitRoute(String key) throws Exception {
        await(() -> {
            AtomicBoolean matches = new AtomicBoolean();
            scenario.onActivity(activity -> {
                RouteViewModel.Routes routes = new ViewModelProvider(activity).get(RouteViewModel.class)
                        .routes().getValue();
                matches.set(!routes.busy && routes.selected != null && key.equals(routes.selected.key));
            });
            return matches.get();
        });
    }

    @Test public void androidParserAndAtomicReplacementWorkTogether() throws Exception {
        File directory = new File(context.getCacheDir(), "route-test-" + System.nanoTime());
        try {
            RouteRepository repository = new RouteRepository(directory);
            String xml = "<k:kml xmlns:k='http://www.opengis.net/kml/2.2'><k:LineString>"
                    + "<k:coordinates>108,22 108,22.001</k:coordinates></k:LineString></k:kml>";
            File saved = repository.save(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                    "route.kml", null);
            String replacement = xml.replace("22.001", "22.002");
            repository.save(new ByteArrayInputStream(replacement.getBytes(StandardCharsets.UTF_8)),
                    "route.kml", saved);
            assertEquals(1, repository.files().size());
            try (InputStream input = new java.io.FileInputStream(saved)) {
                assertEquals(22.002, KmlParser.read(input).get(1).latitude, 1e-10);
            }
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) assertTrue(file.delete());
            assertTrue(directory.delete());
        }
    }

    @Test public void completionWithoutBoundClientsSurvivesServiceDestructionAndReopen() throws Exception {
        RunController.Config shortRun = new RunController.Config("background", "后台完成测试",
                Arrays.asList(new GeoUtils.TrackPoint(22, 108, 10),
                        new GeoUtils.TrackPoint(22.0001, 108, 20)), 300, 1, 100);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> service.startRun(shortRun));
        await(() -> snapshot.get().state == RunController.State.RUNNING);
        closeAllClients();
        awaitSavedResult(RunController.State.COMPLETED);
        RunResultStore.Result result = new RunResultStore(context).read();
        assertEquals(shortRun.totalDistance, result.distance, 1e-8);
        assertTrue(result.elapsedMs > 0);
        reopenAndCheckSummary("后台完成测试", "已完成");
        assertEquals("待命", status()); // Reading a saved result must not restart a run.
    }

    @Test public void backgroundProviderErrorSurvivesServiceDestructionAndReopen() throws Exception {
        click(R.id.btn_start);
        await(() -> snapshot.get().state == RunController.State.RUNNING && snapshot.get().distance > 0);
        double accepted = snapshot.get().distance;
        String routeName = snapshot.get().config.routeName;
        closeAllClients();
        // Removing our GPS test provider makes the next write fail deterministically.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                context.getSystemService(LocationManager.class).removeTestProvider(LocationManager.GPS_PROVIDER));
        awaitSavedResult(RunController.State.ERROR);
        RunResultStore.Result result = new RunResultStore(context).read();
        assertTrue(result.distance >= accepted);
        assertNotNull(result.error);
        reopenAndCheckSummary(routeName, "运行失败");
        assertTrue(text(R.id.last_run_result).contains(result.error));
    }

    @Test public void notificationStopWithoutBoundClientsPreservesSummary() throws Exception {
        click(R.id.btn_start);
        await(() -> snapshot.get().state == RunController.State.RUNNING && snapshot.get().distance > 0);
        String routeName = snapshot.get().config.routeName;
        Notification notification = Arrays.stream(context.getSystemService(NotificationManager.class)
                .getActiveNotifications()).filter(item -> item.getId() == 1).findFirst().get().getNotification();
        closeAllClients();
        notification.actions[notification.actions.length - 1].actionIntent.send();
        awaitSavedResult(RunController.State.STOPPED);
        assertTrue(new RunResultStore(context).read().distance > 0);
        reopenAndCheckSummary(routeName, "已停止");
    }

    @Test public void manualAndButtonLapEditsPreviewNextRunWithoutChangingPreviousResult() throws Exception {
        click(R.id.btn_start);
        await(() -> snapshot.get().state == RunController.State.RUNNING && snapshot.get().distance > 0);
        click(R.id.btn_stop);
        await(() -> snapshot.get().state == RunController.State.STOPPED);
        String previousResult = text(R.id.last_run_result);
        RunController.Config config = snapshot.get().config;
        scenario.onActivity(activity -> ((EditText) activity.findViewById(R.id.input_repetitions)).setText("2"));
        assertTrue(text(R.id.next_run_target).contains("2 圈"));
        assertTrue(text(R.id.next_run_target).contains(String.format(Locale.getDefault(), "%.2f", config.lapDistance * 2 / 1000)));
        click(R.id.btn_laps_up);
        assertTrue(text(R.id.next_run_target).contains("3 圈"));
        assertEquals(previousResult, text(R.id.last_run_result));
        scenario.recreate();
        await(() -> text(R.id.next_run_target).contains("3 圈"));
        assertEquals(previousResult, text(R.id.last_run_result));
    }

    @Test public void malformedSavedResultDoesNotBlockOpeningTheScreen() throws Exception {
        context.getSharedPreferences(RunResultStore.PREFERENCES, 0).edit()
                .putString("last_result", "{broken").commit();
        assertNull(new RunResultStore(context).read());
        scenario.recreate();
        await(() -> status().equals("待命"));
    }

    private void closeAllClients() {
        scenario.close();
        scenario = null;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> service.removeListener(listener));
        context.unbindService(connection);
        connection = null;
        service = null;
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @SuppressWarnings("deprecation")
    private void awaitSavedResult(RunController.State state) throws Exception {
        await(() -> {
            RunResultStore.Result result = new RunResultStore(context).read();
            return result != null && result.state == state;
        });
        await(() -> context.getSystemService(ActivityManager.class).getRunningServices(Integer.MAX_VALUE)
                .stream().noneMatch(info -> info.service.getClassName().equals(LocationMockService.class.getName())));
    }

    private void reopenAndCheckSummary(String routeName, String state) throws Exception {
        scenario = ActivityScenario.launch(FakeGPSActivity.class);
        await(() -> text(R.id.last_run_result).contains(routeName) && text(R.id.last_run_result).contains(state));
    }

    private String text(int id) {
        AtomicReference<String> value = new AtomicReference<>();
        scenario.onActivity(activity -> value.set(((TextView) activity.findViewById(id)).getText().toString()));
        return value.get();
    }

    private void click(int id) { scenario.onActivity(activity -> ((Button) activity.findViewById(id)).performClick()); }

    private String status() {
        AtomicReference<String> text = new AtomicReference<>();
        scenario.onActivity(activity -> text.set(((TextView) activity.findViewById(R.id.status_badge))
                .getText().toString()));
        return text.get();
    }

    private static void await(BooleanSupplier predicate) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 12000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate.getAsBoolean()) return;
            SystemClock.sleep(50);
        }
        fail("Timed out waiting for run state");
    }

    private static void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) { /* Wait for command completion. */ }
        }
    }
}
