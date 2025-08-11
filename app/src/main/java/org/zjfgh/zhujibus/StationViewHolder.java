package org.zjfgh.zhujibus;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

public class StationViewHolder extends BaseViewHolder {
    private TextView tvStationName;
    private TextView tvDistance;

    public StationViewHolder(@NonNull View itemView) {
        super(itemView);
        tvStationName = itemView.findViewById(R.id.tv_station_name);
        tvDistance = itemView.findViewById(R.id.tv_distance);
    }

    @Override
    public void bind(Object item) {
        StationItem station = (StationItem) item;
        tvStationName.setText(station.getStationName());
        tvDistance.setText(station.getDistance());
    }
}
