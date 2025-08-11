package org.zjfgh.zhujibus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class StationRouteAdapter extends RecyclerView.Adapter<BaseViewHolder> {
    private static final int TYPE_STATION = 0;
    private static final int TYPE_ROUTE = 1;

    private List<Object> flattenedList;

    public StationRouteAdapter(List<StationItem> stationList) {
        this.flattenedList = flattenData(stationList);
    }

    // 将嵌套数据展平为一维列表
    private List<Object> flattenData(List<StationItem> stationList) {
        List<Object> result = new ArrayList<>();
        for (StationItem station : stationList) {
            result.add(station);
            result.addAll(station.getRoutes());
        }
        return result;
    }

    @Override
    public int getItemViewType(int position) {
        return flattenedList.get(position) instanceof StationItem ? TYPE_STATION : TYPE_ROUTE;
    }

    @NonNull
    @Override
    public BaseViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_STATION) {
            View view = inflater.inflate(R.layout.item_station, parent, false);
            return new StationViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_route, parent, false);
            return new RouteViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder holder, int position) {
        holder.bind(flattenedList.get(position));
    }

    @Override
    public int getItemCount() {
        return flattenedList.size();
    }
}
