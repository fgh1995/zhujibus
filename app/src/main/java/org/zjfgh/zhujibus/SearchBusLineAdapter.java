package org.zjfgh.zhujibus;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class SearchBusLineAdapter extends RecyclerView.Adapter<BusLineViewHolder> {
    private final List<BusApiClient.BusLineInfo> busLines = new ArrayList<>();
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(BusApiClient.BusLineInfo line);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setData(List<BusApiClient.BusLineInfo> newData) {
        busLines.clear();
        busLines.addAll(newData);
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public BusLineViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_search_bus_line, parent, false);
        return new BusLineViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BusLineViewHolder holder, int position) {
        BusApiClient.BusLineInfo line = busLines.get(position);

        // 提取线路数字（假设格式为"13路"）
        String lineNumber = line.lineName.replaceAll("[^0-9]", "");
        holder.tvLineBadge.setText(lineNumber.isEmpty() ? "?" : lineNumber);

        holder.tvLineName.setText(line.lineName);
        holder.tvStartStation.setText(line.startStation);
        holder.tvEndStation.setText(line.endStation);

        // 设置点击事件
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(line);
            }
        });
    }

    @Override
    public int getItemCount() {
        return busLines.size();
    }
}
