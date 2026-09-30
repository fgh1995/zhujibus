package org.zjfgh.zhujibus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工搜索地区结果列表适配器。
 */
public class RegionSearchAdapter extends RecyclerView.Adapter<RegionSearchAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(BusRegion region);
    }

    private final List<BusRegion> data = new ArrayList<>();
    private OnItemClickListener listener;

    public void setOnItemClickListener(OnItemClickListener l) {
        this.listener = l;
    }

    public void setRegions(List<BusRegion> regions) {
        data.clear();
        if (regions != null) {
            // 按 adCode 去重，保留第一个（同一个区县可能返回多条提示）
            Map<String, BusRegion> map = new LinkedHashMap<>();
            for (BusRegion r : regions) {
                if (r == null || r.adCode.isEmpty()) continue;
                map.putIfAbsent(r.adCode, r);
            }
            data.addAll(map.values());
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_region, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        BusRegion r = data.get(pos);
        h.name.setText(r.regionName);
        // 诸暨（adCode 330681）特例：副标题里的地级市"绍兴市"替换为省名"浙江省"，其余地区不处理
        String cityPart = r.cityName;
        if (r.adCode.startsWith("330681") && !cityPart.isEmpty()) {
            cityPart = "浙江省";
        }
        String sub;
        if (!cityPart.isEmpty() && !r.districtName.isEmpty() && !r.districtName.equals(cityPart)) {
            sub = cityPart + " · " + r.districtName;
        } else if (!r.districtName.isEmpty()) {
            sub = r.districtName;
        } else {
            sub = cityPart;
        }
        h.sub.setText(sub + "  (" + r.adCode + ")");
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(r);
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView name, sub;

        VH(View v) {
            super(v);
            name = v.findViewById(R.id.tv_region_name);
            sub = v.findViewById(R.id.tv_region_sub);
        }
    }
}
