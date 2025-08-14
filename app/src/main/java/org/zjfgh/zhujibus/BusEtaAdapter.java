package org.zjfgh.zhujibus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

public class BusEtaAdapter extends RecyclerView.Adapter<BusEtaAdapter.BusEtaViewHolder> {

    private List<BusEtaItem> etaItems;
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(BusEtaItem item);
    }

    public BusEtaAdapter(List<BusEtaItem> etaItems, OnItemClickListener listener) {
        this.etaItems = etaItems;
        this.listener = listener;
    }

    @NonNull
    @Override
    public BusEtaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.bus_eta_item, parent, false);
        return new BusEtaViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BusEtaViewHolder holder, int position) {
        BusEtaItem item = etaItems.get(position);
        holder.bind(item);
        if (position == getItemCount() - 1) {
            holder.view_.setVisibility(View.GONE);
        } else {
            holder.view_.setVisibility(View.VISIBLE);
        }
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return etaItems != null ? etaItems.size() : 0;
    }

    // ViewHolder 类
    static class BusEtaViewHolder extends RecyclerView.ViewHolder {
        private final LinearLayout ll_bus_info;
        private final TextView tvStopCount;
        private final TextView tvEtaTime;
        private final TextView tvDistance;
        private final TextView tvIsArrive;
        private final View view_;

        public BusEtaViewHolder(@NonNull View itemView) {
            super(itemView);
            tvStopCount = itemView.findViewById(R.id.tv_stop_count);
            tvEtaTime = itemView.findViewById(R.id.tv_eta_time);
            tvDistance = itemView.findViewById(R.id.tv_distance);
            ll_bus_info = itemView.findViewById(R.id.ll_bus_info);
            tvIsArrive = itemView.findViewById(R.id.tv_is_arrive);
            tvIsArrive.setVisibility(View.GONE);
            view_ = itemView.findViewById(R.id.view_);
        }

        public void bind(BusEtaItem item) {
            if (item.getStopCount() == 0) {
                tvIsArrive.setVisibility(View.VISIBLE);
                ll_bus_info.setVisibility(View.GONE);
                return;
            } else if (item.getStopCount() == 1) {
                tvStopCount.setText("下一站");
            } else {
                tvStopCount.setText(item.getStopCount() + "站后");
            }
            tvIsArrive.setVisibility(View.GONE);
            ll_bus_info.setVisibility(View.VISIBLE);
            tvEtaTime.setText("预计" + item.getEtaMinutes() + "分钟");
            String distanceDisplay;
            if (item.getDistance() < 1000) {
                distanceDisplay = item.getDistance() + "米";
            } else {
                distanceDisplay = String.format(Locale.getDefault(), "%.1f公里", item.getDistance() / 1000f);
            }
            tvDistance.setText("约" + distanceDisplay);
        }
    }
}