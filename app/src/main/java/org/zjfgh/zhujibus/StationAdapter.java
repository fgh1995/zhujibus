package org.zjfgh.zhujibus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class StationAdapter extends RecyclerView.Adapter<StationAdapter.StationViewHolder> {
    private List<BusApiClient.BusLineStation> stationList;
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(BusApiClient.BusLineStation station);
    }

    public StationAdapter(List<BusApiClient.BusLineStation> stationList, OnItemClickListener listener) {
        this.stationList = stationList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public StationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_station_details, parent, false);
        return new StationViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull StationViewHolder holder, int position) {
        BusApiClient.BusLineStation station = stationList.get(position);

        // 设置站点序号
        holder.stationOrder.setText(String.valueOf(station.stationOrder));

        // 设置站点名称
        holder.stationName.setText(station.stationName);

        // 处理第一个和最后一个站点的线路指示线
        if (position == 0) {
            holder.lineTop.setVisibility(View.INVISIBLE);
        } else {
            holder.lineTop.setVisibility(View.VISIBLE);
        }

        if (position == stationList.size() - 1) {
            holder.lineBottom.setVisibility(View.INVISIBLE);
        } else {
            holder.lineBottom.setVisibility(View.VISIBLE);
        }

        // 显示换乘图标（如果有换乘信息）
//        if (station.hasTransfer) {
//            holder.transferIcon.setVisibility(View.VISIBLE);
//        } else {
//            holder.transferIcon.setVisibility(View.GONE);
//        }

        // 点击事件
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(station);
            }
        });
    }

    @Override
    public int getItemCount() {
        return stationList != null ? stationList.size() : 0;
    }

    static class StationViewHolder extends RecyclerView.ViewHolder {
        TextView stationOrder;
        View lineTop;
        ImageView stationIcon;
        View lineBottom;
        TextView stationName;
        ImageView transferIcon;

        public StationViewHolder(@NonNull View itemView) {
            super(itemView);
            stationOrder = itemView.findViewById(R.id.station_order);
            lineTop = itemView.findViewById(R.id.line_top);
            //stationIcon = itemView.findViewById(R.id.station_icon);
            lineBottom = itemView.findViewById(R.id.line_bottom);
            stationName = itemView.findViewById(R.id.station_name);
            //transferIcon = itemView.findViewById(R.id.transfer_icon);
        }
    }
}