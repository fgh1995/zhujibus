package org.zjfgh.zhujibus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DirectionPagerAdapter extends RecyclerView.Adapter<DirectionPagerAdapter.DirectionViewHolder> {
    private Context context;
    private List<BusApiClient.LineDirection> directions;

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
        holder.bind(direction);
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
        public void bind(BusApiClient.LineDirection direction) {
            // 设置线路名称和类型
            busRouteName.setText(String.format("%s(%s公交)", direction.lineName, direction.lineTypeName));

            // 设置起点和终点站
            busStations.setText(String.format("%s-%s", direction.startStation, direction.endStation));

            // 设置首末班车时间
            firstBusTime.setText(direction.departureTime);
            lastBusTime.setText(direction.collectTime);

            // 设置车辆动态信息
            if (direction.vehicleInfo != null) {
                nextBusLabel.setText("最近一班");
                // 显示距离信息
                if (direction.vehicleInfo.distance > 1000) {
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
                // 显示下一班车时间
                if (direction.planTime != null && !direction.planTime.isEmpty()) {
                    nextBusTime.setText(direction.planTime);
                }
            }
            busInfoContainer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    // 实现跳转到线路详情页的逻辑
                    Intent intent = new Intent(itemView.getContext(), BusLineDetailActivity.class);
                    intent.putExtra("line_id", direction.lineId);
                    intent.putExtra("line_name", direction.lineName);
                    intent.putExtra("start_station", direction.startStation);
                    intent.putExtra("end_station", direction.endStation);
                    intent.putExtra("station_id", direction.stationId); // 添加要跳转的站点ID
                    itemView.getContext().startActivity(intent);
                }
            });
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