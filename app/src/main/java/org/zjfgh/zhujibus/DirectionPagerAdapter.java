package org.zjfgh.zhujibus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class DirectionPagerAdapter extends RecyclerView.Adapter<DirectionPagerAdapter.DirectionViewHolder> {
    private final Context context;
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
        holder.bindData(directions.get(position));
    }

    @Override
    public int getItemCount() {
        return directions.size();
    }

    // ViewHolder 类
    static class DirectionViewHolder extends RecyclerView.ViewHolder {
        private final TextView busStations;
        private final TextView busRouteName;
        private final TextView departureTime;
        private final TextView collectTime;
        private final TextView nextBusTime;
        private final TextView nextBusLabel;
        private final TextView busdistance;

        public DirectionViewHolder(@NonNull View itemView) {
            super(itemView);
            busStations = itemView.findViewById(R.id.bus_stations);
            busRouteName = itemView.findViewById(R.id.bus_route_name);
            departureTime = itemView.findViewById(R.id.first_bus_time);
            collectTime = itemView.findViewById(R.id.last_bus_time);
            nextBusTime = itemView.findViewById(R.id.next_bus_time);
            nextBusTime.setVisibility(View.INVISIBLE);
            nextBusLabel = itemView.findViewById(R.id.next_bus_label);
            busdistance = itemView.findViewById(R.id.bus_distance);
            busdistance.setVisibility(View.GONE);
        }

        @SuppressLint("SetTextI18n")
        public void bindData(BusApiClient.LineDirection direction) {
            if (direction != null) {
                Log.w("busStations", busStations + "|" + direction.startStation + " - " + direction.endStation);
                busRouteName.setText(direction.lineName);
                busStations.setText(direction.startStation + " - " + direction.endStation);
                departureTime.setText(direction.departureTime);
                collectTime.setText(direction.collectTime);
            }
        }
    }
}