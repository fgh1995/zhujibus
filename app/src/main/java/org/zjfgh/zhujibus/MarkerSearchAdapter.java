package org.zjfgh.zhujibus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MarkerSearchAdapter extends RecyclerView.Adapter<MarkerSearchAdapter.MarkerViewHolder> {
    private List<DirectionMarker> markers = new ArrayList<>();
    private OnMarkerClickListener listener;

    public interface OnMarkerClickListener {
        void onMarkerClick(DirectionMarker marker);
    }

    public void setOnMarkerClickListener(OnMarkerClickListener listener) {
        this.listener = listener;
    }

    public void setData(List<DirectionMarker> markers) {
        this.markers = markers;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public MarkerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_marker_search_result, parent, false);
        return new MarkerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MarkerViewHolder holder, int position) {
        holder.bind(markers.get(position));
    }

    @Override
    public int getItemCount() {
        return markers.size();
    }

    class MarkerViewHolder extends RecyclerView.ViewHolder {
        private TextView markerName;
        private TextView stationName;
        private TextView lineCount;

        public MarkerViewHolder(@NonNull View itemView) {
            super(itemView);
            markerName = itemView.findViewById(R.id.marker_name);
            stationName = itemView.findViewById(R.id.marker_station_name);
            lineCount = itemView.findViewById(R.id.marker_line_count);
        }

        public void bind(DirectionMarker marker) {
            markerName.setText(marker.markerName);
            stationName.setText("站点：" + marker.stationName);
            lineCount.setText("已添加 " + marker.lineIds.size() + " 条线路");

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onMarkerClick(marker);
                }
            });
        }
    }
}