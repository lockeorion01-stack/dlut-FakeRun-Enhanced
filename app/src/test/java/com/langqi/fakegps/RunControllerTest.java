package com.langqi.fakegps;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class RunControllerTest {
    private static final class Scheduler implements RunController.Clock, RunController.Scheduler {
        long now;
        final Map<Runnable, Long> pending = new LinkedHashMap<>();
        @Override public long now() { return now; }
        @Override public void post(Runnable task, long delayMs) {
            assertFalse("A second update must never be scheduled", pending.containsKey(task));
            pending.put(task, now + delayMs);
        }
        @Override public void cancel(Runnable task) { pending.remove(task); }
        void tickAfter(long milliseconds) {
            now += milliseconds;
            assertEquals(1, pending.size());
            Runnable task = pending.keySet().iterator().next();
            pending.remove(task);
            task.run();
        }
    }

    private static final class Sink implements RunController.Sink {
        boolean failOpen;
        boolean failWrite;
        int writes;
        int closes;
        GeoUtils.TrackPosition last;
        double lastSpeed;
        @Override public void open() {
            if (failOpen) throw new SecurityException("Mock location not selected");
        }
        @Override public void write(GeoUtils.TrackPosition position, double speed) throws IOException {
            if (failWrite) throw new IOException("Provider write failed");
            last = position;
            lastSpeed = speed;
            writes++;
        }
        @Override public void close() { closes++; }
    }

    private final Scheduler scheduler = new Scheduler();
    private final Sink sink = new Sink();
    private final ArrayList<RunController.Snapshot> snapshots = new ArrayList<>();
    private final RunController controller = new RunController(scheduler, scheduler, sink, snapshots::add);

    private RunController.Config config(long interval) {
        return new RunController.Config("route", "Route", Arrays.asList(
                new GeoUtils.TrackPoint(22, 108, 10), new GeoUtils.TrackPoint(22.01, 108, 20)),
                300, 1, interval);
    }

    private void start(long interval) { assertTrue(controller.prepare(config(interval))); controller.start(); }

    @Test public void fiveSecondIntervalCountsFiveSecondsOfDistance() {
        start(5000);
        scheduler.tickAfter(5000);
        assertEquals(5000, controller.snapshot().elapsedMs);
        assertEquals(1000.0 / 300 * 5, controller.snapshot().distance, 1e-8);
    }

    @Test public void everySupportedIntervalPreservesSpeed() {
        for (long interval : new long[]{100, 300, 1000, 2000, 3000, 5000}) {
            start(interval);
            scheduler.tickAfter(interval);
            assertEquals(interval, controller.snapshot().elapsedMs);
            assertEquals(interval / 300.0, controller.snapshot().distance, 1e-8);
            controller.stop();
        }
    }

    @Test public void longStallDoesNotTeleportOrInflateElapsedTime() {
        start(5000);
        scheduler.tickAfter(60000);
        assertEquals(5000, controller.snapshot().elapsedMs);
        assertEquals(1000.0 / 300 * 5, controller.snapshot().distance, 1e-8);
    }

    @Test public void rapidPauseResumeKeepsExactlyOneTaskAndExcludesPausedTime() {
        start(5000);
        for (int i = 0; i < 20; i++) {
            scheduler.now += 100;
            controller.pause();
            assertTrue(scheduler.pending.isEmpty());
            assertEquals(0, sink.lastSpeed, 0);
            scheduler.now += 10000;
            controller.resume();
            controller.resume();
            assertEquals(1, scheduler.pending.size());
        }
        assertEquals(2000, controller.snapshot().elapsedMs);
        scheduler.tickAfter(5000);
        assertEquals(7000, controller.snapshot().elapsedMs);
        assertEquals(1000.0 / 300 * 7, controller.snapshot().distance, 1e-8);
    }

    @Test public void rebindingAnObserverCannotResetOrDuplicateTheRun() {
        start(300);
        scheduler.tickAfter(300);
        RunController.Snapshot reopenedScreen = controller.snapshot();
        assertEquals(RunController.State.RUNNING, reopenedScreen.state);
        assertEquals(1, reopenedScreen.distance, 1e-8);
        assertFalse(controller.prepare(config(5000)));
        controller.start();
        assertEquals(1, scheduler.pending.size());
        scheduler.tickAfter(300);
        assertEquals(2, controller.snapshot().distance, 1e-8);
    }

    @Test public void missingMockAuthorizationProducesErrorWithoutProgress() {
        sink.failOpen = true;
        start(300);
        assertEquals(RunController.State.ERROR, controller.snapshot().state);
        assertTrue(controller.snapshot().error.contains("模拟位置应用"));
        assertEquals(0, sink.writes);
        assertEquals(0, controller.snapshot().distance, 0);
        assertTrue(scheduler.pending.isEmpty());
        assertEquals(1, sink.closes);
    }

    @Test public void writeFailureFreezesLastSuccessfulProgress() {
        start(300);
        scheduler.tickAfter(300);
        sink.failWrite = true;
        scheduler.tickAfter(300);
        assertEquals(RunController.State.ERROR, controller.snapshot().state);
        assertEquals(1, controller.snapshot().distance, 1e-8);
        assertEquals(300, controller.snapshot().elapsedMs);
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test public void completionPublishesActualEndpointBeforeClosing() {
        RunController.Config shortRoute = new RunController.Config("short", "Short", Arrays.asList(
                new GeoUtils.TrackPoint(22, 108, 10), new GeoUtils.TrackPoint(22.00001, 108, 20)),
                300, 1, 1000);
        controller.prepare(shortRoute);
        controller.start();
        scheduler.tickAfter(1000);
        assertEquals(RunController.State.COMPLETED, controller.snapshot().state);
        assertEquals(shortRoute.totalDistance, controller.snapshot().distance, 0);
        assertEquals(22.00001, sink.last.latitude, 1e-12);
        assertEquals(20, sink.last.altitude, 0);
        assertEquals(0, sink.lastSpeed, 0);
        assertTrue(scheduler.pending.isEmpty());
        assertEquals(1, sink.closes);
    }

    @Test public void failureAtEndpointNeverReportsCompletion() {
        RunController.Config shortRoute = new RunController.Config("short", "Short", Arrays.asList(
                new GeoUtils.TrackPoint(22, 108, 10), new GeoUtils.TrackPoint(22.00001, 108, 20)),
                300, 1, 1000);
        controller.prepare(shortRoute);
        controller.start();
        sink.failWrite = true;
        scheduler.tickAfter(1000);
        assertEquals(RunController.State.ERROR, controller.snapshot().state);
        assertEquals(0, controller.snapshot().distance, 0);
        assertFalse(snapshots.stream().anyMatch(snapshot -> snapshot.state == RunController.State.COMPLETED));
    }

    @Test public void stopPreservesSummaryAndCancelsFurtherUpdates() {
        start(300);
        scheduler.tickAfter(300);
        controller.stop();
        controller.stop();
        controller.resume();
        assertEquals(RunController.State.STOPPED, controller.snapshot().state);
        assertEquals(1, controller.snapshot().distance, 1e-8);
        assertTrue(scheduler.pending.isEmpty());
        assertEquals(1, sink.closes);
    }

    @Test public void invalidAltitudeIsRejectedBeforeServiceStartup() {
        assertThrows(IllegalArgumentException.class, () -> new RunController.Config("bad", "Bad",
                Arrays.asList(new GeoUtils.TrackPoint(22, 108, Double.NaN),
                        new GeoUtils.TrackPoint(22.01, 108, 0)), 300, 1, 300));
    }

    @Test public void runningRouteIsIndependentOfMutableUiList() {
        ArrayList<GeoUtils.TrackPoint> points = new ArrayList<>(config(300).points);
        RunController.Config immutable = new RunController.Config("route", "Route", points, 300, 1, 300);
        points.clear();
        controller.prepare(immutable);
        controller.start();
        scheduler.tickAfter(300);
        assertEquals(RunController.State.RUNNING, controller.snapshot().state);
    }
}
