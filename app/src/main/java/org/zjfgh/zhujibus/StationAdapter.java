package org.zjfgh.zhujibus;

import android.annotation.SuppressLint;
import android.graphics.Color;
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
    private int selectedPosition = -1;

    public interface OnItemClickListener {
        void onItemClick(BusApiClient.BusLineStation station, int position);
    }

    public void setSelectedPosition(int position) {
        int oldSelected = selectedPosition;
        selectedPosition = position;
        if (oldSelected != -1) notifyItemChanged(oldSelected); // 恢复旧位置的颜色
        notifyItemChanged(selectedPosition); // 更新新位置的颜色
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

    public List<BusApiClient.BusLineStation> getStationList() {
        return stationList;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateBusAverageSpeed(int speed) {
        this.busAverageSpeed = speed;
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {

        return this.selectedPosition;
    }

    public BusApiClient.BusLineStation getBusLineStation(int position) {
        return stationList.get(position);
    }

    public void updateBusPositions(List<BusApiClient.BusPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            resetAllStations();
            notifyItemRangeChanged(0, stationList.size());
            return;
        }

        resetAllStations();

        for (BusApiClient.BusPosition bus : positions) {
            if (bus.currentStationOrder <= 0 || bus.currentStationOrder > stationList.size()) {
                continue;
            }

            BusApiClient.BusLineStation currentStation = stationList.get(bus.currentStationOrder - 1);

            if (bus.isArrived) {
                currentStation.status = BusApiClient.BusLineStation.StationStatus.CURRENT;
                currentStation.plateNumber = bus.plateNumber;
            } else {
                currentStation.status = BusApiClient.BusLineStation.StationStatus.NEXT_STATION;
                currentStation.plateNumber = bus.plateNumber;
            }
        }
        notifyItemRangeChanged(0, stationList.size());
    }

    private void resetAllStations() {
        for (BusApiClient.BusLineStation station : stationList) {
            station.status = BusApiClient.BusLineStation.StationStatus.DEFAULT;
            station.plateNumber = null;
        }
    }

    @Override
    public void onBindViewHolder(@NonNull StationViewHolder holder, int position) {
        if (position == getItemCount() - 1) {
            holder.lineBottom.setVisibility(View.GONE);
        } else {
            holder.lineBottom.setVisibility(View.VISIBLE);
        }
        BusApiClient.BusLineStation station = stationList.get(position);
        holder.stationOrder.setText(String.valueOf(station.stationOrder));
        holder.stationName.setText(station.stationName);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(station, position);
            }
        });
        holder.busStatusIcon.setVisibility(View.INVISIBLE);
        holder.busOnWayIcon.setVisibility(View.INVISIBLE);
        holder.tvPlateNumber.setVisibility(View.INVISIBLE);
        holder.stationName.setAlpha(1f);
        switch (station.status) {
            case CURRENT:
                holder.busStatusIcon.setVisibility(View.VISIBLE);
                break;
            case NEXT_STATION:
                holder.busOnWayIcon.setVisibility(View.VISIBLE);
                if (station.plateNumber != null && !station.plateNumber.isEmpty()) {
                    holder.tvPlateNumber.setText(station.plateNumber);
                    holder.tvPlateNumber.setVisibility(View.VISIBLE);
                }
                break;
            case DEFAULT:
            case NORMAL:
            case PASSED:
                holder.busStatusIcon.setVisibility(View.INVISIBLE);
                holder.busOnWayIcon.setVisibility(View.INVISIBLE);
                holder.tvPlateNumber.setVisibility(View.INVISIBLE);
                break;
        }
        if (position == selectedPosition) {
            holder.stationOrder.setBackgroundResource(R.drawable.red_circle);
            holder.stationName.setTextColor(Color.RED);
        } else {
            holder.stationOrder.setBackgroundResource(R.drawable.blue_circle);
            holder.stationName.setTextColor(Color.parseColor("#333333"));
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(station, position);
                setSelectedPosition(position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return stationList != null ? stationList.size() : 0;
    }

    static class StationViewHolder extends RecyclerView.ViewHolder {
        TextView stationOrder;
        View lineBottom;
        ImageView busStatusIcon;
        ImageView busOnWayIcon;
        TextView stationName;
        TextView tvPlateNumber;

        public StationViewHolder(@NonNull View itemView) {
            super(itemView);
            stationOrder = itemView.findViewById(R.id.station_order);
            lineBottom = itemView.findViewById(R.id.line_bottom);
            busStatusIcon = itemView.findViewById(R.id.bus_status_icon);
            busOnWayIcon = itemView.findViewById(R.id.bus_on_way_icon);
            stationName = itemView.findViewById(R.id.station_name);
            tvPlateNumber = itemView.findViewById(R.id.tv_plate_number);
        }
    }
}