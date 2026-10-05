package com.langqi.fakegps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link GeoUtils} 的单元测试。
 *
 * <p>覆盖三类行为：
 * <ol>
 *   <li>Vincenty 距离的数值正确性（对照已知基线）；</li>
 *   <li>累计里程前缀和的单调性；</li>
 *   <li>按位移插值时的坐标、高程、航向，以及套圈取模。</li>
 * </ol>
 */
public class GeoUtilsTest {

    @Test public void nearAntipodalDistanceUsesNonzeroFallback() {
        GeoUtils.TrackPoint first = new GeoUtils.TrackPoint(0, 0, 0);
        for (double longitude : new double[]{179.9999, 180, -179.9999}) {
            GeoUtils.TrackPoint second = new GeoUtils.TrackPoint(0, longitude, 0);
            double distance = GeoUtils.calculateDistance(first, second);
            // WGS-84 antipodal distance is about 20,004 km; spherical fallback is approximate.
            assertEquals(20003931, distance, 30000);
            assertEquals(distance, GeoUtils.calculateDistance(second, first), 0.001);
        }
    }

    @Test public void datelineCrossingTakesShortPathInBothDirections() {
        for (int direction : new int[]{1, -1}) {
            List<GeoUtils.TrackPoint> route = Arrays.asList(
                    new GeoUtils.TrackPoint(0, direction * 179.999, 10),
                    new GeoUtils.TrackPoint(0, -direction * 179.999, 30));
            List<Double> progress = GeoUtils.buildProgressList(route);
            assertEquals(222.639, progress.get(1), 0.01);
            GeoUtils.TrackPosition middle = GeoUtils.interpolate(route, progress, progress.get(1) / 2);
            assertNotNull(middle);
            assertEquals(180, Math.abs(middle.longitude), 1e-9);
            assertEquals(20, middle.altitude, 1e-9);
            assertEquals(direction == 1 ? 90 : 270, middle.bearing, 0.01);
        }
    }

    @Test public void duplicatePlateausPreserveStartAndExactVertex() {
        GeoUtils.TrackPoint a = point(0, 0, 0);
        GeoUtils.TrackPoint b = point(0, 100, 10);
        GeoUtils.TrackPoint c = point(100, 100, 20);
        List<GeoUtils.TrackPoint> route = Arrays.asList(a, a, a, b, b, b, c, c);
        List<Double> progress = GeoUtils.buildProgressList(route);
        assertEquals(a.longitude, GeoUtils.interpolate(route, progress, 0).longitude, 1e-9);
        GeoUtils.TrackPosition vertex = GeoUtils.interpolate(route, progress, progress.get(3));
        assertEquals(b.latitude, vertex.latitude, 1e-9);
        assertEquals(b.longitude, vertex.longitude, 1e-9);
        GeoUtils.TrackPosition after = GeoUtils.interpolate(route, progress, progress.get(3) + 1);
        assertTrue(after.latitude > b.latitude);
        assertEquals(0, after.bearing, 0.1);
    }

    @Test public void largeRouteLookupDoesNotScanAllPrecedingPoints() {
        final int size = 50000;
        final int[] reads = {0};
        List<Double> progress = new AbstractList<Double>() {
            @Override public Double get(int index) { reads[0]++; return (double) index; }
            @Override public int size() { return size; }
        };
        List<GeoUtils.TrackPoint> points = new AbstractList<GeoUtils.TrackPoint>() {
            @Override public GeoUtils.TrackPoint get(int index) {
                return new GeoUtils.TrackPoint(index * 0.00001, 108, 0);
            }
            @Override public int size() { return size; }
        };
        GeoUtils.TrackPosition nearEnd = GeoUtils.interpolate(points, progress, size - 1.5);
        assertEquals((size - 1.5) * 0.00001, nearEnd.latitude, 1e-10);
        assertTrue("Route lookup must stay logarithmic", reads[0] <= 24);
    }

    @Test public void nonFiniteDisplacementIsRejected() {
        List<GeoUtils.TrackPoint> route = squareLoop();
        List<Double> progress = GeoUtils.buildProgressList(route);
        assertNull(GeoUtils.interpolate(route, progress, Double.NaN));
        assertNull(GeoUtils.interpolate(route, progress, Double.POSITIVE_INFINITY));
        assertNull(GeoUtils.interpolate(route, progress, Double.NEGATIVE_INFINITY));
    }

    /** 一个边长约 100 m 的正方形闭环，便于手算验证。 */
    private static final double BASE_LAT = 22.7090;
    private static final double BASE_LON = 110.2346;
    /** WGS-84 平均地球半径，用于测试里的米->度换算。 */
    private static final double EARTH_RADIUS_METERS = 6371008.8;

    private static GeoUtils.TrackPoint point(double dLatMeters, double dLonMeters, double altitude) {
        // 用 Math.toDegrees 做米->度换算：直接除以 111320.0 会引入舍入误差，
        // 使「位移 0」的点与基准点相差几个 ULP
        double latitude = BASE_LAT + Math.toDegrees(dLatMeters / EARTH_RADIUS_METERS);
        double longitude = BASE_LON + Math.toDegrees(dLonMeters
                / (EARTH_RADIUS_METERS * Math.cos(Math.toRadians(BASE_LAT))));
        return new GeoUtils.TrackPoint(latitude, longitude, altitude);
    }

    /** 边长约 100 m 的正方形闭环：先向正东，再向正北，再向正西，最后回到起点。 */
    private static List<GeoUtils.TrackPoint> squareLoop() {
        return new ArrayList<>(Arrays.asList(
                point(0, 0, 100),
                point(0, 100, 110),
                point(100, 100, 120),
                point(100, 0, 130),
                point(0, 0, 100)));
    }

    @Test
    public void distance_betweenIdenticalPoints_isZero() {
        GeoUtils.TrackPoint p = point(0, 0, 0);
        assertEquals(0.0, GeoUtils.calculateDistance(p, p), 1e-6);
    }

    @Test
    public void distance_oneDegreeOfLatitude_matchesWgs84Meridian() {
        // WGS-84 子午线上 1 度纬度约 110.6 km（随纬度略有变化，此处取赤道附近基准）
        GeoUtils.TrackPoint a = new GeoUtils.TrackPoint(0.0, 0.0, 0);
        GeoUtils.TrackPoint b = new GeoUtils.TrackPoint(1.0, 0.0, 0);
        double distance = GeoUtils.calculateDistance(a, b);
        assertEquals(110574, distance, 200);
    }

    @Test
    public void distance_isSymmetric() {
        GeoUtils.TrackPoint a = point(0, 0, 0);
        GeoUtils.TrackPoint b = point(37, 82, 0);
        assertEquals(GeoUtils.calculateDistance(a, b), GeoUtils.calculateDistance(b, a), 1e-6);
    }

    @Test
    public void distance_thirtyMeterOffset_isWithinTolerance() {
        GeoUtils.TrackPoint a = point(0, 0, 0);
        GeoUtils.TrackPoint b = point(30, 0, 0);
        assertEquals(30.0, GeoUtils.calculateDistance(a, b), 0.5);
    }

    @Test
    public void bearing_dueNorth_isZero() {
        assertEquals(0.0, GeoUtils.calculateBearing(point(0, 0, 0), point(50, 0, 0)), 0.5);
    }

    @Test
    public void bearing_dueEast_isNinety() {
        assertEquals(90.0, GeoUtils.calculateBearing(point(0, 0, 0), point(0, 50, 0)), 0.5);
    }

    @Test
    public void bearing_dueSouth_isOneEighty() {
        assertEquals(180.0, GeoUtils.calculateBearing(point(0, 0, 0), point(-50, 0, 0)), 0.5);
    }

    @Test
    public void bearing_dueWest_isTwoSeventy() {
        assertEquals(270.0, GeoUtils.calculateBearing(point(0, 0, 0), point(0, -50, 0)), 0.5);
    }

    @Test
    public void bearing_isAlwaysWithinCompassRange() {
        List<GeoUtils.TrackPoint> loop = squareLoop();
        for (int i = 1; i < loop.size(); i++) {
            double bearing = GeoUtils.calculateBearing(loop.get(i - 1), loop.get(i));
            assertTrue("方位角越界: " + bearing, bearing >= 0.0 && bearing < 360.0);
        }
    }

    @Test
    public void buildProgressList_startsAtZeroAndIncreasesMonotonically() {
        List<Double> progress = GeoUtils.buildProgressList(squareLoop());
        assertEquals(5, progress.size());
        assertEquals(0.0, progress.get(0), 1e-9);
        for (int i = 1; i < progress.size(); i++) {
            assertTrue("前缀和应严格递增", progress.get(i) > progress.get(i - 1));
        }
    }

    @Test
    public void buildProgressList_totalMatchesFourSidesOfSquare() {
        List<Double> progress = GeoUtils.buildProgressList(squareLoop());
        double total = progress.get(progress.size() - 1);
        // 四条边各约 100 m
        assertEquals(400.0, total, 4.0);
    }

    @Test
    public void buildProgressList_emptyInput_returnsEmptyList() {
        assertTrue(GeoUtils.buildProgressList(new ArrayList<>()).isEmpty());
    }

    @Test
    public void interpolate_atZero_returnsFirstPoint() {
        List<GeoUtils.TrackPoint> loop = squareLoop();
        List<Double> progress = GeoUtils.buildProgressList(loop);

        GeoUtils.TrackPosition position = GeoUtils.interpolate(loop, progress, 0.0);
        assertNotNull(position);
        assertEquals(loop.get(0).latitude, position.latitude, 1e-9);
        assertEquals(loop.get(0).longitude, position.longitude, 1e-9);
        assertEquals(100.0, position.altitude, 1e-6);
    }

    @Test
    public void interpolate_alongFirstSegment_producesEastBearing() {
        List<GeoUtils.TrackPoint> loop = squareLoop();
        List<Double> progress = GeoUtils.buildProgressList(loop);

        // squareLoop 的第一条边由西向东，航向应为正东
        GeoUtils.TrackPosition position = GeoUtils.interpolate(loop, progress, 50.0);
        assertNotNull(position);
        assertEquals(90.0, position.bearing, 1.0);
    }

    @Test
    public void interpolate_alongSecondSegment_producesNorthBearing() {
        List<GeoUtils.TrackPoint> loop = squareLoop();
        List<Double> progress = GeoUtils.buildProgressList(loop);
        double firstSegment = progress.get(1);

        // 第二条边由南向北，航向应为正北
        GeoUtils.TrackPosition position =
                GeoUtils.interpolate(loop, progress, firstSegment + 50.0);
        assertNotNull(position);
        assertEquals(0.0, position.bearing, 1.0);
    }

    @Test
    public void interpolate_altitudeIsLinearlyInterpolated() {
        List<GeoUtils.TrackPoint> loop = squareLoop();
        List<Double> progress = GeoUtils.buildProgressList(loop);
        double firstSegment = progress.get(1);

        // 第一段高程 100 -> 110，走到一半应为 105
        GeoUtils.TrackPosition position =
                GeoUtils.interpolate(loop, progress, firstSegment / 2.0);
        assertNotNull(position);
        assertEquals(105.0, position.altitude, 0.2);
    }

    @Test
    public void interpolate_wrapsAroundAfterFullLap() {
        List<GeoUtils.TrackPoint> loop = squareLoop();
        List<Double> progress = GeoUtils.buildProgressList(loop);
        double total = progress.get(progress.size() - 1);

        // 第 2 圈起点应与第 1 圈起点重合
        GeoUtils.TrackPosition firstLap = GeoUtils.interpolate(loop, progress, 0.0);
        GeoUtils.TrackPosition secondLap = GeoUtils.interpolate(loop, progress, total);
        assertNotNull(firstLap);
        assertNotNull(secondLap);
        assertEquals(firstLap.latitude, secondLap.latitude, 1e-9);
        assertEquals(firstLap.longitude, secondLap.longitude, 1e-9);
    }

    @Test
    public void interpolate_manyLapsStayOnRoute() {
        List<GeoUtils.TrackPoint> loop = squareLoop();
        List<Double> progress = GeoUtils.buildProgressList(loop);

        GeoUtils.TrackPosition position = GeoUtils.interpolate(loop, progress, 4000.0 + 150.0);
        assertNotNull(position);
        assertTrue(position.latitude >= 22.0 && position.latitude <= 23.0);
        assertTrue(position.longitude >= 110.0 && position.longitude <= 111.0);
    }

    @Test
    public void interpolate_handlesDuplicatePointsWithoutHanging() {
        // 含零长度段的路线（真实 KML 里常见重复闭合点）。
        // 第一段为向正北 100 m，位移 50 m 应落在该段中点。
        List<GeoUtils.TrackPoint> route = new ArrayList<>(Arrays.asList(
                point(0, 0, 10),
                point(100, 0, 20),
                point(100, 0, 20),
                point(0, 0, 10),
                point(0, 0, 10)));
        List<Double> progress = GeoUtils.buildProgressList(route);
        assertNotNull(progress);

        GeoUtils.TrackPosition position = GeoUtils.interpolate(route, progress, 50.0);
        assertNotNull(position);
        // 走完 50 m 后纬度应比起点高约 50 m 对应的度数（约 0.00045）
        assertTrue("应位于第一段（向北 100 m）的中点附近，实际纬度偏移 "
                        + (position.latitude - BASE_LAT),
                position.latitude > BASE_LAT + 0.0002);
    }

    @Test
    public void interpolate_degenerateInputs_returnsNull() {
        List<GeoUtils.TrackPoint> single = new ArrayList<>();
        single.add(point(0, 0, 0));
        assertNull("少于两个点应返回 null",
                GeoUtils.interpolate(single, GeoUtils.buildProgressList(single), 10.0));
        assertNull("进度表为空应返回 null",
                GeoUtils.interpolate(squareLoop(), new ArrayList<>(), 10.0));
        assertNull("null 进度表应返回 null",
                GeoUtils.interpolate(squareLoop(), null, 10.0));
        assertNull("null 航点表应返回 null",
                GeoUtils.interpolate(null, new ArrayList<>(), 10.0));
        assertNull("进度表长度与航点数不一致应返回 null",
                GeoUtils.interpolate(squareLoop(), Arrays.asList(0.0, 100.0), 10.0));
    }

    @Test
    public void interpolate_beyondLastProgressEntry_returnsLastPoint() {
        // 位移超过总长但进度表最后一项小于它（浮点边界）时应回退到终点，而不是崩
        List<GeoUtils.TrackPoint> loop = squareLoop();
        List<Double> progress = GeoUtils.buildProgressList(loop);
        double total = progress.get(progress.size() - 1);

        GeoUtils.TrackPosition position =
                GeoUtils.interpolate(loop, progress, total - 1e-9);
        assertNotNull(position);
        assertEquals(loop.get(loop.size() - 1).latitude, position.latitude, 1e-6);
    }
}
