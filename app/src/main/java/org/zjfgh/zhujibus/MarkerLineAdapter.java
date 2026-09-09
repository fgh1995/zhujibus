package org.zjfgh.zhujibus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MarkerLineAdapter extends RecyclerView.Adapter<MarkerLineAdapter.LineViewHolder> {
    private List<DirectionMarker.LineInfo> lines = new ArrayList<>();
    private OnLineDeleteListener deleteListener;

    public interface OnLineDeleteListener {
        void onLineDelete(int position, DirectionMarker.LineInfo line);
    }

    public void setOnLineDeleteListener(OnLineDeleteListener listener) {
        this.deleteListener = listener;
    }

    public void setData(List<DirectionMarker.LineInfo> lines) {
        this.lines = lines;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public LineViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_marker_line, parent, false);
        return new LineViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LineViewHolder holder, int position) {
        holder.bind(lines.get(position), position);
    }

    @Override
    public int getItemCount() {
        return lines.size();
    }

    class LineViewHolder extends RecyclerView.ViewHolder {
        private TextView lineName;
        private TextView lineInfo;
        private TextView lineTime;
        private TextView lineTypeBadge;
        private ImageButton lineDelete;

        public LineViewHolder(@NonNull View itemView) {
            super(itemView);
            lineName = itemView.findViewById(R.id.line_name);
            lineInfo = itemView.findViewById(R.id.line_info);
            lineTime = itemView.findViewById(R.id.line_time);
            lineTypeBadge = itemView.findViewById(R.id.line_type_badge);
            lineDelete = itemView.findViewById(R.id.line_delete);
        }

        public void bind(DirectionMarker.LineInfo line, int position) {
            lineName.setText(line.lineName);

            if (line.lineType != null && !line.lineType.isEmpty()) {
                lineTypeBadge.setText(line.lineType);
                lineTypeBadge.setVisibility(View.VISIBLE);
            } else {
                lineTypeBadge.setVisibility(View.GONE);
            }

            lineInfo.setText(String.format("%s → %s", line.startStation, line.endStation));

            String timeInfo = "";
            if (line.departureTime != null && !line.departureTime.isEmpty() &&
                line.collectTime != null && !line.collectTime.isEmpty()) {
                timeInfo = String.format("首班 %s · 末班 %s", line.departureTime, line.collectTime);
            } else if (line.departureTime != null && !line.departureTime.isEmpty()) {
                timeInfo = String.format("首班 %s", line.departureTime);
            } else if (line.collectTime != null && !line.collectTime.isEmpty()) {
                timeInfo = String.format("末班 %s", line.collectTime);
            }
            lineTime.setText(timeInfo);

            lineDelete.setOnClickListener(v -> {
                if (deleteListener != null) {
                    deleteListener.onLineDelete(position, line);
                }
            });

            itemView.setOnLongClickListener(v -> {
                if (deleteListener != null) {
                    deleteListener.onLineDelete(position, line);
                }
                return true;
            });
        }
    }
}
