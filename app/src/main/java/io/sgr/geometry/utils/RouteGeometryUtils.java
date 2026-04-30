package io.sgr.geometry.utils;

import io.sgr.geometry.Coordinate;

import java.util.ArrayList;
import java.util.List;

public class RouteGeometryUtils {

    public static List<Coordinate> parseGeometry(String geometryStr) {
        List<Coordinate> points = new ArrayList<>();
        if (geometryStr == null || geometryStr.isEmpty()) {
            return points;
        }

        String[] pairs = geometryStr.split(";");
        for (String pair : pairs) {
            String[] coords = pair.split(",");
            if (coords.length == 2) {
                try {
                    double lng = Double.parseDouble(coords[0].trim());
                    double lat = Double.parseDouble(coords[1].trim());
                    points.add(new Coordinate(lat, lng));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return points;
    }

    public static double distanceBetween(double lat1, double lng1, double lat2, double lng2) {
        float[] results = new float[1];
        android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, results);
        return results[0];
    }

    public static double pointToRouteDistance(Coordinate point, List<Coordinate> routePoints) {
        if (point == null || routePoints == null || routePoints.size() < 2) {
            return -1;
        }

        double minDistance = Double.MAX_VALUE;
        for (int i = 0; i < routePoints.size() - 1; i++) {
            double segmentDist = pointToSegmentDistance(point, routePoints.get(i), routePoints.get(i + 1));
            if (segmentDist < minDistance) {
                minDistance = segmentDist;
            }
        }
        return minDistance == Double.MAX_VALUE ? -1 : minDistance;
    }

    public static double pointToSegmentDistance(Coordinate point, Coordinate segStart, Coordinate segEnd) {
        double px = point.getLng();
        double py = point.getLat();
        double x1 = segStart.getLng();
        double y1 = segStart.getLat();
        double x2 = segEnd.getLng();
        double y2 = segEnd.getLat();

        double dx = x2 - x1;
        double dy = y2 - y1;

        if (dx == 0 && dy == 0) {
            return distanceBetween(py, px, y1, x1);
        }

        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));

        double projX = x1 + t * dx;
        double projY = y1 + t * dy;

        return distanceBetween(py, px, projY, projX);
    }

    public static ProjectionResult projectToRoute(Coordinate point, List<Coordinate> routePoints) {
        if (point == null || routePoints == null || routePoints.size() < 2) {
            return null;
        }

        double minPerpDist = Double.MAX_VALUE;
        int nearestSegIndex = 0;
        double minT = 0;

        for (int i = 0; i < routePoints.size() - 1; i++) {
            double t = projectT(point, routePoints.get(i), routePoints.get(i + 1));
            t = Math.max(0, Math.min(1, t));
            double perpDist = perpendicularDistanceAtT(point, routePoints.get(i), routePoints.get(i + 1), t);

            if (perpDist < minPerpDist) {
                minPerpDist = perpDist;
                nearestSegIndex = i;
                minT = t;
            }
        }

        double totalDistToProjection = 0;

        for (int i = 0; i < nearestSegIndex; i++) {
            totalDistToProjection += distanceBetween(
                    routePoints.get(i).getLat(), routePoints.get(i).getLng(),
                    routePoints.get(i + 1).getLat(), routePoints.get(i + 1).getLng()
            );
        }

        if (nearestSegIndex < routePoints.size() - 1) {
            double segLen = distanceBetween(
                    routePoints.get(nearestSegIndex).getLat(), routePoints.get(nearestSegIndex).getLng(),
                    routePoints.get(nearestSegIndex + 1).getLat(), routePoints.get(nearestSegIndex + 1).getLng()
            );
            totalDistToProjection += segLen * minT;
        }

        return new ProjectionResult(totalDistToProjection, nearestSegIndex, minT, minPerpDist);
    }

    public static double distanceAlongRoute(Coordinate gpsPoint, List<Coordinate> routePoints, Coordinate stationPoint) {
        ProjectionResult gpsProj = projectToRoute(gpsPoint, routePoints);
        ProjectionResult stationProj = projectToRoute(stationPoint, routePoints);

        if (gpsProj == null || stationProj == null) {
            return distanceBetween(gpsPoint.getLat(), gpsPoint.getLng(), stationPoint.getLat(), stationPoint.getLng());
        }

        double distance = Math.abs(gpsProj.distanceAlongRoute - stationProj.distanceAlongRoute);

        double perpDist1 = gpsProj.perpendicularDistance;
        double perpDist2 = stationProj.perpendicularDistance;
        double maxPerpDist = Math.max(perpDist1, perpDist2);

        if (maxPerpDist > 200) {
            distance = Math.sqrt(distance * distance + maxPerpDist * maxPerpDist);
        }

        return distance;
    }

    public static RouteDistanceResult calculateDistances(
            double gpsLat, double gpsLon,
            double stationLat, double stationLon,
            List<Coordinate> routePoints) {

        RouteDistanceResult result = new RouteDistanceResult();

        result.directDistance = distanceBetween(gpsLat, gpsLon, stationLat, stationLon);

        if (routePoints == null || routePoints.size() < 2) {
            result.alongRouteDistance = result.directDistance;
            result.gpsToRouteDistance = -1;
            result.stationToRouteDistance = -1;
            return result;
        }

        Coordinate gpsCoord = new Coordinate(gpsLat, gpsLon);
        Coordinate stationCoord = new Coordinate(stationLat, stationLon);

        ProjectionResult gpsProj = projectToRoute(gpsCoord, routePoints);
        ProjectionResult stationProj = projectToRoute(stationCoord, routePoints);

        if (gpsProj != null && stationProj != null) {
            result.alongRouteDistance = Math.abs(gpsProj.distanceAlongRoute - stationProj.distanceAlongRoute);
            result.gpsToRouteDistance = gpsProj.perpendicularDistance;
            result.stationToRouteDistance = stationProj.perpendicularDistance;
            result.gpsSegmentIndex = gpsProj.segmentIndex;
            result.stationSegmentIndex = stationProj.segmentIndex;
            result.gpsT = gpsProj.t;
            result.stationT = stationProj.t;

            double perpDist1 = gpsProj.perpendicularDistance;
            double perpDist2 = stationProj.perpendicularDistance;
            double maxPerpDist = Math.max(perpDist1, perpDist2);

            if (maxPerpDist > 200) {
                result.alongRouteDistance = Math.sqrt(
                        result.alongRouteDistance * result.alongRouteDistance +
                        maxPerpDist * maxPerpDist
                );
            }
        } else {
            result.alongRouteDistance = result.directDistance;
        }

        return result;
    }

    private static double projectT(Coordinate point, Coordinate segStart, Coordinate segEnd) {
        double px = point.getLng();
        double py = point.getLat();
        double x1 = segStart.getLng();
        double y1 = segStart.getLat();
        double x2 = segEnd.getLng();
        double y2 = segEnd.getLat();

        double dx = x2 - x1;
        double dy = y2 - y1;

        if (dx == 0 && dy == 0) {
            return 0;
        }

        return ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
    }

    private static double perpendicularDistanceAtT(Coordinate point, Coordinate segStart, Coordinate segEnd, double t) {
        double projX = segStart.getLng() + t * (segEnd.getLng() - segStart.getLng());
        double projY = segStart.getLat() + t * (segEnd.getLat() - segStart.getLat());

        return distanceBetween(point.getLat(), point.getLng(), projY, projX);
    }

    public static class ProjectionResult {
        public final double distanceAlongRoute;
        public final int segmentIndex;
        public final double t;
        public final double perpendicularDistance;

        public ProjectionResult(double distanceAlongRoute, int segmentIndex, double t, double perpendicularDistance) {
            this.distanceAlongRoute = distanceAlongRoute;
            this.segmentIndex = segmentIndex;
            this.t = t;
            this.perpendicularDistance = perpendicularDistance;
        }
    }

    public static class RouteDistanceResult {
        public double directDistance;
        public double alongRouteDistance;
        public double gpsToRouteDistance;
        public double stationToRouteDistance;
        public int gpsSegmentIndex = -1;
        public int stationSegmentIndex = -1;
        public double gpsT = 0;
        public double stationT = 0;
    }
}