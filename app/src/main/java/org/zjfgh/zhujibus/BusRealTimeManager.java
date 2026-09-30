package org.zjfgh.zhujibus;

import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import com.amap.api.services.core.LatLonPoint;

public class BusRealTimeManager {
    private static final long REFRESH_INTERVAL = 10 * 1000; // 15秒刷新一次
    private Handler handler;
    private String currentLineId;
    /** 当前回调监听器，由 startTracking 设置，refreshNow 复用 */
    private RealTimeUpdateListener currentListener;
    private List<BusApiClient.BusPosition> busPositions = new ArrayList<>();
    private List<BusApiClient.BusLineStation> stationList;
    public int busAverageSpeed = 500; // 默认500米/分钟

    public interface RealTimeUpdateListener {
        void onBusPositionsUpdated(List<BusApiClient.BusPosition> positions);

        void onError(String message);
    }

    public BusRealTimeManager(Handler handler, List<BusApiClient.BusLineStation> stationList) {
        this.handler = handler;
        this.stationList = stationList;
    }

    public List<BusApiClient.BusLineStation> getStationList() {
        return stationList;
    }

    /** 高德坐标覆盖备份（首次覆盖前保存官方坐标，用于恢复）。非 null 表示当前处于高德坐标模式 */
    private List<BusApiClient.BusLineStation> amapOverrideBackup;

    /**
     * 用高德站点坐标覆盖官方坐标（按站点序号匹配）。
     * 高德与诸暨官方坐标同为 GCJ-02，可直接替换；GPS 报站与地图均读取本列表。
     */
    public int applyAmapCoordOverride(List<LatLonPoint> amapPoints) {
        if (stationList == null || amapPoints == null) return 0;
        if (amapOverrideBackup == null) {
            amapOverrideBackup = new ArrayList<>();
            for (BusApiClient.BusLineStation s : stationList) {
                BusApiClient.BusLineStation b = new BusApiClient.BusLineStation();
                b.poiOriginLat = s.poiOriginLat;
                b.poiOriginLon = s.poiOriginLon;
                b.lat = s.lat;
                b.lng = s.lng;
                amapOverrideBackup.add(b);
            }
        }
        int n = Math.min(stationList.size(), amapPoints.size());
        int applied = 0;
        for (int i = 0; i < n; i++) {
            LatLonPoint p = amapPoints.get(i);
            if (p == null) continue;
            BusApiClient.BusLineStation s = stationList.get(i);
            s.poiOriginLat = p.getLatitude();
            s.poiOriginLon = p.getLongitude();
            s.lat = p.getLatitude();
            s.lng = p.getLongitude();
            applied++;
        }
        return applied;
    }

    /** 恢复为诸暨官方坐标 */
    public void revertAmapCoordOverride() {
        if (amapOverrideBackup == null) return;
        int n = Math.min(stationList.size(), amapOverrideBackup.size());
        for (int i = 0; i < n; i++) {
            BusApiClient.BusLineStation s = stationList.get(i);
            BusApiClient.BusLineStation b = amapOverrideBackup.get(i);
            s.poiOriginLat = b.poiOriginLat;
            s.poiOriginLon = b.poiOriginLon;
            s.lat = b.lat;
            s.lng = b.lng;
        }
        amapOverrideBackup = null;
    }

    /** 当前是否处于高德坐标覆盖模式 */
    public boolean isAmapCoordOverrideActive() {
        return amapOverrideBackup != null;
    }

    /**
     * 返回官方原始站点坐标（覆盖前的），用于地图叠加对比。
     * 覆盖激活时取 {@link #amapOverrideBackup} 备份，否则取当前列表（即官方坐标）。
     * 顺序与 stationList 一致。
     */
    public List<LatLonPoint> getOfficialStationCoords() {
        List<BusApiClient.BusLineStation> src =
                (amapOverrideBackup != null) ? amapOverrideBackup : stationList;
        List<LatLonPoint> res = new ArrayList<>();
        if (src == null) return res;
        for (BusApiClient.BusLineStation s : src) {
            res.add(new LatLonPoint(s.poiOriginLat, s.poiOriginLon));
        }
        return res;
    }

    public void startTracking(String lineId, RealTimeUpdateListener listener) {
        this.currentLineId = lineId;
        this.currentListener = listener;
        fetchData(listener);
    }

    public void stopTracking() {
        handler.removeCallbacksAndMessages(null);
        currentListener = null;
    }

    /**
     * 主动触发一次刷新（供 UI 倒计时到 0 时调用）。
     * <p>
     * 由 Activity 的倒计时统一调度刷新节奏，避免 Manager 内部 postDelayed
     * 与 UI 倒计时错位造成"提前刷新"的现象。
     */
    public void refreshNow() {
        if (currentListener != null) {
            fetchData(currentListener);
        }
    }

    private void fetchData(final RealTimeUpdateListener listener) {
        BusApiClient busApiClient = new BusApiClient();
        busApiClient.queryBusVehicleDynamic(currentLineId,
                new BusApiClient.ApiCallback<>() {
                    @Override
                    public void onSuccess(BusApiClient.BusVehicleDynamicResponse response) {
                        if ("200".equals(response.returnFlag)) {
                            busAverageSpeed = response.data.busAverageSpeed;
                            processResponse(response.data, listener);
                        } else {
                            listener.onError(response.returnInfo);
                        }
                        // 不再 postDelayed 下一次，节奏由 UI 倒计时统一控制
                    }

                    @Override
                    public void onError(BusApiClient.BusApiException e) {
                        listener.onError(e.toString());
                        // 不再 postDelayed 下一次，节奏由 UI 倒计时统一控制
                    }
                });
    }

    private void processResponse(BusApiClient.BusVehicleDynamicData data,
                                 RealTimeUpdateListener listener) {
        List<BusApiClient.BusPosition> newPositions = new ArrayList<>();
        
        resetAllStations();
        
        for (BusApiClient.VehicleDynamicInfo vehicle : data.list) {
            if (stationList != null) {
                if (vehicle.vehicleOrder <= 0 || vehicle.vehicleOrder > stationList.size()) {
                    continue;
                }
                if (vehicle.vehicleOrder + 1 > stationList.size()) {
                    continue;
                }
            }

            BusApiClient.BusPosition position = new BusApiClient.BusPosition();
            position.plateNumber = vehicle.plateNumber;
            position.isArrived = vehicle.isArriveStation == 1;
            position.distanceToNext = vehicle.distance;
            position.updateTime = System.currentTimeMillis();
            position.lat = vehicle.lat;
            position.lng = vehicle.lng;
            position.currentStationOrder = vehicle.vehicleOrder;
            position.nextStationOrder = vehicle.vehicleOrder + 1;

            if (stationList != null) {
                BusApiClient.BusLineStation currentStation = stationList.get(vehicle.vehicleOrder - 1);
                if (vehicle.isArriveStation == 1) {
                    currentStation.status = BusApiClient.BusLineStation.StationStatus.CURRENT;
                } else {
                    currentStation.status = BusApiClient.BusLineStation.StationStatus.NEXT_STATION;
                }
                currentStation.plateNumber = vehicle.plateNumber;
            }
            newPositions.add(position);
        }
        this.busPositions = newPositions;
        listener.onBusPositionsUpdated(newPositions);
    }

    private void resetAllStations() {
        if (stationList == null) {
            return;
        }
        for (BusApiClient.BusLineStation station : stationList) {
            station.status = BusApiClient.BusLineStation.StationStatus.NORMAL;
            station.plateNumber = null;
        }
    }
}
