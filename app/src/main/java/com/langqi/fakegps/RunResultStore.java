package com.langqi.fakegps;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/** Stores only the latest terminal summary, independently of Service and route lifetimes. */
final class RunResultStore {
    static final String PREFERENCES = "run_results";
    private static final String LAST_RESULT = "last_result";
    private final SharedPreferences preferences;

    static final class Result {
        final RunController.State state;
        final String routeName;
        final double distance;
        final double totalDistance;
        final long elapsedMs;
        final String error;

        Result(JSONObject json) throws JSONException {
            state = RunController.State.valueOf(json.getString("state"));
            routeName = json.getString("routeName");
            distance = json.getDouble("distance");
            totalDistance = json.getDouble("totalDistance");
            elapsedMs = json.getLong("elapsedMs");
            error = json.isNull("error") ? null : json.getString("error");
            if (!terminal(state) || !Double.isFinite(distance) || !Double.isFinite(totalDistance)
                    || totalDistance <= 0 || distance < 0 || distance > totalDistance || elapsedMs < 0) {
                throw new IllegalArgumentException("Invalid saved run summary");
            }
        }
    }

    RunResultStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    void save(RunController.Snapshot snapshot) {
        if (snapshot.config == null || !terminal(snapshot.state)) return;
        try {
            JSONObject json = new JSONObject()
                    .put("state", snapshot.state.name())
                    .put("routeName", snapshot.config.routeName)
                    .put("distance", snapshot.distance)
                    .put("totalDistance", snapshot.config.totalDistance)
                    .put("elapsedMs", snapshot.elapsedMs)
                    .put("error", snapshot.error == null ? JSONObject.NULL : snapshot.error);
            // One preference value prevents readers seeing a partially replaced summary.
            // apply() updates memory immediately and lets Android finish disk I/O off the UI thread.
            preferences.edit().putString(LAST_RESULT, json.toString()).apply();
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid run summary", e);
        }
    }

    Result read() {
        try {
            String saved = preferences.getString(LAST_RESULT, null);
            return saved == null ? null : new Result(new JSONObject(saved));
        } catch (JSONException | IllegalArgumentException | ClassCastException ignored) {
            // An incompatible or damaged record must never prevent a new run.
            return null;
        }
    }

    private static boolean terminal(RunController.State state) {
        return state == RunController.State.COMPLETED || state == RunController.State.STOPPED
                || state == RunController.State.ERROR;
    }
}
