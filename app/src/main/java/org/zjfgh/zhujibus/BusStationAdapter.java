package org.zjfgh.zhujibus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BusStationAdapter extends RecyclerView.Adapter<BusStationAdapter.BusLineViewHolder> {
    private List<BusApiClient.StationLineInfo> busLineItems = new ArrayList<>();
    private List<DirectionPagerAdapter> childAdapters = new ArrayList<>();
    private DirectionPagerAdapter.OnDirectionLongClickListener directionLongClickListener;
    private Map<Integer, ViewPager2> viewPagerMap = new HashMap<>();

    private List<String> pendingHighlightLineIds;
    private List<String> pendingHighlightStationIds;
    private List<String> pendingGrayLineIds;
    private List<String> pendingGrayStationIds;
    private boolean hasPendingHighlight = false;
    private boolean hasPendingGray = false;

    public void setOnDirectionLongClickListener(DirectionPagerAdapter.OnDirectionLongClickListener listener) {
        this.directionLongClickListener = listener;
    }

    public void setData(List<BusApiClient.StationLineInfo> busLineItems) {
        this.busLineItems = busLineItems;
        this.childAdapters.clear();
        notifyDataSetChanged();

        if (hasPendingHighlight) {
            setHighlightedLinesInternal(pendingHighlightLineIds, pendingHighlightStationIds);
        }
        if (hasPendingGray) {
            setGrayedLinesInternal(pendingGrayLineIds, pendingGrayStationIds);
        }
    }

    public void setHighlightedLines(List<String> lineIds, List<String> stationIds) {
        this.pendingHighlightLineIds = lineIds != null ? new ArrayList<>(lineIds) : new ArrayList<>();
        this.pendingHighlightStationIds = stationIds != null ? new ArrayList<>(stationIds) : new ArrayList<>();
        this.hasPendingHighlight = true;
        setHighlightedLinesInternal(lineIds, stationIds);
    }

    private void setHighlightedLinesInternal(List<String> lineIds, List<String> stationIds) {
        for (DirectionPagerAdapter adapter : childAdapters) {
            if (adapter != null) {
                adapter.setHighlightedLines(lineIds, stationIds);
            }
        }
    }

    public void setGrayedLines(List<String> lineIds, List<String> stationIds) {
        this.pendingGrayLineIds = lineIds != null ? new ArrayList<>(lineIds) : new ArrayList<>();
        this.pendingGrayStationIds = stationIds != null ? new ArrayList<>(stationIds) : new ArrayList<>();
        this.hasPendingGray = true;
        setGrayedLinesInternal(lineIds, stationIds);
    }

    private void setGrayedLinesInternal(List<String> lineIds, List<String> stationIds) {
        for (DirectionPagerAdapter adapter : childAdapters) {
            if (adapter != null) {
                adapter.setGrayedLines(lineIds, stationIds);
            }
        }
    }

    public void switchToMatchingDirection(List<String> lineIds, List<String> stationIds) {
        for (int i = 0; i < childAdapters.size(); i++) {
            DirectionPagerAdapter adapter = childAdapters.get(i);
            BusApiClient.StationLineInfo lineInfo = busLineItems.get(i);
            if (adapter == null || lineInfo == null || lineInfo.getDirections() == null) continue;
            for (int j = 0; j < lineInfo.getDirections().size(); j++) {
                BusApiClient.LineDirection direction = lineInfo.getDirections().get(j);
                if (direction == null) continue;
                if (lineIds.contains(direction.lineId) && stationIds.contains(direction.stationId)) {
                    ViewPager2 viewPager = viewPagerMap.get(i);
                    if (viewPager != null && viewPager.getCurrentItem() != j) {
                        viewPager.setCurrentItem(j, true);
                    }
                    break;
                }
            }
        }
    }

    public void clearHighlightAndGray() {
        hasPendingHighlight = false;
        hasPendingGray = false;
        pendingHighlightLineIds = null;
        pendingHighlightStationIds = null;
        pendingGrayLineIds = null;
        pendingGrayStationIds = null;
        for (DirectionPagerAdapter adapter : childAdapters) {
            if (adapter != null) {
                adapter.clearHighlightAndGray();
            }
        }
    }

    public void resetAllViewPagersToZero() {
        for (int i = 0; i < childAdapters.size(); i++) {
            ViewPager2 viewPager = viewPagerMap.get(i);
            if (viewPager != null && viewPager.getCurrentItem() != 0) {
                viewPager.setCurrentItem(0, false);
            }
        }
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
        holder.bind(busLineItems.get(position), directionLongClickListener, position);
    }

    @Override
    public void onViewRecycled(@NonNull BusLineViewHolder holder) {
        super.onViewRecycled(holder);
        int pos = holder.getAdapterPosition();
        if (pos >= 0 && pos < childAdapters.size()) {
            childAdapters.set(pos, null);
        }
        if (pos >= 0) {
            viewPagerMap.remove(pos);
        }
    }

    @Override
    public int getItemCount() {
        return busLineItems.size();
    }

    class BusLineViewHolder extends RecyclerView.ViewHolder {
        private ViewPager2 directionViewPager;
        private LinearLayout indicatorContainer;
        private int adapterPosition = -1;

        public BusLineViewHolder(@NonNull View itemView) {
            super(itemView);
            directionViewPager = itemView.findViewById(R.id.view_pager);
            indicatorContainer = itemView.findViewById(R.id.indicator_container);
        }

        public void bind(BusApiClient.StationLineInfo busLineItem,
                        DirectionPagerAdapter.OnDirectionLongClickListener listener,
                        int position) {
            this.adapterPosition = position;
            setupIndicators(busLineItem.getDirections().size());

            DirectionPagerAdapter adapter = new DirectionPagerAdapter(
                    itemView.getContext(),
                    busLineItem.getDirections()
            );
            adapter.setOnDirectionLongClickListener(listener);

            if (hasPendingHighlight) {
                adapter.setHighlightedLines(pendingHighlightLineIds, pendingHighlightStationIds);
            }
            if (hasPendingGray) {
                adapter.setGrayedLines(pendingGrayLineIds, pendingGrayStationIds);
            }

            while (childAdapters.size() <= position) {
                childAdapters.add(null);
            }
            childAdapters.set(position, adapter);
            viewPagerMap.put(position, directionViewPager);

            directionViewPager.setAdapter(adapter);
            directionViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int pagePosition) {
                    super.onPageSelected(pagePosition);
                    updateIndicators(pagePosition);
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