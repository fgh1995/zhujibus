package org.zjfgh.zhujibus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;

import java.util.List;

public class DirectionPagerAdapter extends PagerAdapter {
    private final Context context;
    private List<BusApiClient.LineDirection> directions;

    public DirectionPagerAdapter(Context context, List<BusApiClient.LineDirection> directions) {
        this.context = context;
        this.directions = directions;
    }

    @Override
    public int getCount() {
        return directions.size();
    }

    @Override
    public int getItemPosition(@NonNull Object object) {
        // 强制每次数据变化时都重新创建所有页面
        return POSITION_NONE;
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        return view == object;
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_bus_direction_station, container, false);
        Log.d("DirectionPagerAdapter", "Creating view for position: " + position + "view" + view);
        bindData(view, directions.get(position));
        container.addView(view);
        return view;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        container.removeView((View) object);
    }

    @SuppressLint("SetTextI18n")
    private void bindData(View view, BusApiClient.LineDirection direction) {
        TextView busStations = view.findViewById(R.id.bus_stations);
        TextView busRouteName = view.findViewById(R.id.bus_route_name);
        if (direction != null) {
            Log.w("busStations", busStations + "|" + direction.startStation + " - " + direction.endStation);
            busRouteName.setText(direction.lineName);
            busStations.setText(direction.startStation + " - " + direction.endStation);
        }

        //tvPrice.setText(String.format(Locale.getDefault(), "¥%.1f", direction.price));
    }

    // 添加更新数据的方法
    public void updateDirections(List<BusApiClient.LineDirection> newDirections) {
        this.directions = newDirections;
        notifyDataSetChanged();
    }
}
