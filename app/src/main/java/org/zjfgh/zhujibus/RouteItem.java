package org.zjfgh.zhujibus;

public class RouteItem {
    private final String routeName;
    private final String routeInfo;
    private final String startStation;
    private final String endStation;
    private final int arrivalTime;

    public RouteItem(String routeName, String routeInfo, String startStation, String endStation, int arrivalTime) {
        this.routeName = routeName;
        this.routeInfo = routeInfo;
        this.startStation = startStation;
        this.endStation = endStation;
        this.arrivalTime = arrivalTime;
    }

    // Getter 方法
    public String getRouteName() {
        return routeName;
    }

    public String getRouteInfo() {
        return routeInfo;
    }

    public String getStartStation() {
        return startStation;
    }

    public String getEndStation() {
        return endStation;
    }

    public int getArrivalTime() {
        return arrivalTime;
    }
}