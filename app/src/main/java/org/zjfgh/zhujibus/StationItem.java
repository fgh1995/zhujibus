package org.zjfgh.zhujibus;

import java.util.List;

public class StationItem {
    private String stationName;
    private String distance;
    private List<RouteItem> routes;

    public StationItem(String stationName, String distance, List<RouteItem> routes) {
        this.stationName = stationName;
        this.distance = distance;
        this.routes = routes;
    }

    // Getter 方法
    public String getStationName() {
        return stationName;
    }

    public String getDistance() {
        return distance;
    }

    public List<RouteItem> getRoutes() {
        return routes;
    }
}
