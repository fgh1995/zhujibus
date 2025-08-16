package org.zjfgh.zhujibus;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.List;

public class BusStationAdapter extends RecyclerView.Adapter<BusStationAdapter.BusLineViewHolder> {
    private List<BusApiClient.StationLineInfo> busLineItems = new ArrayList<>();

    public void setData(List<BusApiClient.StationLineInfo> busLineItems) {
        this.busLineItems = busLineItems;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public BusLineViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bus_line_station, parent, false);
        return new BusLineViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BusLineViewHolder holder, int position) {
        holder.bind(busLineItems.get(position));
    }

    @Override
    public int getItemCount() {
        return busLineItems.size();
    }

    static class BusLineViewHolder extends RecyclerView.ViewHolder {
        private ViewPager2 directionViewPager;
        private LinearLayout indicatorContainer;

        public BusLineViewHolder(@NonNull View itemView) {
            super(itemView);
            directionViewPager = itemView.findViewById(R.id.view_pager);
            indicatorContainer = itemView.findViewById(R.id.indicator_container);
        }

        public void bind(BusApiClient.StationLineInfo busLineItem) {
            // 设置指示器
            setupIndicators(busLineItem.getDirections().size());

            // 创建adapter并传入数据
            DirectionPagerAdapter adapter = new DirectionPagerAdapter(
                    itemView.getContext(),
                    busLineItem.getDirections()
            );

            directionViewPager.setAdapter(adapter);
            directionViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    super.onPageSelected(position);
                    updateIndicators(position);
                }
            });
        }

        private void setupIndicators(int count) {
            indicatorContainer.removeAllViews();

            for (int i = 0; i < count; i++) {
                ImageView indicator = new ImageView(itemView.getContext());
                indicator.setImageResource(R.drawable.indicator_selector);

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        dpToPx(8), dpToPx(8));
                params.setMargins(dpToPx(4), 0, dpToPx(4), 0);

                indicator.setLayoutParams(params);
                indicatorContainer.addView(indicator);
            }

            updateIndicators(0);
        }

        private void updateIndicators(int position) {
            for (int i = 0; i < indicatorContainer.getChildCount(); i++) {
                ImageView indicator = (ImageView) indicatorContainer.getChildAt(i);
                indicator.setSelected(i == position);
            }
        }

        private int dpToPx(int dp) {
            return (int) (dp * itemView.getContext().getResources().getDisplayMetrics().density);
        }
    }
}
