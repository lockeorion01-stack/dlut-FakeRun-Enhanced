package com.langqi.fakegps;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
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

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalSettings = new HashMap<>(context.getSharedPreferences("run_settings", 0).getAll());
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
