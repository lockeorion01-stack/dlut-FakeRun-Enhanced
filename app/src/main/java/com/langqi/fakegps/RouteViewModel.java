package com.langqi.fakegps;

import android.app.Application;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps route I/O and its result alive across Activity recreation, without retaining a View. */
public class RouteViewModel extends AndroidViewModel {
    RunController.Snapshot session;
    private Uri pendingImport;
    private String pendingImportReplacement;
    static final class Entry {
        final String key;
        final String name;
        final int resource;
        final File file;

        Entry(String name, int resource) {
            this.key = "raw:" + resource;
            this.name = name;
            this.resource = resource;
            this.file = null;
        }

        Entry(File file) {
            this.key = "file:" + file.getName();
            this.name = "已保存: " + file.getName().replaceFirst("(?i)\\.kml$", "");
            this.resource = 0;
            this.file = file;
        }
    }

    static final class Routes {
        final List<Entry> entries;
        final Entry selected;
        final List<GeoUtils.TrackPoint> points;
        final double distance;
        final boolean busy;
        final String error;

        Routes(List<Entry> entries, Entry selected, List<GeoUtils.TrackPoint> points,
               boolean busy, String error) {
            this.entries = GeoUtils.immutableCopy(entries);
            this.selected = selected;
            this.points = GeoUtils.immutableCopy(points);
            this.busy = busy;
            this.error = error;
            List<Double> progress = GeoUtils.buildProgressList(points);
            distance = progress.isEmpty() ? 0 : progress.get(progress.size() - 1);
        }

        Routes(Routes previous, boolean busy, String error) {
            entries = previous.entries;
            selected = previous.selected;
            points = previous.points;
            distance = previous.distance;
            this.busy = busy;
            this.error = error;
        }
    }

    private final MutableLiveData<Routes> routes = new MutableLiveData<>(
            new Routes(Collections.emptyList(), null, Collections.emptyList(), false, null));
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean cleared;
    private int generation;

    public RouteViewModel(Application application) {
        super(application);
        refresh(application.getSharedPreferences("run_settings", 0).getString("route", null));
    }

    LiveData<Routes> routes() { return routes; }

    void select(Entry entry) {
        Routes current = routes.getValue();
        if (current.busy || (current.selected != null && current.selected.key.equals(entry.key))) return;
        load(current.entries, entry);
    }

    void refresh(String preferredKey) {
        int request = begin();
        worker.execute(() -> {
            try {
                List<Entry> entries = entries();
                Entry selected = entries.get(0);
                for (Entry entry : entries) if (entry.key.equals(preferredKey)) selected = entry;
                post(request, readOrError(entries, selected));
            } catch (Exception e) {
                error(request, e);
            }
        });
    }

    private void load(List<Entry> entries, Entry entry) {
        int request = begin();
        worker.execute(() -> {
            try {
                post(request, read(entries, entry));
            } catch (Exception e) {
                post(request, new Routes(entries, entry, Collections.emptyList(), false, message(e)));
            }
        });
    }

    void importRoute(Uri uri, String replacementKey) {
        if (routes.getValue().busy) {
            pendingImport = uri;
            pendingImportReplacement = replacementKey;
            return;
        }
        // Capture the actual replacement before the file picker or background work changes selection.
        File replacement = null;
        if (replacementKey != null) {
            for (Entry entry : routes.getValue().entries) {
                if (entry.key.equals(replacementKey)) replacement = entry.file;
            }
            if (replacement == null) {
                error(begin(), new IllegalArgumentException("待替换的路线已不存在"));
                return;
            }
        }
        File destination = replacement;
        int request = begin();
        worker.execute(() -> {
            try (InputStream input = getApplication().getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalArgumentException("无法读取 KML 文件");
                File saved = repository().save(input, displayName(uri), destination);
                List<Entry> entries = entries();
                Entry selected = new Entry(saved);
                post(request, read(entries, selected));
            } catch (Exception e) {
                error(request, e);
            }
        });
    }

    void delete(Entry entry) {
        if (routes.getValue().busy || entry.file == null) return;
        int request = begin();
        worker.execute(() -> {
            try {
                repository().delete(entry.file);
                List<Entry> entries = entries();
                post(request, read(entries, entries.get(0)));
            } catch (Exception e) {
                error(request, e);
            }
        });
    }

    private int begin() {
        Routes current = routes.getValue();
        routes.setValue(new Routes(current, true, null));
        return ++generation;
    }

    private Routes read(List<Entry> entries, Entry entry) throws Exception {
        try (InputStream input = entry.file == null
                ? getApplication().getResources().openRawResource(entry.resource)
                : new FileInputStream(entry.file)) {
            return new Routes(entries, entry, KmlParser.read(input), false, null);
        }
    }

    private Routes readOrError(List<Entry> entries, Entry entry) {
        try {
            return read(entries, entry);
        } catch (Exception e) {
            return new Routes(entries, entry, Collections.emptyList(), false, message(e));
        }
    }

    private void post(int request, Routes result) {
        main.post(() -> {
            if (cleared || request != generation) return;
            if (session != null && !session.active() && session.config != null
                    && result.selected != null && !session.config.routeKey.equals(result.selected.key)) {
                session = null;
            }
            routes.setValue(result);
            if (result.selected != null && !result.points.isEmpty()) {
                getApplication().getSharedPreferences("run_settings", 0).edit()
                        .putString("route", result.selected.key).apply();
            }
            processPendingImport();
        });
    }

    private void error(int request, Exception exception) {
        main.post(() -> {
            if (cleared || request != generation) return;
            Routes current = routes.getValue();
            routes.setValue(new Routes(current, false, message(exception)));
            processPendingImport();
        });
    }

    private void processPendingImport() {
        if (pendingImport == null) return;
        Uri uri = pendingImport;
        String replacement = pendingImportReplacement;
        pendingImport = null;
        pendingImportReplacement = null;
        importRoute(uri, replacement);
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? "路线操作失败" : exception.getMessage();
    }

    private RouteRepository repository() throws Exception {
        return new RouteRepository(new File(getApplication().getFilesDir(), "routes"));
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getApplication().getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        }
        return "route.kml";
    }

    private List<Entry> entries() throws Exception {
        List<Entry> result = new ArrayList<>();
        int[] resources = {R.raw.asean_route_1, R.raw.asean_route_2, R.raw.asean_route_3,
                R.raw.asean_route_4, R.raw.asean_route_5, R.raw.uni_route_1, R.raw.uni_route_2,
                R.raw.uni_route_3, R.raw.uni_route_4, R.raw.uni_route_5, R.raw.uni_route_6, R.raw.three_km};
        String[] names = {"东盟 1", "东盟 2", "东盟 3", "东盟 4", "东盟 5", "大学路 1",
                "大学路 2", "大学路 3", "大学路 4", "大学路 5", "大学路 6", "默认路径"};
        for (int i = 0; i < resources.length; i++) result.add(new Entry(names[i], resources[i]));
        for (File file : repository().files()) result.add(new Entry(file));
        return result;
    }

    @Override protected void onCleared() {
        cleared = true;
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
