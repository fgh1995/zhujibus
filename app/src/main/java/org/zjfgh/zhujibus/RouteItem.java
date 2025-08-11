package org.zjfgh.zhujibus;

public class RouteItem {
    private String routeName;
    private String routeInfo;

    public RouteItem(String routeName, String routeInfo) {
        this.routeName = routeName;
        this.routeInfo = routeInfo;
    }

    // Getter 方法
    public String getRouteName() {
        return routeName;
    }

    public String getRouteInfo() {
        return routeInfo;
    }
}