package org.zjfgh.zhujibus;

import android.annotation.SuppressLint;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class StationAdapter extends RecyclerView.Adapter<StationAdapter.StationViewHolder> {
    private List<BusApiClient.BusLineStation> stationList;
    private int busAverageSpeed = 500; // 默认500米/分钟
    private final OnItemClickListener listener;


    /**
     * 获取指定位置的站点数据
     *
     * @param position 位置索引
     * @return 对应位置的站点数据
     */
    public BusApiClient.BusLineStation getStationAt(int position) {
        if (position >= 0 && position < stationList.size()) {
            return stationList.get(position);
        }
        return null; // 或者抛出 IndexOutOfBoundsException
    }

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

    @SuppressLint("NotifyDataSetChanged")
    public void updateBusAverageSpeed(int speed) {
        this.busAverageSpeed = speed;
        notifyDataSetChanged();
    }

    public void updateBusPositions(List<BusApiClient.BusPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            resetAllStations(); // 重置所有站点状态
            notifyDataSetChanged();
            return;
        }

        // 1. 重置所有站点状态（避免旧数据干扰）
        resetAllStations();

        // 2. 遍历所有公交车，更新对应站点的状态
        for (BusApiClient.BusPosition bus : positions) {
            // 检查 currentStationOrder 是否有效
            if (bus.currentStationOrder <= 0 || bus.currentStationOrder > stationList.size()) {
                continue; // 无效的站点序号，跳过
            }

            // 获取当前站
            BusApiClient.BusLineStation currentStation = stationList.get(bus.currentStationOrder - 1);

            if (bus.isArrived) {
                // 如果已到站，当前站标记为 CURRENT
                currentStation.status = BusApiClient.BusLineStation.StationStatus.CURRENT;
            } else {
                // 如果未到站，当前站标记为 CURRENT（表示正在前往该站）
                currentStation.status = BusApiClient.BusLineStation.StationStatus.NEXT_STATION;
            }
        }

        // 3. 通知 UI 刷新
        notifyDataSetChanged();
    }

    /**
     * 重置所有站点的状态为默认值
     */
    private void resetAllStations() {
        for (BusApiClient.BusLineStation station : stationList) {
            station.status = BusApiClient.BusLineStation.StationStatus.DEFAULT;
        }
    }

    @Override
    public void onBindViewHolder(@NonNull StationViewHolder holder, int position) {
        if (position == getItemCount() - 1) {
            holder.lineTop.setVisibility(View.GONE);
        }
        BusApiClient.BusLineStation station = stationList.get(position);
        // 设置站点序号
        holder.stationOrder.setText(String.valueOf(station.stationOrder));
        // 设置站点名称
        holder.stationName.setText(station.stationName);
        // 点击事件
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(station);
            }
        });
        // 重置所有视图状态
        holder.arrivedContainer.setVisibility(View.INVISIBLE);
        holder.onWayContainer.setVisibility(View.INVISIBLE);
        holder.stationName.setAlpha(1f);
        // 根据实时状态更新UI
        switch (station.status) {
            case CURRENT:
                // 当前站 - 显示已到站图标
                holder.arrivedContainer.setVisibility(View.VISIBLE);
                holder.tvPlateNumberIsArrive.setText(station.plateNumber);
                Log.d("BusInfo", "arrivalStatus.已到站:" + station.stationName);
                Log.d("BusInfo", "arrivalStatus.正在上下客");
                break;
            case NEXT_STATION:
                holder.onWayContainer.setVisibility(View.VISIBLE);
                holder.tvPlateNumberIsArrive.setText(station.plateNumber);
                Log.d("BusInfo", "arrivalStatus.途中:" + station.stationName);
                Log.d("BusInfo", "station.arrivalTime" + station.arrivalTime);
                break;

            case DEFAULT:
                holder.arrivedContainer.setVisibility(View.INVISIBLE);
                holder.onWayContainer.setVisibility(View.INVISIBLE);
                break;
        }
    }

    private String formatArrivalTime(int seconds) {
        if (seconds <= 0) return "即将到站";
        if (seconds < 60) return seconds + "秒后到站";
        int minutes = seconds / 60;
        return minutes + "分钟后到站";
    }

    @Override
    public int getItemCount() {
        return stationList != null ? stationList.size() : 0;
    }

    static class StationViewHolder extends RecyclerView.ViewHolder {
        TextView stationOrder;
        View lineTop;
        LinearLayout arrivedContainer;
        ImageView busStatusIcon;
        LinearLayout onWayContainer;
        ImageView busOnWayIcon;
        View lineBottom;
        TextView stationName;
        ImageView transferIcon;
        TextView tvPlateNumberIsArrive;
        TextView tvPlateNumberNotArrive;

        public StationViewHolder(@NonNull View itemView) {
            super(itemView);
            stationOrder = itemView.findViewById(R.id.station_order);
            lineTop = itemView.findViewById(R.id.line_top);
            arrivedContainer = itemView.findViewById(R.id.arrived_container);
            busStatusIcon = itemView.findViewById(R.id.bus_status_icon);
            onWayContainer = itemView.findViewById(R.id.on_way_container);
            busOnWayIcon = itemView.findViewById(R.id.bus_on_way_icon);
            stationName = itemView.findViewById(R.id.station_name);
            tvPlateNumberIsArrive = itemView.findViewById(R.id.tv_plate_number_is_arrive);
            tvPlateNumberNotArrive = itemView.findViewById(R.id.tv_plate_number_not_arrive);
            //transferIcon = itemView.findViewById(R.id.transfer_icon);
        }
    }
}