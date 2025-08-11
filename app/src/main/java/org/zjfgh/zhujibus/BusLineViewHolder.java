package org.zjfgh.zhujibus;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class BusLineViewHolder extends RecyclerView.ViewHolder {
    TextView tvLineBadge;
    TextView tvLineName;
    TextView tvStartStation;
    TextView tvEndStation;

    public BusLineViewHolder(@NonNull View itemView) {
        super(itemView);
        tvLineBadge = itemView.findViewById(R.id.tv_line_badge);
        tvLineName = itemView.findViewById(R.id.tv_line_name);
        tvStartStation = itemView.findViewById(R.id.tv_start_station);
        tvEndStation = itemView.findViewById(R.id.tv_end_station);
    }
}
