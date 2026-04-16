package org.zjfgh.zhujibus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class DirectionPagerAdapter extends RecyclerView.Adapter<DirectionPagerAdapter.DirectionViewHolder> {
    private Context context;
    private List<BusApiClient.LineDirection> directions;
    private OnDirectionLongClickListener longClickListener;
    private Set<String> highlightedLineIds = new HashSet<>();
    private Set<String> highlightedStationIds = new HashSet<>();
    private Set<String> grayedLineIds = new HashSet<>();
    private Set<String> grayedStationIds = new HashSet<>();
    private List<String> pendingHighlightLineIds;
    private List<String> pendingHighlightStationIds;
    private List<String> pendingGrayLineIds;
    private List<String> pendingGrayStationIds;

    public interface OnDirectionLongClickListener {
        void onDirectionLongClick(BusApiClient.LineDirection direction, View anchorView);
    }

    public void setOnDirectionLongClickListener(OnDirectionLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setHighlightedLines(List<String> lineIds, List<String> stationIds) {
        this.pendingHighlightLineIds = lineIds != null ? new ArrayList<>(lineIds) : new ArrayList<>();
        this.pendingHighlightStationIds = stationIds != null ? new ArrayList<>(stationIds) : new ArrayList<>();
        highlightedLineIds.clear();
        highlightedStationIds.clear();
        highlightedLineIds.addAll(this.pendingHighlightLineIds);
        highlightedStationIds.addAll(this.pendingHighlightStationIds);
        notifyDataSetChanged();
    }

    public void setGrayedLines(List<String> lineIds, List<String> stationIds) {
        this.pendingGrayLineIds = lineIds != null ? new ArrayList<>(lineIds) : new ArrayList<>();
        this.pendingGrayStationIds = stationIds != null ? new ArrayList<>(stationIds) : new ArrayList<>();
        grayedLineIds.clear();
        grayedStationIds.clear();
        if (lineIds != null) grayedLineIds.addAll(lineIds);
        if (stationIds != null) grayedStationIds.addAll(stationIds);
        notifyDataSetChanged();
    }

    public void clearHighlightAndGray() {
        highlightedLineIds.clear();
        highlightedStationIds.clear();
        grayedLineIds.clear();
        grayedStationIds.clear();
        pendingHighlightLineIds = null;
        pendingHighlightStationIds = null;
        pendingGrayLineIds = null;
        pendingGrayStationIds = null;
        notifyDataSetChanged();
    }

    public DirectionPagerAdapter(Context context, List<BusApiClient.LineDirection> directions) {
        this.context = context;
        this.directions = directions;
    }

    @NonNull
    @Override
    public DirectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_bus_direction_station, parent, false);
        return new DirectionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DirectionViewHolder holder, int position) {
        BusApiClient.LineDirection direction = directions.get(position);
        boolean isHighlighted = highlightedLineIds.contains(direction.lineId) &&
                               highlightedStationIds.contains(direction.stationId);
        boolean isGrayed = grayedLineIds.contains(direction.lineId) &&
                           grayedStationIds.contains(direction.stationId);
        holder.bind(direction, longClickListener, isHighlighted, isGrayed);
    }

    @Override
    public int getItemCount() {
        return directions.size();
    }

    static class DirectionViewHolder extends RecyclerView.ViewHolder {
        private LinearLayout busInfoContainer;
        private TextView busRouteName;
        private TextView busDistance;
        private TextView busStations;
        private TextView nextBusTime;
        private TextView nextBusLabel;
        private TextView firstBusTime;
        private TextView lastBusTime;

        public DirectionViewHolder(@NonNull View itemView) {
            super(itemView);
            busInfoContainer = itemView.findViewById(R.id.bus_info_container);
            busRouteName = itemView.findViewById(R.id.bus_route_name);
            busDistance = itemView.findViewById(R.id.bus_distance);
            busDistance.setText("");
            busStations = itemView.findViewById(R.id.bus_stations);
            nextBusTime = itemView.findViewById(R.id.next_bus_time);
            nextBusTime.setText("");
            nextBusLabel = itemView.findViewById(R.id.next_bus_label);
            nextBusLabel.setText("");
            firstBusTime = itemView.findViewById(R.id.first_bus_time);
            lastBusTime = itemView.findViewById(R.id.last_bus_time);
        }

        @SuppressLint("SetTextI18n")
        public void bind(BusApiClient.LineDirection direction, OnDirectionLongClickListener listener,
                        boolean isHighlighted, boolean isGrayed) {
            busRouteName.setText(String.format("%s(%s公交)", direction.lineName, direction.lineTypeName));
            busStations.setText(String.format("%s-%s", direction.startStation, direction.endStation));
            firstBusTime.setText(direction.departureTime);
            lastBusTime.setText(direction.collectTime);

            if (isHighlighted) {
                busInfoContainer.setBackgroundColor(Color.parseColor("#E3F2FD"));
                busRouteName.setTextColor(Color.parseColor("#1976D2"));
                busStations.setTextColor(Color.parseColor("#1976D2"));
            } else if (isGrayed) {
                busInfoContainer.setBackgroundColor(Color.parseColor("#F5F5F5"));
                busRouteName.setTextColor(Color.parseColor("#999999"));
                busStations.setTextColor(Color.parseColor("#999999"));
            } else {
                busInfoContainer.setBackgroundColor(Color.WHITE);
                busRouteName.setTextColor(Color.BLACK);
                busStations.setTextColor(Color.BLACK);
            }

            if (direction.vehicleInfo != null) {
                nextBusLabel.setText("最近一班");
                if (direction.vehicleInfo.nextNumber == 0 && direction.vehicleInfo.distance > 0) {
                    busDistance.setText("即将进站（约" + direction.vehicleInfo.distance + "米）");
                } else if (direction.vehicleInfo.nextNumber == 0 && direction.vehicleInfo.distance == 0) {
                    busDistance.setText("已到站");
                    nextBusTime.setText("");
                } else if (direction.vehicleInfo.distance > 1000) {
                    double distanceKm = direction.vehicleInfo.distance / 1000.0;
                    String distance = String.format("约%.1f公里", distanceKm);
                    busDistance.setText("距离查询站点" + direction.vehicleInfo.nextNumber + "站（" + distance + ")");
                } else {
                    String distance = String.format("约%d米", direction.vehicleInfo.distance);
                    busDistance.setText("距离查询站点" + direction.vehicleInfo.nextNumber + "站（" + distance + ")");
                }
                nextBusTime.setText("");
            } else {
                nextBusLabel.setText("下一班发车时间");
                busDistance.setText("");
                if (direction.planTime != null && !direction.planTime.isEmpty()) {
                    nextBusTime.setText(direction.planTime);
                } else {
                    nextBusLabel.setText("暂无车辆信息");
                }
            }

            if (!isGrayed) {
                busInfoContainer.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Intent intent = new Intent(itemView.getContext(), BusLineDetailActivity.class);
                        intent.putExtra("line_id", direction.lineId);
                        intent.putExtra("line_name", direction.lineName);
                        intent.putExtra("start_station", direction.startStation);
                        intent.putExtra("end_station", direction.endStation);
                        intent.putExtra("station_id", direction.stationId);
                        itemView.getContext().startActivity(intent);
                    }
                });

                busInfoContainer.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View view) {
                        if (listener != null) {
                            listener.onDirectionLongClick(direction, busInfoContainer);
                        }
                        return true;
                    }
                });
            } else {
                busInfoContainer.setOnClickListener(null);
                busInfoContainer.setOnLongClickListener(null);
            }
        }

        private String formatGpsTime(String gpsTime) {
            try {
                SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                SimpleDateFormat outputFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
                Date date = inputFormat.parse(gpsTime);
                return outputFormat.format(date);
            } catch (ParseException e) {
                return gpsTime.length() > 5 ? gpsTime.substring(11, 16) : gpsTime;
            }
        }
    }
}