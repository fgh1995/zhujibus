package org.zjfgh.zhujibus;

public class BusEtaItem {
    private int stopCount;
    private int etaMinutes;
    private float distance; // 单位：公里
    public int position;
    private String plateNumber;
    public boolean isArriveStation;

    public BusEtaItem(int stopCount, int etaMinutes, float distance, boolean isArriveStation, String plateNumber) {
        this.stopCount = stopCount;
        this.etaMinutes = etaMinutes;
        this.distance = distance;
        this.isArriveStation = isArriveStation;
        this.plateNumber = plateNumber;
    }

    // Getter 方法
    public int getStopCount() {
        return stopCount;
    }

    public int getEtaMinutes() {
        return etaMinutes;
    }

    public float getDistance() {
        return distance;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public boolean getIsArriveStation() {
        return isArriveStation;
    }

    public void setIsArriveStation(boolean isArriveStation) {
        this.isArriveStation = isArriveStation;
    }

    public String getPlateNumber() {
        return plateNumber;
    }
}
