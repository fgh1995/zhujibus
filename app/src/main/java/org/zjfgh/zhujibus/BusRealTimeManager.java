package org.zjfgh.zhujibus;

import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class BusRealTimeManager {
    private static final long REFRESH_INTERVAL = 5 * 1000; // 15秒刷新一次
    private Handler handler;
    private String currentLineId;
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

    public void startTracking(String lineId, RealTimeUpdateListener listener) {
        this.currentLineId = lineId;
        fetchData(listener);
    }

    public void stopTracking() {
        handler.removeCallbacksAndMessages(null);
    }

    private void fetchData(final RealTimeUpdateListener listener) {
        BusApiClient busApiClient = new BusApiClient();
        busApiClient.queryBusVehicleDynamic(currentLineId,
                new BusApiClient.ApiCallback<>() {
                    @Override
                    public void onSuccess(BusApiClient.BusVehicleDynamicResponse response) {
                        Log.d("BusInfo", "response.returnFlag" + response.returnFlag);
                        if ("200".equals(response.returnFlag)) {
                            busAverageSpeed = response.data.busAverageSpeed;
                            processResponse(response.data, listener);
                        } else {
                            listener.onError(response.returnInfo);
                        }
                        handler.postDelayed(() -> fetchData(listener), REFRESH_INTERVAL);
                    }

                    @Override
                    public void onError(BusApiClient.BusApiException e) {
                        listener.onError(e.toString());
                        handler.postDelayed(() -> fetchData(listener), REFRESH_INTERVAL);
                    }
                });
    }

    private void processResponse(BusApiClient.BusVehicleDynamicData data,
                                 RealTimeUpdateListener listener) {
        List<BusApiClient.BusPosition> newPositions = new ArrayList<>();

        for (BusApiClient.VehicleDynamicInfo vehicle : data.list) {
            BusApiClient.BusPosition position = new BusApiClient.BusPosition();

            position.plateNumber = vehicle.plateNumber;
            position.isArrived = vehicle.isArriveStation == 1;
            position.distanceToNext = vehicle.distance;
            position.updateTime = System.currentTimeMillis();
            position.lat = vehicle.lat;
            position.lng = vehicle.lng;
            // 当前站始终是 vehicleOrder（无论是否到站）
            position.currentStationOrder = vehicle.vehicleOrder;
            // 下一站：如果已到站，则下一站是 vehicleOrder + 1
            // 如果未到站，也表示下一站是 vehicleOrder + 1（但UI上仍显示在 currentStationOrder 的指示线中间）
            position.nextStationOrder = vehicle.vehicleOrder + 1;

            // 确保不超出站点范围
            if (stationList != null) {
                position.nextStationOrder = Math.min(position.nextStationOrder, stationList.size());
            }

            newPositions.add(position);
        }

        this.busPositions = newPositions;
        listener.onBusPositionsUpdated(newPositions);
    }
}
