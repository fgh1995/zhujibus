package org.zjfgh.zhujibus;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class SearchBusStationAdapter extends RecyclerView.Adapter<SearchBusStationAdapter.BusStationViewHolder> {
    private final List<BusApiClient.StationSimpleInfo> stationSimpleInfo = new ArrayList<>();
    private SearchBusStationAdapter.OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(BusApiClient.StationSimpleInfo stationSimpleInfo);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setData(List<BusApiClient.StationSimpleInfo> newData) {
        stationSimpleInfo.clear();
        stationSimpleInfo.addAll(newData);
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(SearchBusStationAdapter.OnItemClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public BusStationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_search_bus_station, parent, false);

        return new BusStationViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BusStationViewHolder holder, int position) {
        BusApiClient.StationSimpleInfo stationSimpleInfo1 = stationSimpleInfo.get(position);
        holder.stationName.setText(stationSimpleInfo1.stationName);
        // 设置点击事件
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(stationSimpleInfo1);
            }
        });
    }

    @Override
    public int getItemCount() {
        return stationSimpleInfo.size();
    }

    public class BusStationViewHolder extends RecyclerView.ViewHolder {
        TextView stationName;

        public BusStationViewHolder(@NonNull View itemView) {
            super(itemView);
            stationName = itemView.findViewById(R.id.station_title);
        }
    }
}
