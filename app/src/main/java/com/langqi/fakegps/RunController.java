package com.langqi.fakegps;

import java.util.List;

/** One service-owned run. All calls, including scheduler callbacks, use the same thread. */
final class RunController {
    enum State { READY, STARTING, RUNNING, PAUSED, COMPLETED, STOPPED, ERROR }

    interface Clock { long now(); }
    interface Scheduler {
        void post(Runnable task, long delayMs);
        void cancel(Runnable task);
    }
    interface Sink {
        void open() throws Exception;
        void write(GeoUtils.TrackPosition position, double speed) throws Exception;
        void close();
    }
    interface Listener { void changed(Snapshot snapshot); }

    static final class Config {
        final String routeKey;
        final String routeName;
        final List<GeoUtils.TrackPoint> points;
        final List<Double> progress;
        final int paceSeconds;
        final int laps;
        final long intervalMs;
        final double lapDistance;
        final double totalDistance;
        final double speed;

        Config(String routeKey, String routeName, List<GeoUtils.TrackPoint> points,
               int paceSeconds, int laps, long intervalMs) {
            if (points == null || points.size() < 2 || paceSeconds <= 0 || laps <= 0
                    || intervalMs < 100 || intervalMs > 5000) {
                throw new IllegalArgumentException("路线或运行参数无效");
            }
            for (GeoUtils.TrackPoint point : points) {
                if (point == null || !Double.isFinite(point.latitude)
                        || !Double.isFinite(point.longitude) || !Double.isFinite(point.altitude)
                        || Math.abs(point.latitude) > 90 || Math.abs(point.longitude) > 180) {
                    throw new IllegalArgumentException("路线包含无效坐标或高程");
                }
            }
            this.points = GeoUtils.immutableCopy(points);
            this.progress = GeoUtils.immutableCopy(GeoUtils.buildProgressList(this.points));
            lapDistance = progress.get(progress.size() - 1);
            totalDistance = lapDistance * laps;
            if (!Double.isFinite(totalDistance) || lapDistance <= 0) {
                throw new IllegalArgumentException("路线长度必须大于零");
            }
            this.routeKey = routeKey;
            this.routeName = routeName;
            this.paceSeconds = paceSeconds;
            this.laps = laps;
            this.intervalMs = intervalMs;
            speed = 1000.0 / paceSeconds;
        }

        GeoUtils.TrackPosition position(double distance) {
            // A non-closed route must finish at its endpoint, not wrap to its start.
            if (distance >= totalDistance) {
                GeoUtils.TrackPoint last = points.get(points.size() - 1);
                int previous = points.size() - 2;
                while (previous > 0 && progress.get(previous) >= lapDistance) previous--;
                return new GeoUtils.TrackPosition(last.latitude, last.longitude, last.altitude,
                        (float) GeoUtils.calculateBearing(points.get(previous), last));
            }
            GeoUtils.TrackPosition position = GeoUtils.interpolate(points, progress, distance);
            if (position == null) throw new IllegalArgumentException("路线插值失败");
            return position;
        }
    }

    static final class Snapshot {
        final State state;
        final Config config;
        final double distance;
        final long elapsedMs;
        final String error;

        Snapshot(State state, Config config, double distance, long elapsedMs, String error) {
            this.state = state;
            this.config = config;
            this.distance = distance;
            this.elapsedMs = elapsedMs;
            this.error = error;
        }

        boolean active() {
            return state == State.STARTING || state == State.RUNNING || state == State.PAUSED;
        }
    }

    private final Clock clock;
    private final Scheduler scheduler;
    private final Sink sink;
    private final Listener listener;
    private final Runnable tick = this::tick;
    private State state = State.READY;
    private Config config;
    private double distance;
    private long elapsedMs;
    private long lastTime;
    private String error;

    RunController(Clock clock, Scheduler scheduler, Sink sink, Listener listener) {
        this.clock = clock;
        this.scheduler = scheduler;
        this.sink = sink;
        this.listener = listener;
    }

    Snapshot snapshot() { return new Snapshot(state, config, distance, elapsedMs, error); }

    boolean prepare(Config next) {
        if (snapshot().active()) return false;
        scheduler.cancel(tick);
        config = next;
        distance = 0;
        elapsedMs = 0;
        error = null;
        state = State.STARTING;
        publish();
        return true;
    }

    void start() {
        if (state != State.STARTING) return;
        try {
            sink.open();
            sink.write(config.position(0), config.speed);
            lastTime = clock.now();
            state = State.RUNNING;
            publish();
            schedule();
        } catch (Exception e) {
            fail(e);
        }
    }

    void pause() {
        if (state != State.RUNNING) return;
        scheduler.cancel(tick);
        advance();
        if (state != State.RUNNING) return;
        try {
            sink.write(config.position(distance), 0);
            state = State.PAUSED;
            publish();
        } catch (Exception e) {
            fail(e);
        }
    }

    void resume() {
        if (state != State.PAUSED) return;
        try {
            sink.write(config.position(distance), config.speed);
            lastTime = clock.now();
            state = State.RUNNING;
            publish();
            schedule();
        } catch (Exception e) {
            fail(e);
        }
    }

    void stop() {
        if (snapshot().active()) end(State.STOPPED, null);
    }

    void fail(Exception exception) {
        String message = exception instanceof SecurityException
                ? "请授予定位权限，并在开发者选项中将 FakeGPS 选为模拟位置应用"
                : exception.getMessage();
        end(State.ERROR, message == null ? "定位服务运行失败" : message);
    }

    private void tick() {
        if (state != State.RUNNING) return;
        advance();
        if (state == State.RUNNING) schedule();
    }

    private void schedule() {
        // Cancel before every post: rapid pause/resume can never leave a second loop.
        scheduler.cancel(tick);
        scheduler.post(tick, config.intervalMs);
    }

    private void advance() {
        long now = clock.now();
        long delta = Math.max(0, now - lastTime);
        lastTime = now;
        // Accept every supported normal interval. Treat a much longer stall as one tick,
        // excluding the same stalled time from both distance and elapsed time.
        if (delta > Math.max(2000, config.intervalMs * 2)) delta = config.intervalMs;
        double nextDistance = Math.min(config.totalDistance, distance + config.speed * delta / 1000.0);
        try {
            boolean finished = nextDistance >= config.totalDistance;
            sink.write(config.position(nextDistance), finished ? 0 : config.speed);
            long accepted = finished
                    ? Math.min(delta, Math.round((nextDistance - distance) / config.speed * 1000)) : delta;
            distance = nextDistance;
            elapsedMs += accepted;
            if (finished) end(State.COMPLETED, null);
            else publish();
        } catch (Exception e) {
            // Do not claim distance or completion when the position was not accepted.
            fail(e);
        }
    }

    private void end(State next, String message) {
        scheduler.cancel(tick);
        state = next;
        error = message;
        sink.close();
        publish();
    }

    private void publish() { listener.changed(snapshot()); }
}
