package org.zjfgh.zhujibus;

public class BusEtaItem {
    private int stopCount;
    private int etaMinutes;
    private float distance; // 单位：公里
    public int position;

    public boolean isArriveStation;

    public BusEtaItem(int stopCount, int etaMinutes, float distance, boolean isArriveStation) {
        this.stopCount = stopCount;
        this.etaMinutes = etaMinutes;
        this.distance = distance;
        this.isArriveStation = isArriveStation;
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
}
