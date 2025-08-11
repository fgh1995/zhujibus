package org.zjfgh.zhujibus;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

public class RouteViewHolder extends BaseViewHolder {
    private TextView tvRouteName;
    private TextView tvRouteInfo;

    public RouteViewHolder(@NonNull View itemView) {
        super(itemView);
        tvRouteName = itemView.findViewById(R.id.tv_route_name);
        tvRouteInfo = itemView.findViewById(R.id.tv_route_info);
    }

    @Override
    public void bind(Object item) {
        RouteItem route = (RouteItem) item;
        tvRouteName.setText(route.getRouteName());
        tvRouteInfo.setText(route.getRouteInfo());
    }
}
