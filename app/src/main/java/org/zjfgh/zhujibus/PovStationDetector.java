package org.zjfgh.zhujibus;

import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.sgr.geometry.Coordinate;
import io.sgr.geometry.utils.RouteGeometryUtils;

public class PovStationDetector {
    private static final String TAG = "PovStationDetector";
    private static final double DEFAULT_ENTER_STATION_RADIUS = 30.0;
    private static final double DEFAULT_EXIT_STATION_RADIUS = 80.0;
    private static final double STATION_PROXIMITY_THRESHOLD_METERS = 300.0;
    // 距离比"进站后的最小距离"增大多少米，才算"车辆已越过最近点、开始驶离站点"。
    // 出站必须"距离由减小转为增大"后再超过出站半径，否则进站半径大于出站半径时
    // （如进站80/出站40），车辆接近途中就会被误判成出站。
    private static final double EXIT_MOVING_AWAY_METERS = 5.0;
    private static final float MIN_VALID_SPEED_KMH = 3.0f;
    private static final long GPS_PROCESS_INTERVAL_MS = 1000L;

    public interface Callback {
        void onStationStatusChanged(boolean isAtStation, int stationIndex, String stationName);
        void onNearestStationUpdated(String name, double distance, double directDistance);
        void onEatUpdated(String eatText);
        void onGpsUpdated(double lat, double lng);
    }

    private Callback callback;
    private final List<BusApiClient.BusLineStation> stationList = new ArrayList<>();
    private final List<Coordinate> routePoints = new ArrayList<>();
    private double enterStationRadius = DEFAULT_ENTER_STATION_RADIUS;
    private double exitStationRadius = DEFAULT_EXIT_STATION_RADIUS;

    private boolean isInsideStationRadius = false;
    private int lastInsideStationIndex = -1;
    // 进站后到该站的最小距离：用于判断"距离是否已由减小转为增大"（车辆是否已驶离该站）
    private double insideStationMinDistance = Double.MAX_VALUE;
    private int currentStationIndex = -1;
    private int nextStationIndex = 0;
    private String nearestStationName = "";
    private double nearestStationDistance = -1;
    private double nearestStationDirectDistance = -1;
    private double currentGpsLat;
    private double currentGpsLon;
    private Location lastSpeedLocation;
    private Location latestLocation;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private boolean isWorkerStarted;
    private volatile boolean isDestroyed;
    private long lastSpeedTime;

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setRouteData(List<BusApiClient.BusLineStation> stations, List<Coordinate> route) {
        stationList.clear();
        if (stations != null) {
            stationList.addAll(stations);
        }
        routePoints.clear();
        if (route != null) {
            routePoints.addAll(route);
        }
        resetState();
        Log.d(TAG, "POV检测器路线数据已更新: stations=" + stationList.size() + ", routePoints=" + routePoints.size());
    }

    public void setStationRadius(double enterRadius, double exitRadius) {
        enterStationRadius = enterRadius > 0 ? enterRadius : DEFAULT_ENTER_STATION_RADIUS;
        exitStationRadius = exitRadius > 0 ? exitRadius : DEFAULT_EXIT_STATION_RADIUS;
    }

    public void onGpsLocation(Location location) {
        if (location == null) return;
        synchronized (this) {
            latestLocation = new Location(location);
        }
        ensureWorkerStarted();
    }

    public void destroy() {
        isDestroyed = true;
        callback = null;
        HandlerThread ht;
        Handler h;
        synchronized (this) {
            h = workerHandler;
            ht = workerThread;
            workerHandler = null;
            workerThread = null;
        }
        if (h != null) {
            h.removeCallbacksAndMessages(null);
        }
        if (ht != null) {
            ht.quitSafely();
            try {
                ht.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        stationList.clear();
        routePoints.clear();
        lastSpeedLocation = null;
        synchronized (this) {
            latestLocation = null;
        }
        isWorkerStarted = false;
    }

    private void ensureWorkerStarted() {
        if (isWorkerStarted || isDestroyed) return;
        synchronized (this) {
            if (isWorkerStarted || isDestroyed) return;
            workerThread = new HandlerThread("PovStationDetector");
            workerThread.start();
            workerHandler = new Handler(workerThread.getLooper());
            isWorkerStarted = true;
            workerHandler.post(processRunnable);
        }
    }

    private final Runnable processRunnable = new Runnable() {
        @Override
        public void run() {
            processLatestLocation();
            if (workerHandler != null) {
                workerHandler.postDelayed(this, GPS_PROCESS_INTERVAL_MS);
            }
        }
    };

    private void processLatestLocation() {
        if (isDestroyed) return;
        Location location;
        synchronized (this) {
            if (latestLocation == null) return;
            location = new Location(latestLocation);
        }
        if (isDestroyed) return;
        currentGpsLat = location.getLatitude();
        currentGpsLon = location.getLongitude();
        Callback cb = callback;
        if (cb != null) {
            cb.onGpsUpdated(currentGpsLat, currentGpsLon);
        }
        if (stationList.isEmpty() || routePoints.size() < 2) return;

        float speedKmh = calculateSpeedKmh(location);
        DetectionResult result = detectCurrentStation(currentGpsLat, currentGpsLon);
        updateState(result);
        cb = callback;
        if (cb != null) {
            if (nearestStationDistance >= 0) {
                cb.onNearestStationUpdated(nearestStationName, nearestStationDistance, nearestStationDirectDistance);
            }
            cb.onEatUpdated(calculateEatText(result, speedKmh));
        }
    }

    private DetectionResult detectCurrentStation(double lat, double lon) {
        DetectionResult result = new DetectionResult();
        nearestStationName = "";
        nearestStationDistance = -1;
        nearestStationDirectDistance = -1;

        float[] tmp = new float[1];
        for (int i = 0; i < stationList.size(); i++) {
            BusApiClient.BusLineStation station = stationList.get(i);
            double stationLat = station.poiOriginLat;
            double stationLon = station.poiOriginLon;
            if (stationLat == 0 && stationLon == 0) continue;

            Location.distanceBetween(lat, lon, stationLat, stationLon, tmp);
            double directDistance = tmp[0];
            double compareDistance = directDistance;
            boolean shouldUseRoute = !routePoints.isEmpty()
                    && (directDistance <= enterStationRadius
                    || directDistance <= STATION_PROXIMITY_THRESHOLD_METERS
                    || (i == lastInsideStationIndex && directDistance <= exitStationRadius * 2));
            if (shouldUseRoute) {
                RouteGeometryUtils.RouteDistanceResult routeDistance =
                        RouteGeometryUtils.calculateDistances(lat, lon, stationLat, stationLon, routePoints);
                if (routeDistance.alongRouteDistance >= 0) {
                    compareDistance = routeDistance.alongRouteDistance;
                } else {
                    compareDistance = routeDistance.directDistance;
                }
                directDistance = routeDistance.directDistance;
            }

            if (nearestStationDistance < 0 || compareDistance < nearestStationDistance) {
                nearestStationDistance = compareDistance;
                nearestStationDirectDistance = directDistance;
                nearestStationName = station.stationName;
                result.nearestIndex = i;
            }

            if (compareDistance <= enterStationRadius && !result.isInside && i >= Math.max(0, currentStationIndex)) {
                result.isInside = true;
                result.stationIndex = i;
                result.stationName = station.stationName;
            }
        }

        // ⭐ 出站判定独立于进站判定：只按【出站半径】判断"是否已离开上一帧所在站点"。
        //    原实现带 !result.isInside 门槛，于是只要还在进站半径内就永远判不出站 ——
        //    出站半径小于进站半径时（例如进站40/出站30），表现就是"出站按进站半径判定"。
        if (isInsideStationRadius && lastInsideStationIndex >= 0) {
            BusApiClient.BusLineStation lastInside = stationList.get(lastInsideStationIndex);
            double stationLat = lastInside.poiOriginLat;
            double stationLon = lastInside.poiOriginLon;
            if (stationLat != 0 || stationLon != 0) {
                Location.distanceBetween(lat, lon, stationLat, stationLon, tmp);
                double distance = tmp[0];
                if (!routePoints.isEmpty()) {
                    RouteGeometryUtils.RouteDistanceResult routeDistance =
                            RouteGeometryUtils.calculateDistances(lat, lon, stationLat, stationLon, routePoints);
                    distance = routeDistance.alongRouteDistance >= 0 ? routeDistance.alongRouteDistance : routeDistance.directDistance;
                }
                // ① 距离已由减小转为增大（越过最近点/站点，用"最小距离 + 余量"抗 GPS 抖动），
                // ② 且已超过【出站半径】，才算驶离。
                // 缺①时，进站半径 > 出站半径（如进站80/出站40）会在车辆还在接近时（距离40~80）就判出站。
                if (distance < insideStationMinDistance) {
                    insideStationMinDistance = distance;
                }
                boolean movingAway = distance >= insideStationMinDistance + EXIT_MOVING_AWAY_METERS;
                if (movingAway && distance > exitStationRadius) {
                    result.leavingIndex = lastInsideStationIndex;
                    result.stationIndex = lastInsideStationIndex;
                    result.stationName = lastInside.stationName;
                }
            }
        }
        return result;
    }

    private void updateState(DetectionResult result) {
        if (callback == null || stationList.isEmpty()) return;
        if (result.leavingIndex >= 0) {
            isInsideStationRadius = false;
            currentStationIndex = Math.max(currentStationIndex, result.leavingIndex);
            lastInsideStationIndex = -1;
            insideStationMinDistance = Double.MAX_VALUE; // 已出站：重置最小距离，下一站重新统计
            nextStationIndex = Math.min(currentStationIndex + 1, stationList.size() - 1);
            callback.onStationStatusChanged(false, nextStationIndex, stationList.get(nextStationIndex).stationName);
            return;
        }
        if (!result.isInside) return;
        if (isInsideStationRadius) {
            if (result.stationIndex < currentStationIndex) {
                return;
            }
        } else if (result.stationIndex <= currentStationIndex) {
            return;
        }
        if (!isInsideStationRadius || result.stationIndex != currentStationIndex) {
            // 进站/换站：重新统计"最小距离"，让出站判定按本站的"先减小后增大"来判断
            insideStationMinDistance = Double.MAX_VALUE;
        }
        isInsideStationRadius = true;
        lastInsideStationIndex = result.stationIndex;
        currentStationIndex = result.stationIndex;
        nextStationIndex = Math.min(result.stationIndex + 1, stationList.size() - 1);
        callback.onStationStatusChanged(true, result.stationIndex, result.stationName);
    }

    private String calculateEatText(DetectionResult result, float speedKmh) {
        int nextIndex = nextStationIndex;
        if (result.isInside) {
            nextIndex = Math.min(result.stationIndex + 1, stationList.size() - 1);
        } else if (result.leavingIndex >= 0) {
            nextIndex = Math.min(result.leavingIndex + 1, stationList.size() - 1);
        }
        if (nextIndex < 0 || nextIndex >= stationList.size()) {
            return "预计：下一站 --，终点 --";
        }

        BusApiClient.BusLineStation nextStation = stationList.get(nextIndex);
        double distanceToNext = distanceToStation(nextStation);
        double distanceToTerminal = distanceToNext;
        for (int i = nextIndex; i < stationList.size() - 1; i++) {
            BusApiClient.BusLineStation current = stationList.get(i);
            BusApiClient.BusLineStation next = stationList.get(i + 1);
            distanceToTerminal += stationDistance(current, next);
        }

        String nextEat;
        String terminalEat;
        if (speedKmh > MIN_VALID_SPEED_KMH) {
            double speedMps = speedKmh / 3.6;
            nextEat = distanceToNext > 0 ? formatEtaTime((int) (distanceToNext / speedMps)) : "--";
            terminalEat = distanceToTerminal > 0 ? formatEtaTime((int) (distanceToTerminal / speedMps)) : "--";
        } else {
            nextEat = distanceToNext > 0 ? formatDistance(distanceToNext) : "--";
            terminalEat = distanceToTerminal > 0 ? formatDistance(distanceToTerminal) : "--";
        }
        return String.format(Locale.CHINA, "预计：下一站 %s，终点 %s", nextEat, terminalEat);
    }

    private double distanceToStation(BusApiClient.BusLineStation station) {
        if (station == null) return 0;
        if (!routePoints.isEmpty()) {
            RouteGeometryUtils.RouteDistanceResult routeDistance = RouteGeometryUtils.calculateDistances(
                    currentGpsLat, currentGpsLon, station.poiOriginLat, station.poiOriginLon, routePoints);
            return routeDistance.alongRouteDistance >= 0 ? routeDistance.alongRouteDistance : routeDistance.directDistance;
        }
        float[] result = new float[1];
        Location.distanceBetween(currentGpsLat, currentGpsLon, station.poiOriginLat, station.poiOriginLon, result);
        return result[0];
    }

    private double stationDistance(BusApiClient.BusLineStation from, BusApiClient.BusLineStation to) {
        if (from == null || to == null) return 0;
        if (from.distanceToNext > 0) return from.distanceToNext;
        if (!routePoints.isEmpty()) {
            RouteGeometryUtils.RouteDistanceResult routeDistance = RouteGeometryUtils.calculateDistances(
                    from.poiOriginLat, from.poiOriginLon, to.poiOriginLat, to.poiOriginLon, routePoints);
            return routeDistance.alongRouteDistance >= 0 ? routeDistance.alongRouteDistance : routeDistance.directDistance;
        }
        float[] result = new float[1];
        Location.distanceBetween(from.poiOriginLat, from.poiOriginLon, to.poiOriginLat, to.poiOriginLon, result);
        return result[0];
    }

    private float calculateSpeedKmh(Location location) {
        if (location.hasSpeed()) return location.getSpeed() * 3.6f;
        long now = System.currentTimeMillis();
        float speedKmh = 0f;
        if (lastSpeedLocation != null && lastSpeedTime > 0) {
            long diff = now - lastSpeedTime;
            if (diff > 0 && diff < 10000) {
                speedKmh = (lastSpeedLocation.distanceTo(location) / (diff / 1000f)) * 3.6f;
            }
        }
        lastSpeedLocation = new Location(location);
        lastSpeedTime = now;
        return speedKmh;
    }

    private void resetState() {
        isInsideStationRadius = false;
        lastInsideStationIndex = -1;
        insideStationMinDistance = Double.MAX_VALUE;
        currentStationIndex = -1;
        nextStationIndex = 0;
        nearestStationName = "";
        nearestStationDistance = -1;
        nearestStationDirectDistance = -1;
        synchronized (this) {
            latestLocation = null;
        }
    }

    private String formatDistance(double meters) {
        if (meters >= 1000) return String.format(Locale.CHINA, "%.1fkm", meters / 1000);
        return String.format(Locale.CHINA, "%.0fm", meters);
    }

    private String formatEtaTime(int totalSeconds) {
        if (totalSeconds < 60) return totalSeconds + "秒";
        if (totalSeconds < 3600) {
            int minutes = totalSeconds / 60;
            int seconds = totalSeconds % 60;
            return seconds > 0 ? minutes + "分" + seconds + "秒" : minutes + "分钟";
        }
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        return minutes > 0 ? hours + "时" + minutes + "分" : hours + "小时";
    }

    private static class DetectionResult {
        boolean isInside;
        int stationIndex = -1;
        int leavingIndex = -1;
        int nearestIndex = -1;
        String stationName;
    }
}
