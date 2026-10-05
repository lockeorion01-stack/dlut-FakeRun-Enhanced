package com.langqi.fakegps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 纯几何计算工具：不依赖任何 Android API，便于在主机侧做单元测试。
 *
 * <p>路线模型说明：
 * <ul>
 *   <li>{@link TrackPoint} 保存一个 KML 航点的纬度、经度与高程（KML 的第三列）。</li>
 *   <li>{@link #buildProgressList(List)} 生成累计里程前缀和，用于「按已跑距离反查坐标」。</li>
 *   <li>{@link #interpolate(List, List, double)} 按累计位移求出当前坐标、高程与航向角。
 *       支持自动取模套圈，调用方无需自己处理圈数。</li>
 * </ul>
 */
final class GeoUtils {

    /** WGS-84 长半轴（米）。 */
    private static final double WGS84_A = 6378137.0;
    /** WGS-84 扁率。 */
    private static final double WGS84_F = 1 / 298.257223563;
    /** Vincenty 迭代收敛阈值。 */
    private static final double VINCENTY_TOLERANCE = 1e-12;
    /** Vincenty 迭代次数上限。 */
    private static final int VINCENTY_MAX_ITERATIONS = 200;

    private GeoUtils() {
    }

    /** 一个 KML 航点：纬度、经度、高程（米）。 */
    static final class TrackPoint {
        final double latitude;
        final double longitude;
        final double altitude;

        TrackPoint(double latitude, double longitude, double altitude) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
        }
    }

    /** 插值结果：坐标 + 高程 + 航向角（度，正北为 0，顺时针）。 */
    static final class TrackPosition {
        final double latitude;
        final double longitude;
        final double altitude;
        final float bearing;

        TrackPosition(double latitude, double longitude, double altitude, float bearing) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.bearing = bearing;
        }
    }

    /**
     * 生成累计里程前缀和。返回值第 i 项表示从起点走到第 i 个航点的累计距离（米），
     * 因此第 0 项恒为 0。
     */
    static List<Double> buildProgressList(List<TrackPoint> points) {
        List<Double> progressList = new ArrayList<>();
        if (points == null || points.isEmpty()) {
            return progressList;
        }
        progressList.add(0.0);
        for (int i = 1; i < points.size(); i++) {
            double segment = calculateDistance(points.get(i - 1), points.get(i));
            progressList.add(progressList.get(i - 1) + segment);
        }
        return progressList;
    }

    /**
     * 按累计位移求当前应上报的位置。
     *
     * @param points       路线航点，至少 2 个
     * @param progressList {@link #buildProgressList(List)} 的结果
     * @param displacement 已累计的位移（米），内部按单圈长度取模以实现套圈
     * @return 插值后的位置；输入非法时返回 {@code null}
     */
    static TrackPosition interpolate(List<TrackPoint> points, List<Double> progressList,
                                     double displacement) {
        if (points == null || progressList == null || points.size() < 2
                || progressList.size() != points.size()) {
            return null;
        }
        double totalDistance = progressList.get(progressList.size() - 1);
        if (totalDistance <= 0) {
            return null;
        }
        // 套圈：把任意大的位移折回单圈范围内
        double target = displacement % totalDistance;
        if (target < 0) {
            target += totalDistance;
        }

        for (int i = 1; i < progressList.size(); i++) {
            double segmentDistance = progressList.get(i) - progressList.get(i - 1);
            if (segmentDistance <= 0) {
                continue;
            }
            if (progressList.get(i) >= target) {
                double k = (target - progressList.get(i - 1)) / segmentDistance;
                TrackPoint p1 = points.get(i - 1);
                TrackPoint p2 = points.get(i);
                double latitude = p1.latitude + k * (p2.latitude - p1.latitude);
                double longitude = p1.longitude + k * (p2.longitude - p1.longitude);
                double altitude = p1.altitude + k * (p2.altitude - p1.altitude);
                // 航向取当前所在路段的方向，与真实 GPS 的 bearing 语义一致
                float bearing = (float) calculateBearing(p1, p2);
                return new TrackPosition(latitude, longitude, altitude, bearing);
            }
        }

        // 位移落在最后一段的浮点误差范围内时，返回终点
        TrackPoint last = points.get(points.size() - 1);
        TrackPoint secondLast = points.get(points.size() - 2);
        return new TrackPosition(last.latitude, last.longitude, last.altitude,
                (float) calculateBearing(secondLast, last));
    }

    /**
     * Vincenty 反算公式求两点间大圆距离（米）。比 Haversine 精度更高，
     * 在 0.4 km 量级的操场上误差可忽略。
     */
    static double calculateDistance(TrackPoint p1, TrackPoint p2) {
        double a = WGS84_A;
        double f = WGS84_F;
        double b = (1 - f) * a;

        double l = Math.toRadians(p2.longitude - p1.longitude);
        double u1 = Math.atan((1 - f) * Math.tan(Math.toRadians(p1.latitude)));
        double u2 = Math.atan((1 - f) * Math.tan(Math.toRadians(p2.latitude)));
        double sinU1 = Math.sin(u1);
        double cosU1 = Math.cos(u1);
        double sinU2 = Math.sin(u2);
        double cosU2 = Math.cos(u2);

        double lambda = l;
        double lambdaP;
        int iterations = 0;
        double sinSigma = 0;
        double cosSigma = 0;
        double sigma = 0;
        double sinAlpha = 0;
        double cosSqAlpha = 0;
        double cos2SigmaM = 0;

        do {
            double sinLambda = Math.sin(lambda);
            double cosLambda = Math.cos(lambda);
            sinSigma = Math.sqrt((cosU2 * sinLambda) * (cosU2 * sinLambda)
                    + (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda)
                    * (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda));
            if (sinSigma == 0) {
                return 0;
            }
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda;
            sigma = Math.atan2(sinSigma, cosSigma);
            sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma;
            cosSqAlpha = 1 - sinAlpha * sinAlpha;
            cos2SigmaM = cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha;
            if (Double.isNaN(cos2SigmaM)) {
                cos2SigmaM = 0;
            }
            double c = f / 16 * cosSqAlpha * (4 + f * (4 - 3 * cosSqAlpha));
            lambdaP = lambda;
            lambda = l + (1 - c) * f * sinAlpha
                    * (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma
                    * (-1 + 2 * cos2SigmaM * cos2SigmaM)));
        } while (Math.abs(lambda - lambdaP) > VINCENTY_TOLERANCE
                && ++iterations < VINCENTY_MAX_ITERATIONS);

        if (iterations >= VINCENTY_MAX_ITERATIONS) {
            return 0;
        }

        double uSq = cosSqAlpha * (a * a - b * b) / (b * b);
        double bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)));
        double bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)));
        double deltaSigma = bigB * sinSigma * (cos2SigmaM + bigB / 4 * (cosSigma
                * (-1 + 2 * cos2SigmaM * cos2SigmaM) - bigB / 6 * cos2SigmaM
                * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)));
        return b * bigA * (sigma - deltaSigma);
    }

    /**
     * 求 p1 指向 p2 的初始方位角（度，正北为 0，顺时针 0~360）。
     * 用于填充 {@code Location.setBearing}，避免航向恒为 0。
     */
    static double calculateBearing(TrackPoint p1, TrackPoint p2) {
        double lat1 = Math.toRadians(p1.latitude);
        double lat2 = Math.toRadians(p2.latitude);
        double deltaLon = Math.toRadians(p2.longitude - p1.longitude);

        double y = Math.sin(deltaLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360.0) % 360.0;
    }

    /** 只读视图便利方法，避免调用方直接改动内部列表。 */
    static <T> List<T> immutableCopy(List<T> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
