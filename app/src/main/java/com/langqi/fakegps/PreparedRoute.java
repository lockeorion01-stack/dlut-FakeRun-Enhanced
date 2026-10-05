package com.langqi.fakegps;

import java.util.List;

/** Immutable geometry, validated and indexed once on the route loading thread. */
final class PreparedRoute {
    final List<GeoUtils.TrackPoint> points;
    final List<Double> progress;
    final double distance;

    PreparedRoute(List<GeoUtils.TrackPoint> source) {
        if (source == null || source.size() < 2) {
            throw new IllegalArgumentException("线路至少需要两个坐标点");
        }
        points = GeoUtils.immutableCopy(source);
        for (GeoUtils.TrackPoint point : points) {
            if (point == null || !Double.isFinite(point.latitude)
                    || !Double.isFinite(point.longitude) || !Double.isFinite(point.altitude)
                    || Math.abs(point.latitude) > 90 || Math.abs(point.longitude) > 180) {
                throw new IllegalArgumentException("路线包含无效坐标或高程");
            }
        }
        progress = GeoUtils.immutableCopy(GeoUtils.buildProgressList(points));
        distance = progress.get(progress.size() - 1);
        if (!Double.isFinite(distance) || distance <= 0) {
            throw new IllegalArgumentException("路线长度必须大于零");
        }
    }
}
