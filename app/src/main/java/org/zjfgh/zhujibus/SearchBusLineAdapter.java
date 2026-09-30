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
    private final List<SearchLineResult> busLines = new ArrayList<>();
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(SearchLineResult line);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setData(List<SearchLineResult> newData) {
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
        SearchLineResult line = busLines.get(position);

        holder.tvLineBadge.setText(line.getBadge());

        holder.tvLineName.setText(line.lineName);
        // 地区单独展示（如「嵊州」「诸暨」），无地区时隐藏 chip，不连写在线路名里
        if (line.region == null || line.region.isEmpty()) {
            holder.tvRegion.setVisibility(View.GONE);
        } else {
            holder.tvRegion.setText(line.region);
            holder.tvRegion.setVisibility(View.VISIBLE);
        }
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
