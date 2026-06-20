package org.zjfgh.zhujibus;

import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

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
