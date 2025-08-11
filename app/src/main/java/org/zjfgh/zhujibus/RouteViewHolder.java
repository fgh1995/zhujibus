package org.zjfgh.zhujibus;

import android.annotation.SuppressLint;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

public class RouteViewHolder extends BaseViewHolder {
    private final TextView tvRouteName;
    private final TextView tvRouteInfo;
    private final TextView startEndStation;
    private final TextView tvRouteArrivalTime;

    public RouteViewHolder(@NonNull View itemView) {
        super(itemView);
        tvRouteName = itemView.findViewById(R.id.tv_route_name);
        tvRouteInfo = itemView.findViewById(R.id.tv_route_info);
        startEndStation = itemView.findViewById(R.id.tv_route_station_orientation);
        tvRouteArrivalTime = itemView.findViewById(R.id.tv_route_arrival_time);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void bind(Object item) {
        RouteItem route = (RouteItem) item;
        tvRouteName.setText(route.getRouteName());
        tvRouteInfo.setText(route.getRouteInfo());
        startEndStation.setText(route.getStartStation() + " → " + route.getEndStation());
        tvRouteArrivalTime.setText("预计：" + route.getArrivalTime() + "分种");
    }
}
