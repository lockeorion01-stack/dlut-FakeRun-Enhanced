package com.langqi.fakegps;

import android.app.AlertDialog;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Log;
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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class FakeGPSActivity extends AppCompatActivity {
    private static final String LOG_TAG = "langqi_log";
    private static final String ROUTES_DIRECTORY = "routes";
    /** 与 LocationMockService 约定的模拟位置广播 action。 */
    private static final String ACTION_MOCK_LOCATION = "com.langqi.fakegps.MOCK_LOCATION";

    /** 上报的水平精度（米）。真实手机在操场环境下的 GPS 精度通常在数米量级。 */
    private static final float MIN_ACCURACY_METERS = 5.0f;
    private static final float MAX_ACCURACY_METERS = 8.0f;
    /**
     * 单次更新的最大计时增量（秒）。屏幕熄灭或应用被切到后台时 Handler 回调会停摆，
     * 若直接按墙钟补算，唤醒瞬间会一次性冲进一大段距离。这里把单次增量钳住。
     */
    private static final double MAX_TICK_SECONDS = 2.0;

    // Built-in routes (12 individual closed-loop KML files)
    private static final int[] ROUTE_RAW_IDS = {
        R.raw.asean_route_1, R.raw.asean_route_2, R.raw.asean_route_3,
        R.raw.asean_route_4, R.raw.asean_route_5,
        R.raw.uni_route_1, R.raw.uni_route_2, R.raw.uni_route_3,
        R.raw.uni_route_4, R.raw.uni_route_5, R.raw.uni_route_6,
        R.raw.three_km
    };
    private static final String[] ROUTE_NAMES = {
        "东盟 1 (0.44km)", "东盟 2 (0.44km)", "东盟 3 (0.44km)",
        "东盟 4 (0.45km)", "东盟 5 (0.44km)",
        "大学路 1 (0.44km)", "大学路 2 (0.43km)", "大学路 3 (0.43km)",
        "大学路 4 (0.43km)", "大学路 5 (0.42km)", "大学路 6 (0.44km)",
        "默认路径 (0.42km)"
    };

    // UI elements
    private Spinner routeSelector;
    private TextView kmlInfo;
    private TextView currentPaceView;
    private TextView currentSpeedDisplay;
    private TextView targetPaceView;
    private TextView distanceDoneView;
    private TextView distanceRemainingView;
    private TextView elapsedTimeView;
    private TextView progressText;
    private TextView lapCounterView;
    private TextView statusBadgeView;
    private EditText inputPace;
    private EditText inputRepetitions;
    private EditText inputInterval;
    private ProgressBar progressBar;
    private Button btnStart;
    private Button btnPause;
    private Button btnResume;
    private Button btnStop;
    private Button btnUpdateRoute;
    private Button btnDeleteRoute;

    // State
    private final List<GeoUtils.TrackPoint> trackPoints = new ArrayList<>();
    private List<Double> progressList = new ArrayList<>();
    private double totalDistance = 0;
    private boolean isRunning = false;
    private boolean isPaused = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private double accumulatedDistance = 0;
    private long lastUpdateTime = 0;
    private long runStartTime = 0;
    private long pausedElapsed = 0;
    private long pauseStartedAt = 0;
    private int currentLap = 0;
    private final List<RouteEntry> routeEntries = new ArrayList<>();
    private ArrayAdapter<String> routeAdapter;
    private int selectedRouteIndex = -1;
    private RouteFileAction pendingRouteAction = RouteFileAction.ADD;

    private final ActivityResultLauncher<String> browseFile = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    handlePickedKml(uri, pendingRouteAction);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fake_gps);

        initializeViews();
        setupRouteSelector();
        setupPaceControls();
        setupControlButtons();
        updateButtonStates();
    }

    // ===================== INITIALIZATION =====================

    private void initializeViews() {
        routeSelector = findViewById(R.id.route_selector);
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

    private void setupRouteSelector() {
        routeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new ArrayList<>());
        routeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        routeSelector.setAdapter(routeAdapter);
        routeSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedRouteIndex = position;
                loadRoute(position);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        findViewById(R.id.btn_browse).setOnClickListener(v -> openRoutePicker(RouteFileAction.ADD));
        btnUpdateRoute.setOnClickListener(v -> openRoutePicker(RouteFileAction.REPLACE));
        btnDeleteRoute.setOnClickListener(v -> confirmDeleteSelectedRoute());
        refreshRouteList(0);
    }

    private void setupPaceControls() {
        findViewById(R.id.btn_pace_up).setOnClickListener(v -> adjustPace(5));
        findViewById(R.id.btn_pace_down).setOnClickListener(v -> adjustPace(-5));
        findViewById(R.id.btn_laps_up).setOnClickListener(v -> adjustLaps(1));
        findViewById(R.id.btn_laps_down).setOnClickListener(v -> adjustLaps(-1));

        inputPace.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) updateTargetPaceDisplay();
        });
    }

    private void setupControlButtons() {
        btnStart.setOnClickListener(v -> startProcessing());
        btnPause.setOnClickListener(v -> pauseProcessing());
        btnResume.setOnClickListener(v -> resumeProcessing());
        btnStop.setOnClickListener(v -> stopProcessing());
    }

    // ===================== PACE & LAPS =====================

    private void adjustPace(int seconds) {
        try {
            double pace = Double.parseDouble(inputPace.getText().toString());
            double newPace = pace + seconds / 60.0;
            if (newPace >= 2.0) { // minimum pace ~2 min/km (8.3 m/s)
                inputPace.setText(String.format(Locale.US, "%.2f", newPace));
                updateTargetPaceDisplay();
            }
        } catch (NumberFormatException e) {
            inputPace.setText("5.55");
            updateTargetPaceDisplay();
        }
    }

    private void adjustLaps(int delta) {
        try {
            int laps = Integer.parseInt(inputRepetitions.getText().toString());
            laps = Math.max(1, laps + delta);
            inputRepetitions.setText(String.valueOf(laps));
            updateRemainingDisplay();
        } catch (NumberFormatException e) {
            inputRepetitions.setText("1");
        }
    }

    private void updateTargetPaceDisplay() {
        double pace = getPaceMinPerKm();
        int minutes = (int) pace;
        int seconds = (int) Math.round((pace - minutes) * 60);
        if (seconds == 60) { minutes++; seconds = 0; }
        targetPaceView.setText(String.format(Locale.US, "%d'%02d\"", minutes, seconds));
    }

    private String formatPace(double speedMs) {
        if (speedMs <= 0) return "--'--\"";
        double pace = 1000.0 / (speedMs * 60.0);
        int minutes = (int) pace;
        int seconds = (int) Math.round((pace - minutes) * 60);
        if (seconds == 60) { minutes++; seconds = 0; }
        return String.format(Locale.US, "%d'%02d\"", minutes, seconds);
    }

    private double getPaceMinPerKm() {
        try {
            return Double.parseDouble(inputPace.getText().toString());
        } catch (NumberFormatException e) {
            return 5.55;
        }
    }

    private int getRepetitions() {
        try {
            return Math.max(1, Integer.parseInt(inputRepetitions.getText().toString()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private double getSpeedFromPace() {
        double paceMinPerKm = getPaceMinPerKm();
        if (paceMinPerKm <= 0) return 3.0;
        return 1000.0 / (paceMinPerKm * 60.0);
    }

    private void updateRemainingDisplay() {
        int laps = getRepetitions();
        double total = totalDistance * laps;
        double done = accumulatedDistance;
        double remaining = Math.max(0, total - done);
        distanceRemainingView.setText(String.format(Locale.US, "%.2f km", remaining / 1000.0));
        distanceDoneView.setText(String.format(Locale.US, "%.2f km", done / 1000.0));
    }

    // ===================== KML PROCESSING =====================

    private void refreshRouteList(int requestedSelection) {
        routeEntries.clear();
        for (int i = 0; i < ROUTE_RAW_IDS.length; i++) {
            routeEntries.add(RouteEntry.builtIn(ROUTE_NAMES[i], ROUTE_RAW_IDS[i]));
        }

        try {
            File routeDirectory = getRouteDirectory();
            File[] savedFiles = routeDirectory.listFiles((directory, name) ->
                    name.toLowerCase(Locale.US).endsWith(".kml"));
            if (savedFiles != null) {
                Arrays.sort(savedFiles, Comparator.comparing(File::getName,
                        String.CASE_INSENSITIVE_ORDER));
                for (File savedFile : savedFiles) {
                    routeEntries.add(RouteEntry.saved(savedFile));
                }
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "无法创建线路存储目录", e);
            Toast.makeText(this, "无法创建线路存储目录", Toast.LENGTH_SHORT).show();
        }

        routeAdapter.clear();
        for (RouteEntry route : routeEntries) {
            routeAdapter.add(route.displayName);
        }
        routeAdapter.notifyDataSetChanged();

        if (routeEntries.isEmpty()) {
            selectedRouteIndex = -1;
            updateRouteActionButtons();
            return;
        }

        int selection = Math.max(0, Math.min(requestedSelection, routeEntries.size() - 1));
        selectedRouteIndex = selection;
        routeSelector.setSelection(selection, false);
        loadRoute(selection);
    }

    private File getRouteDirectory() throws IOException {
        File routeDirectory = new File(getFilesDir(), ROUTES_DIRECTORY);
        if (!routeDirectory.exists() && !routeDirectory.mkdirs()) {
            throw new IOException("无法创建线路存储目录");
        }
        return routeDirectory;
    }

    private void loadRoute(int index) {
        if (index < 0 || index >= routeEntries.size()) return;

        RouteEntry route = routeEntries.get(index);
        try (InputStream inputStream = route.builtIn
                ? getResources().openRawResource(route.rawResourceId)
                : new FileInputStream(route.file)) {
            List<GeoUtils.TrackPoint> loadedPoints = readKmlCoordinates(inputStream);
            trackPoints.clear();
            trackPoints.addAll(loadedPoints);
            calculateProgressList();
            resetRouteProgress();

            String source = route.builtIn ? "内置线路" : "已保存线路";
            kmlInfo.setText(String.format(Locale.US,
                    "%s\n%s\n路径点: %d | 总距离: %.2f km",
                    source, route.displayName, trackPoints.size(), totalDistance / 1000.0));
            updateRemainingDisplay();
            updateRouteActionButtons();
        } catch (Exception e) {
            trackPoints.clear();
            progressList.clear();
            totalDistance = 0;
            resetRouteProgress();
            kmlInfo.setText("KML route load failed. Choose a valid file.");
            updateButtonStates();
            Log.e(LOG_TAG, "加载 KML 线路失败", e);
            Toast.makeText(this, "加载 KML 线路失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 从 KML 中读取路线坐标。
     *
     * <p>优先定位 {@code <LineString>} 内部的 {@code <coordinates>}：从图新地球等工具导出的
     * KML 常在同一个文件里混装多个 {@code <Point>} 地标，那些地标每个只含一个坐标，
     * 若直接取「第一个 coordinates 节点」会解析出单个点并误判为无效路线。
     * 只有文件不含 LineString 时才回退到第一个 coordinates 节点。
     */
    private List<GeoUtils.TrackPoint> readKmlCoordinates(InputStream inputStream) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Android ships different XML parser implementations across API levels. These
        // protections are best-effort so a parser that does not expose one of them does
        // not make every built-in route fail to load.
        trySetXmlFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetXmlFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetXmlFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        try {
            factory.setXIncludeAware(false);
        } catch (UnsupportedOperationException | AbstractMethodError e) {
            Log.w(LOG_TAG, "当前 XML 解析器不支持关闭 XInclude", e);
        }
        try {
            factory.setExpandEntityReferences(false);
        } catch (UnsupportedOperationException | AbstractMethodError e) {
            Log.w(LOG_TAG, "当前 XML 解析器不支持关闭实体展开", e);
        }

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(inputStream);

        String coordinatesText = extractRouteCoordinatesText(document);
        if (coordinatesText == null || coordinatesText.trim().isEmpty()) {
            throw new IllegalArgumentException("未找到坐标数据");
        }

        List<GeoUtils.TrackPoint> loadedPoints = parseCoordinateText(coordinatesText);
        if (loadedPoints.size() < 2) {
            throw new IllegalArgumentException("线路至少需要两个坐标点");
        }
        return loadedPoints;
    }

    /** 取出路线的坐标串，优先使用 LineString 而非零散的 Point 地标。 */
    private String extractRouteCoordinatesText(Document document) {
        NodeList lineStrings = document.getElementsByTagName("LineString");
        if (lineStrings.getLength() > 0 && lineStrings.item(0) instanceof Element) {
            NodeList lineCoordinates =
                    ((Element) lineStrings.item(0)).getElementsByTagName("coordinates");
            if (lineCoordinates.getLength() > 0) {
                return lineCoordinates.item(0).getTextContent();
            }
        }
        NodeList coordinatesList = document.getElementsByTagName("coordinates");
        if (coordinatesList.getLength() == 0) {
            return null;
        }
        return coordinatesList.item(0).getTextContent();
    }

    private void trySetXmlFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception | AbstractMethodError e) {
            Log.w(LOG_TAG, "当前 XML 解析器不支持特性: " + feature, e);
        }
    }

    /**
     * 解析 KML 坐标串。KML 的每一组是 {@code 经度,纬度[,高程]}，第三列高程会被保留，
     * 供上报时还原真实海拔；缺省时记 0。
     */
    private List<GeoUtils.TrackPoint> parseCoordinateText(String coordinatesText) {
        if (coordinatesText == null || coordinatesText.trim().isEmpty()) {
            throw new IllegalArgumentException("坐标数据为空");
        }

        List<GeoUtils.TrackPoint> parsedPoints = new ArrayList<>();
        String[] points = coordinatesText.trim().split("\\s+");
        for (String point : points) {
            if (point.isEmpty()) {
                continue;
            }
            String[] parts = point.split(",");
            if (parts.length < 2) {
                continue;
            }
            double longitude;
            double latitude;
            try {
                longitude = Double.parseDouble(parts[0]);
                latitude = Double.parseDouble(parts[1]);
            } catch (NumberFormatException e) {
                // KML 里偶有空白或占位符残留，跳过即可，不必让整条线路失败
                Log.w(LOG_TAG, "跳过无法解析的坐标: " + point);
                continue;
            }
            double altitude = 0;
            if (parts.length >= 3) {
                try {
                    altitude = Double.parseDouble(parts[2]);
                } catch (NumberFormatException e) {
                    altitude = 0;
                }
            }
            if (Double.isNaN(latitude) || Double.isInfinite(latitude)
                    || Double.isNaN(longitude) || Double.isInfinite(longitude)
                    || latitude < -90 || latitude > 90
                    || longitude < -180 || longitude > 180) {
                throw new IllegalArgumentException("坐标范围无效");
            }
            parsedPoints.add(new GeoUtils.TrackPoint(latitude, longitude, altitude));
        }
        return parsedPoints;
    }

    private void openRoutePicker(RouteFileAction action) {
        if (isRunning) return;
        if (action == RouteFileAction.REPLACE && getSelectedSavedRoute() == null) {
            Toast.makeText(this, "请选择已保存线路后再替换", Toast.LENGTH_SHORT).show();
            return;
        }

        pendingRouteAction = action;
        browseFile.launch("*/*");
    }

    private void handlePickedKml(Uri uri, RouteFileAction action) {
        File temporaryFile = null;
        try {
            File routeDirectory = getRouteDirectory();
            temporaryFile = File.createTempFile("route_", ".kml", routeDirectory);
            copyUriToFile(uri, temporaryFile);

            try (InputStream inputStream = new FileInputStream(temporaryFile)) {
                readKmlCoordinates(inputStream);
            }

            if (action == RouteFileAction.ADD) {
                File target = createUniqueRouteFile(routeDirectory, getDisplayName(uri));
                copyFile(temporaryFile, target);
                refreshRouteList(0);
                selectRoute(findRouteIndex(target));
                Toast.makeText(this, "线路已保存", Toast.LENGTH_SHORT).show();
            } else {
                RouteEntry selectedRoute = getSelectedSavedRoute();
                if (selectedRoute == null) {
                    Toast.makeText(this, "请选择已保存线路后再替换", Toast.LENGTH_SHORT).show();
                    return;
                }
                copyFile(temporaryFile, selectedRoute.file);
                int selectedIndex = findRouteIndex(selectedRoute.file);
                refreshRouteList(selectedIndex);
                Toast.makeText(this, "线路已更新并保存", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "保存 KML 线路失败", e);
            Toast.makeText(this, "保存 KML 线路失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            if (temporaryFile != null && temporaryFile.exists()) {
                // The copied route is the durable file; the temporary validation copy can be removed.
                temporaryFile.delete();
            }
        }
    }

    private void copyUriToFile(Uri uri, File target) throws IOException {
        InputStream inputStream = getContentResolver().openInputStream(uri);
        if (inputStream == null) {
            throw new IOException("无法读取 KML 文件");
        }
        try (InputStream input = inputStream; OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }

    private void copyFile(File source, File target) throws IOException {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }

    private File createUniqueRouteFile(File routeDirectory, String displayName) {
        String fileName = sanitizeFileName(displayName);
        File candidate = new File(routeDirectory, fileName);
        if (!candidate.exists()) return candidate;

        String baseName = fileName.substring(0, fileName.length() - 4);
        int copyNumber = 2;
        do {
            candidate = new File(routeDirectory, baseName + " (" + copyNumber + ").kml");
            copyNumber++;
        } while (candidate.exists());
        return candidate;
    }

    private String sanitizeFileName(String displayName) {
        String name = displayName == null ? "route" : displayName.trim();
        if (name.isEmpty()) name = "route";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.toLowerCase(Locale.US).endsWith(".kml")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name.isEmpty()) name = "route";
        return name + ".kml";
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String displayName = cursor.getString(0);
                if (displayName != null && !displayName.trim().isEmpty()) {
                    return displayName;
                }
            }
        }
        String path = uri.getLastPathSegment();
        return path == null || path.trim().isEmpty() ? "route.kml" : path;
    }

    private int findRouteIndex(File file) {
        for (int i = 0; i < routeEntries.size(); i++) {
            RouteEntry route = routeEntries.get(i);
            if (!route.builtIn && route.file.equals(file)) return i;
        }
        return Math.max(0, routeEntries.size() - 1);
    }

    private void selectRoute(int index) {
        if (index < 0 || index >= routeEntries.size()) return;
        selectedRouteIndex = index;
        routeSelector.setSelection(index, false);
        loadRoute(index);
    }

    private RouteEntry getSelectedSavedRoute() {
        if (selectedRouteIndex < 0 || selectedRouteIndex >= routeEntries.size()) return null;
        RouteEntry route = routeEntries.get(selectedRouteIndex);
        return route.builtIn ? null : route;
    }

    private void confirmDeleteSelectedRoute() {
        RouteEntry selectedRoute = getSelectedSavedRoute();
        if (selectedRoute == null) {
            Toast.makeText(this, "内置线路不能删除，请选择已保存线路", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("删除线路")
                .setMessage("确定删除“" + selectedRoute.displayName + "”吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> deleteSelectedRoute(selectedRoute))
                .show();
    }

    private void deleteSelectedRoute(RouteEntry selectedRoute) {
        int oldIndex = selectedRouteIndex;
        if (!selectedRoute.file.delete()) {
            Toast.makeText(this, "删除线路失败", Toast.LENGTH_SHORT).show();
            return;
        }

        refreshRouteList(Math.min(oldIndex, routeEntries.size() - 2));
        Toast.makeText(this, "线路已删除", Toast.LENGTH_SHORT).show();
    }

    private void resetRouteProgress() {
        accumulatedDistance = 0;
        currentLap = 0;
        progressBar.setProgress(0);
        progressText.setText("0%");
        lapCounterView.setText("圈 0/1");
        distanceDoneView.setText("0.00 km");
        currentPaceView.setText("--'--\"");
        currentSpeedDisplay.setText("0.00 m/s");
        updateRemainingDisplay();
    }

    private void updateRouteActionButtons() {
        if (btnUpdateRoute == null || btnDeleteRoute == null) return;
        boolean canEdit = !isRunning && getSelectedSavedRoute() != null;
        btnUpdateRoute.setEnabled(canEdit);
        btnDeleteRoute.setEnabled(canEdit);
        btnUpdateRoute.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                canEdit ? 0xFF0F3460 : 0xFF555555));
        btnDeleteRoute.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                canEdit ? 0xFFCC2222 : 0xFF555555));
    }

    private void calculateProgressList() {
        // 距离与前缀和计算已迁移到 GeoUtils，便于主机侧单元测试
        progressList = GeoUtils.buildProgressList(trackPoints);
        totalDistance = progressList.isEmpty() ? 0 : progressList.get(progressList.size() - 1);
    }

    // ===================== CONTROL LOGIC =====================

    private void startProcessing() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先授予定位权限", Toast.LENGTH_SHORT).show();
            return;
        }
        if (trackPoints.size() < 2 || totalDistance <= 0) {
            Toast.makeText(this, "请先选择路线", Toast.LENGTH_SHORT).show();
            return;
        }

        double pace = getPaceMinPerKm();
        if (pace <= 0 || Double.isNaN(pace) || Double.isInfinite(pace)) {
            Toast.makeText(this, "请输入有效配速", Toast.LENGTH_SHORT).show();
            return;
        }
        inputRepetitions.setText(String.valueOf(getRepetitions()));

        updateTargetPaceDisplay();

        isRunning = true;
        isPaused = false;
        accumulatedDistance = 0;
        currentLap = 0;
        // 用 elapsedRealtime 而非墙钟：它单调递增且不受用户改时间/NTP 校时影响
        long now = SystemClock.elapsedRealtime();
        lastUpdateTime = now;
        runStartTime = now;
        pausedElapsed = 0;
        pauseStartedAt = 0;

        progressBar.setProgress(0);
        distanceDoneView.setText("0.00 km");
        elapsedTimeView.setText("00:00:00");
        currentPaceView.setText("--'--\"");
        currentSpeedDisplay.setText("0.00 m/s");
        updateRemainingDisplay();

        updateButtonStates();
        startLocationMockService();
        startLocationUpdates();
    }

    private void pauseProcessing() {
        if (!isRunning || isPaused) return;
        long now = SystemClock.elapsedRealtime();
        isPaused = true;
        pauseStartedAt = now;
        lastUpdateTime = now;
        updateButtonStates();
    }

    private void resumeProcessing() {
        if (!isRunning || !isPaused) return;
        long now = SystemClock.elapsedRealtime();
        if (pauseStartedAt > 0) {
            pausedElapsed += now - pauseStartedAt;
        }
        isPaused = false;
        pauseStartedAt = 0;
        lastUpdateTime = now;
        updateButtonStates();
        startLocationUpdates();
    }

    private void stopProcessing() {
        isRunning = false;
        isPaused = false;
        pauseStartedAt = 0;
        handler.removeCallbacksAndMessages(null);
        accumulatedDistance = 0;
        currentLap = 0;
        progressBar.setProgress(0);
        progressText.setText("0%");
        lapCounterView.setText("圈 0/1");
        currentPaceView.setText("--'--\"");
        currentSpeedDisplay.setText("0.00 m/s");
        elapsedTimeView.setText("00:00:00");
        distanceDoneView.setText("0.00 km");
        updateRemainingDisplay();
        updateButtonStates();
        stopLocationMockService();
    }

    private void finishProcessing() {
        isRunning = false;
        isPaused = false;
        pauseStartedAt = 0;
        handler.removeCallbacksAndMessages(null);
        progressBar.setProgress(100);
        progressText.setText("100%");
        updateButtonStates();
        stopLocationMockService();
    }

    private void updateButtonStates() {
        boolean hasCoordinates = !trackPoints.isEmpty();
        btnStart.setEnabled(hasCoordinates && !isRunning);
        btnPause.setEnabled(isRunning && !isPaused);
        btnResume.setEnabled(isRunning && isPaused);
        btnStop.setEnabled(isRunning);

        int startColor = (hasCoordinates && !isRunning) ? 0xFF00aa55 : 0xFF555555;
        int pauseColor = (isRunning && !isPaused) ? 0xFFff8800 : 0xFF555555;
        int resumeColor = (isRunning && isPaused) ? 0xFF0088cc : 0xFF555555;
        int stopColor = isRunning ? 0xFFcc2222 : 0xFF555555;

        android.content.res.ColorStateList startTint = android.content.res.ColorStateList.valueOf(startColor);
        android.content.res.ColorStateList pauseTint = android.content.res.ColorStateList.valueOf(pauseColor);
        android.content.res.ColorStateList resumeTint = android.content.res.ColorStateList.valueOf(resumeColor);
        android.content.res.ColorStateList stopTint = android.content.res.ColorStateList.valueOf(stopColor);

        btnStart.setBackgroundTintList(startTint);
        btnPause.setBackgroundTintList(pauseTint);
        btnResume.setBackgroundTintList(resumeTint);
        btnStop.setBackgroundTintList(stopTint);

        // Disable editing while running
        inputPace.setEnabled(!isRunning);
        inputRepetitions.setEnabled(!isRunning);
        inputInterval.setEnabled(!isRunning);
        routeSelector.setEnabled(!isRunning);
        findViewById(R.id.btn_browse).setEnabled(!isRunning);
        findViewById(R.id.btn_pace_up).setEnabled(!isRunning);
        findViewById(R.id.btn_pace_down).setEnabled(!isRunning);
        findViewById(R.id.btn_laps_up).setEnabled(!isRunning);
        findViewById(R.id.btn_laps_down).setEnabled(!isRunning);
        updateStatusBadge();
        updateRouteActionButtons();
    }

    // ===================== LOCATION UPDATES =====================

    private void startLocationUpdates() {
        if (!isRunning || isPaused) return;

        long currentTime = SystemClock.elapsedRealtime();
        double timeDiff = Math.max(0, (currentTime - lastUpdateTime) / 1000.0);
        lastUpdateTime = currentTime;

        // 息屏/切后台时回调会停摆，若不封顶，唤醒瞬间会一次性补进一大段距离（等价瞬移）
        if (timeDiff > MAX_TICK_SECONDS) {
            Log.w(LOG_TAG, "单次更新间隔 " + timeDiff + "s 超出上限，已钳制到 " + MAX_TICK_SECONDS + "s");
            timeDiff = MAX_TICK_SECONDS;
        }

        double speed = getSpeedFromPace();
        accumulatedDistance += timeDiff * speed;

        int repetitions = getRepetitions();
        double totalPathDistance = totalDistance * repetitions;

        // Check completion
        if (accumulatedDistance >= totalPathDistance) {
            accumulatedDistance = totalPathDistance;
            updateDisplayInfo(speed, repetitions, totalPathDistance);
            finishProcessing();
            Toast.makeText(this, "路线完成!", Toast.LENGTH_SHORT).show();
            return;
        }

        GeoUtils.TrackPosition position =
                GeoUtils.interpolate(trackPoints, progressList, accumulatedDistance);
        if (position == null) {
            Log.e(LOG_TAG, "路线插值失败，停止更新");
            finishProcessing();
            return;
        }

        // 水平精度模拟为 5~8 m 的抖动：恒定 1 m 的“完美精度”在真机上并不存在
        float accuracy = MIN_ACCURACY_METERS
                + (float) Math.random() * (MAX_ACCURACY_METERS - MIN_ACCURACY_METERS);

        Intent intent = new Intent(ACTION_MOCK_LOCATION)
                .setPackage(getPackageName());
        intent.putExtra("lat", String.valueOf(position.latitude));
        intent.putExtra("lng", String.valueOf(position.longitude));
        intent.putExtra("alt", String.valueOf(position.altitude));
        intent.putExtra("bea", String.valueOf(position.bearing));
        intent.putExtra("speed", String.valueOf(speed));
        intent.putExtra("acc", String.valueOf(accuracy));
        sendBroadcast(intent);

        updateDisplayInfo(speed, repetitions, totalPathDistance);

        double interval;
        try {
            interval = Double.parseDouble(inputInterval.getText().toString());
        } catch (NumberFormatException e) {
            interval = 0.3;
        }
        interval = Math.max(0.1, Math.min(5.0, interval));
        handler.postDelayed(this::startLocationUpdates, (long) (interval * 1000));
    }

    private void updateDisplayInfo(double speed, int repetitions, double totalPathDistance) {
        if (totalPathDistance <= 0 || totalDistance <= 0) return;
        int progress = (int) ((accumulatedDistance / totalPathDistance) * 100);
        progressBar.setProgress(progress);
        progressText.setText(progress + "%");

        // Lap counter
        int lap = (int) (accumulatedDistance / totalDistance) + 1;
        if (lap > repetitions) lap = repetitions;
        if (lap != currentLap) currentLap = lap;
        lapCounterView.setText(String.format(Locale.US, "圈 %d/%d", Math.min(lap, repetitions), repetitions));

        // Current pace (from actual recent speed)
        currentPaceView.setText(formatPace(speed));
        currentSpeedDisplay.setText(String.format(Locale.US, "%.2f m/s", speed));

        // Elapsed time（同样基于单调时钟，避免系统改时间导致计时跳变）
        long now = SystemClock.elapsedRealtime();
        long totalPaused = pausedElapsed;
        if (isPaused && pauseStartedAt > 0) {
            totalPaused += now - pauseStartedAt;
        }
        long elapsed = Math.max(0, now - runStartTime - totalPaused);
        long hours = elapsed / 3600000;
        long minutes = (elapsed % 3600000) / 60000;
        long seconds = (elapsed % 60000) / 1000;
        elapsedTimeView.setText(String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds));

        // Distance
        distanceDoneView.setText(String.format(Locale.US, "%.2f km", accumulatedDistance / 1000.0));
        updateRemainingDisplay();
    }

    // ===================== LIFECYCLE =====================

    @Override
    protected void onDestroy() {
        // 只在真正退出时清理：转屏等配置变更也会走 onDestroy，但此时应让模拟继续运行
        if (isFinishing()) {
            handler.removeCallbacksAndMessages(null);
            stopLocationMockService();
        }
        super.onDestroy();
    }

    private void updateStatusBadge() {
        if (statusBadgeView == null) return;
        if (isRunning && !isPaused) {
            statusBadgeView.setText("RUNNING");
            statusBadgeView.setTextColor(ContextCompat.getColor(this, R.color.status_green));
        } else if (isRunning) {
            statusBadgeView.setText("PAUSED");
            statusBadgeView.setTextColor(ContextCompat.getColor(this, R.color.target_yellow));
        } else {
            statusBadgeView.setText("READY");
            statusBadgeView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    // ===================== SERVICE =====================

    private void startLocationMockService() {
        Intent serviceIntent = new Intent(this, LocationMockService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopLocationMockService() {
        Intent serviceIntent = new Intent(this, LocationMockService.class);
        stopService(serviceIntent);
    }

    // ===================== DATA CLASSES =====================

    private enum RouteFileAction {
        ADD,
        REPLACE
    }

    private static class RouteEntry {
        String displayName;
        boolean builtIn;
        int rawResourceId;
        File file;

        static RouteEntry builtIn(String displayName, int rawResourceId) {
            RouteEntry route = new RouteEntry();
            route.displayName = displayName;
            route.builtIn = true;
            route.rawResourceId = rawResourceId;
            return route;
        }

        static RouteEntry saved(File file) {
            RouteEntry route = new RouteEntry();
            route.displayName = "已保存: " + removeKmlExtension(file.getName());
            route.builtIn = false;
            route.file = file;
            return route;
        }

        private static String removeKmlExtension(String name) {
            return name.toLowerCase(Locale.US).endsWith(".kml")
                    ? name.substring(0, name.length() - 4) : name;
        }
    }
}
