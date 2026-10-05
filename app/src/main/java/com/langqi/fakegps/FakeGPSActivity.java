package com.langqi.fakegps;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Displays service snapshots; never owns or schedules a running session. */
public class FakeGPSActivity extends AppCompatActivity {
    private RouteViewModel model;
    private RouteViewModel.Routes routes;
    private LocationMockService service;
    private boolean bound;
    private String pendingReplacementKey;
    private Spinner routeSelector;
    private ArrayAdapter<String> routeAdapter;
    private final List<RouteViewModel.Entry> displayedEntries = new ArrayList<>();
    private TextView kmlInfo, currentPaceView, currentSpeedDisplay, targetPaceView;
    private TextView distanceDoneView, distanceRemainingView, elapsedTimeView;
    private TextView progressText, lapCounterView, statusBadgeView;
    private EditText inputPace, inputRepetitions, inputInterval;
    private ProgressBar progressBar;
    private Button btnStart, btnPause, btnResume, btnStop, btnUpdateRoute, btnDeleteRoute;
    private RunController.Config shownConfig;

    private final RunController.Listener runListener = snapshot -> {
        // A retained terminal service must not restore the summary of a different selected route.
        if (!snapshot.active() && snapshot.config != null && routes != null && !routes.busy
                && routes.selected != null && !routes.selected.key.equals(snapshot.config.routeKey)) {
            render();
            return;
        }
        if (snapshot.config != null || model.session == null || model.session.active()) {
            model.session = snapshot;
        }
        render();
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((LocationMockService.LocalBinder) binder).service();
            service.addListener(runListener);
            render();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            if (model.session != null && model.session.active()) {
                RunController.Snapshot previous = model.session;
                model.session = new RunController.Snapshot(RunController.State.ERROR, previous.config,
                        previous.distance, previous.elapsedMs, "定位服务已中断，请重新开始");
            }
            render();
        }
    };

    private final ActivityResultLauncher<String> browseFile = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                String replacement = pendingReplacementKey;
                pendingReplacementKey = null;
                if (uri != null && !active()) {
                    model.session = null;
                    shownConfig = null;
                    model.importRoute(uri, replacement);
                }
            });
    private final ActivityResultLauncher<String[]> permissionRequest = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> Toast.makeText(this, "权限设置已更新，请点击开始", Toast.LENGTH_SHORT).show());
    private final ActivityResultLauncher<String> notificationRequest = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), result -> { });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fake_gps);
        model = new ViewModelProvider(this).get(RouteViewModel.class);
        if (savedInstanceState != null) pendingReplacementKey = savedInstanceState.getString("replacement");
        initializeViews();
        setupControls();
        SharedPreferences settings = getSharedPreferences("run_settings", MODE_PRIVATE);
        inputPace.setText(settings.getString("pace", "5:33"));
        inputRepetitions.setText(settings.getString("laps", "1"));
        inputInterval.setText(settings.getString("interval", "0.3"));
        model.routes().observe(this, value -> {
            routes = value;
            updateRoutes();
            render();
        });
    }

    private void initializeViews() {
        routeSelector = findViewById(R.id.route_selector);
        routeSelector.setSaveEnabled(false);
        kmlInfo = findViewById(R.id.kml_info);
        currentPaceView = findViewById(R.id.current_pace);
        currentSpeedDisplay = findViewById(R.id.current_speed_display);
        targetPaceView = findViewById(R.id.target_pace);
        distanceDoneView = findViewById(R.id.distance_done);
        distanceRemainingView = findViewById(R.id.distance_remaining);
        elapsedTimeView = findViewById(R.id.elapsed_time);
        progressText = findViewById(R.id.progress_text);
        lapCounterView = findViewById(R.id.lap_counter);
        statusBadgeView = findViewById(R.id.status_badge);
        inputPace = findViewById(R.id.input_pace);
        inputRepetitions = findViewById(R.id.input_repetitions);
        inputInterval = findViewById(R.id.input_interval);
        progressBar = findViewById(R.id.progress_bar);
        btnStart = findViewById(R.id.btn_start);
        btnPause = findViewById(R.id.btn_pause);
        btnResume = findViewById(R.id.btn_resume);
        btnStop = findViewById(R.id.btn_stop);
        btnUpdateRoute = findViewById(R.id.btn_update_route);
        btnDeleteRoute = findViewById(R.id.btn_delete_route);
    }

    private void setupControls() {
        routeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        routeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        routeSelector.setAdapter(routeAdapter);
        routeSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (active() || routes == null || routes.busy || position >= displayedEntries.size()) return;
                RouteViewModel.Entry entry = displayedEntries.get(position);
                if (routes.selected != null && routes.selected.key.equals(entry.key)) return;
                model.session = null;
                shownConfig = null;
                model.select(entry);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        findViewById(R.id.btn_browse).setOnClickListener(v -> openPicker(false));
        btnUpdateRoute.setOnClickListener(v -> openPicker(true));
        btnDeleteRoute.setOnClickListener(v -> deleteRoute());
        btnStart.setOnClickListener(v -> startRun());
        btnPause.setOnClickListener(v -> { if (service != null) service.pauseRun(); });
        btnResume.setOnClickListener(v -> { if (service != null) service.resumeRun(); });
        btnStop.setOnClickListener(v -> { if (service != null) service.stopRun(); });
        findViewById(R.id.btn_pace_up).setOnClickListener(v -> adjustPace(5));
        findViewById(R.id.btn_pace_down).setOnClickListener(v -> adjustPace(-5));
        findViewById(R.id.btn_laps_up).setOnClickListener(v -> adjustLaps(1));
        findViewById(R.id.btn_laps_down).setOnClickListener(v -> adjustLaps(-1));
        inputPace.setOnFocusChangeListener((v, focused) -> { if (!focused) updateTargets(); });
        inputRepetitions.setOnFocusChangeListener((v, focused) -> { if (!focused) render(); });
        statusBadgeView.setOnClickListener(v -> {
            if (model.session != null && model.session.state == RunController.State.ERROR) {
                new AlertDialog.Builder(this).setTitle("运行失败").setMessage(model.session.error)
                        .setPositiveButton("确定", null)
                        .setNeutralButton("开发者选项", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                            try { startActivity(intent); }
                            catch (ActivityNotFoundException e) {
                                Toast.makeText(this, "请在系统设置中打开开发者选项", Toast.LENGTH_LONG).show();
                            }
                        }).show();
            }
        });
    }

    private void updateRoutes() {
        displayedEntries.clear();
        displayedEntries.addAll(routes.entries);
        routeAdapter.clear();
        for (RouteViewModel.Entry entry : displayedEntries) routeAdapter.add(entry.name);
        routeAdapter.notifyDataSetChanged();
        selectDisplayedRoute(active() && model.session.config != null
                ? model.session.config.routeKey : routes.selected == null ? null : routes.selected.key);
    }

    private void selectDisplayedRoute(String key) {
        for (int index = 0; index < displayedEntries.size(); index++) {
            if (displayedEntries.get(index).key.equals(key)) {
                if (routeSelector.getSelectedItemPosition() != index) routeSelector.setSelection(index);
                return;
            }
        }
    }

    private boolean active() { return model != null && model.session != null && model.session.active(); }

    private void startRun() {
        if (service == null || routes == null || routes.busy || routes.points.isEmpty()) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionRequest.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        try {
            int pace = Pace.parse(inputPace.getText().toString());
            int laps = Integer.parseInt(inputRepetitions.getText().toString().trim());
            double interval = Double.parseDouble(inputInterval.getText().toString().trim());
            if (!Double.isFinite(interval) || interval < 0.1 || interval > 5 || laps < 1) {
                throw new IllegalArgumentException("圈数至少为 1，更新间隔应为 0.1～5 秒");
            }
            RunController.Config config = new RunController.Config(routes.selected.key, routes.selected.name,
                    routes.points, pace, laps, Math.round(interval * 1000));
            inputPace.setText(Pace.format(pace));
            saveSettings();
            service.startRun(config);
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage() == null ? "请输入有效运行参数" : e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openPicker(boolean replace) {
        if (active() || routes == null || routes.busy) return;
        if (replace && (routes.selected == null || routes.selected.file == null)) return;
        pendingReplacementKey = replace ? routes.selected.key : null;
        browseFile.launch("*/*");
    }

    private void deleteRoute() {
        if (active() || routes == null || routes.busy || routes.selected == null
                || routes.selected.file == null) return;
        RouteViewModel.Entry entry = routes.selected;
        new AlertDialog.Builder(this).setTitle("删除路线").setMessage("确定删除“" + entry.name + "”吗？")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> {
                    if (!active()) { model.session = null; model.delete(entry); }
                }).show();
    }

    private void adjustPace(int delta) {
        try {
            inputPace.setText(Pace.format(Math.max(1, Math.min(599999,
                    Pace.parse(inputPace.getText().toString()) + delta))));
            updateTargets();
        } catch (IllegalArgumentException e) {
            inputPace.setError("请输入有效配速，例如 5:33");
        }
    }

    private void adjustLaps(int delta) {
        try {
            long laps = Long.parseLong(inputRepetitions.getText().toString().trim());
            inputRepetitions.setText(String.valueOf(Math.max(1, Math.min(Integer.MAX_VALUE, laps + delta))));
            model.session = null;
            render();
        } catch (NumberFormatException e) { inputRepetitions.setError("请输入有效圈数"); }
    }

    private void updateTargets() {
        try {
            targetPaceView.setText(Pace.format(Pace.parse(inputPace.getText().toString())) + " /km");
        } catch (IllegalArgumentException e) { targetPaceView.setText("--:-- /km"); }
    }

    private void render() {
        if (routes == null) return;
        RunController.Snapshot snapshot = model.session;
        boolean active = active();
        if (snapshot != null && snapshot.config != null && shownConfig != snapshot.config && active) {
            shownConfig = snapshot.config;
            inputPace.setText(Pace.format(shownConfig.paceSeconds));
            inputRepetitions.setText(String.valueOf(shownConfig.laps));
            inputInterval.setText(String.format(Locale.US, "%.3f", shownConfig.intervalMs / 1000.0));
        }
        RunController.State state = snapshot == null ? RunController.State.READY : snapshot.state;
        String status;
        switch (state) {
            case STARTING: status = "正在启动"; break;
            case RUNNING: status = "运行中"; break;
            case PAUSED: status = "已暂停"; break;
            case COMPLETED: status = "已完成"; break;
            case STOPPED: status = "已停止"; break;
            case ERROR: status = "运行失败"; break;
            default: status = "待命";
        }
        statusBadgeView.setText(status);
        statusBadgeView.setTextColor(ContextCompat.getColor(this, state == RunController.State.ERROR
                ? R.color.danger_red : state == RunController.State.RUNNING
                ? R.color.status_green : R.color.text_secondary));
        boolean editable = !active && !routes.busy;
        btnStart.setEnabled(editable && service != null && !routes.points.isEmpty());
        btnPause.setEnabled(service != null && state == RunController.State.RUNNING);
        btnResume.setEnabled(service != null && state == RunController.State.PAUSED);
        btnStop.setEnabled(service != null && active && state != RunController.State.STARTING);
        routeSelector.setEnabled(editable && service != null);
        inputPace.setEnabled(editable);
        inputRepetitions.setEnabled(editable);
        inputInterval.setEnabled(editable);
        for (int id : new int[]{R.id.btn_browse, R.id.btn_pace_up, R.id.btn_pace_down,
                R.id.btn_laps_up, R.id.btn_laps_down}) findViewById(id).setEnabled(editable);
        boolean saved = editable && routes.selected != null && routes.selected.file != null;
        btnUpdateRoute.setEnabled(saved);
        btnDeleteRoute.setEnabled(saved);

        String info;
        if (active && snapshot.config != null) {
            selectDisplayedRoute(snapshot.config.routeKey);
            info = snapshot.config.routeName + String.format(Locale.US,
                    "\n单圈距离: %.2f km", snapshot.config.lapDistance / 1000);
        } else if (routes.busy) info = "正在处理路线…";
        else if (routes.error != null) info = "路线操作失败: " + routes.error;
        else if (routes.selected == null) info = "请选择路线";
        else info = routes.selected.name + String.format(Locale.US,
                    "\n路径点: %d | 单圈距离: %.2f km", routes.points.size(), routes.distance / 1000);
        if (snapshot != null && snapshot.error != null) info += "\n运行失败: " + snapshot.error;
        kmlInfo.setText(info);

        int laps = 1;
        try { laps = Math.max(1, Integer.parseInt(inputRepetitions.getText().toString())); }
        catch (NumberFormatException ignored) { }
        double total = routes.distance * laps;
        double done = 0;
        long elapsed = 0;
        double lapDistance = routes.distance;
        if (snapshot != null && snapshot.config != null) {
            laps = snapshot.config.laps;
            total = snapshot.config.totalDistance;
            lapDistance = snapshot.config.lapDistance;
            done = snapshot.distance;
            elapsed = snapshot.elapsedMs;
        }
        int progress = total <= 0 ? 0 : (int) Math.min(100, done / total * 100);
        if (state == RunController.State.COMPLETED) progress = 100;
        progressBar.setProgress(progress);
        progressText.setText(progress + "%");
        int lap = done <= 0 || lapDistance <= 0 ? 0 : Math.min(laps, (int) (done / lapDistance) + 1);
        lapCounterView.setText(String.format(Locale.US, "圈 %d/%d", lap, laps));
        distanceDoneView.setText(String.format(Locale.US, "%.2f km", done / 1000));
        distanceRemainingView.setText(String.format(Locale.US, "%.2f km", Math.max(0, total - done) / 1000));
        elapsedTimeView.setText(String.format(Locale.US, "%02d:%02d:%02d",
                elapsed / 3600000, elapsed / 60000 % 60, elapsed / 1000 % 60));
        boolean moving = state == RunController.State.RUNNING && snapshot.config != null;
        currentPaceView.setText(moving ? Pace.format(snapshot.config.paceSeconds) : "--:--");
        currentSpeedDisplay.setText(String.format(Locale.US, "%.2f m/s", moving ? snapshot.config.speed : 0));
        updateTargets();
    }

    private void saveSettings() {
        getSharedPreferences("run_settings", MODE_PRIVATE).edit()
                .putString("pace", inputPace.getText().toString())
                .putString("laps", inputRepetitions.getText().toString())
                .putString("interval", inputInterval.getText().toString()).apply();
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, LocationMockService.class), connection, Context.BIND_AUTO_CREATE);
        render();
    }

    @Override protected void onStop() {
        saveSettings();
        if (service != null) service.removeListener(runListener);
        service = null;
        if (bound) unbindService(connection);
        bound = false;
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("replacement", pendingReplacementKey);
        saveSettings();
        super.onSaveInstanceState(state);
    }
}
