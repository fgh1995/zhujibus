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
    /** 卡片仍显示"上一轮数据"时的透明度：淡化显示，和已刷新的卡片形成对比 */
    private static final float STALE_ALPHA = 0.5f;

    private List<BusApiClient.StationLineInfo> busLineItems = new ArrayList<>();
    // 键 lineId_stationId：数据还没更新到本轮（仍是上一轮内容）的线路，卡片整体淡化
    private Set<String> staleLineKeys = new HashSet<>();
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

    /**
     * 只刷新某一条线路卡片：标记模式下车辆/计划发车时间随各线路逐条返回，
     * 逐条刷新即可，避免整表重绑（闪烁、方向翻页被重置）。
     */
    public void notifyLineChanged(int position) {
        if (position >= 0 && position < busLineItems.size()) {
            notifyItemChanged(position);
        }
    }

    /**
     * 按目标顺序重排列表，做出"动态榜单"效果：
     * 逐项用 {@link #notifyItemMoved} 把条目挪到目标位置，交由 RecyclerView 的
     * ItemAnimator 播放移动动画 —— 名次上升的卡片会滑动到前面，被挤下去的卡片顺次后移，
     * 而不是整表刷新覆盖（那样看不到交换过程）。
     * <p>
     * 本方法只负责"换位"（列表顺序本身就变成目标顺序），内容刷新由调用方再调
     * {@link #notifyLineChanged(int)}，避免移动动画被内容刷新的重绑打断。
     */
    public void applyOrder(List<BusApiClient.StationLineInfo> sortedItems) {
        if (sortedItems == null || sortedItems.isEmpty() || busLineItems.isEmpty()) return;
        int size = Math.min(sortedItems.size(), busLineItems.size());
        for (int target = 0; target < size; target++) {
            BusApiClient.StationLineInfo item = sortedItems.get(target);
            int current = busLineItems.indexOf(item);
            if (current < 0 || current == target) continue;
            busLineItems.remove(current);
            busLineItems.add(target, item);
            notifyItemMoved(current, target);
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
        if (lineIds == null || stationIds == null) return;
        for (int i = 0; i < childAdapters.size(); i++) {
            DirectionPagerAdapter adapter = childAdapters.get(i);
            BusApiClient.StationLineInfo lineInfo = busLineItems.get(i);
            if (adapter == null || lineInfo == null || lineInfo.getDirections() == null) continue;
            for (int j = 0; j < lineInfo.getDirections().size(); j++) {
                BusApiClient.LineDirection direction = lineInfo.getDirections().get(j);
                if (direction == null) continue;
                // 按 (lineId, stationId) 同索引成对匹配，避免同一 lineId 出现在多个方向时切错页
                boolean matched = false;
                for (int k = 0; k < lineIds.size() && k < stationIds.size(); k++) {
                    if (lineIds.get(k).equals(direction.lineId)
                            && stationIds.get(k).equals(direction.stationId)) {
                        matched = true;
                        break;
                    }
                }
                if (matched) {
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
        BusApiClient.StationLineInfo item = busLineItems.get(position);
        // 仍是上一轮数据的卡片整体淡化：新数据到了就恢复正常，方便一眼看出哪些还在更新
        holder.itemView.setAlpha(isStaleItem(item) ? STALE_ALPHA : 1f);
        holder.bind(item, directionLongClickListener, position);
    }

    /**
     * 标记"当前展示的还是上一轮数据"的线路（键 lineId_stationId）。
     * 传入的是同一个集合引用，所以集合内容变化后调 {@link #notifyAllLinesChanged()} 即可生效。
     */
    public void setStaleLineKeys(Set<String> keys) {
        this.staleLineKeys = keys != null ? keys : new HashSet<>();
    }

    /** 整表重绑一次（数据本身没变，只是让淡化状态生效） */
    public void notifyAllLinesChanged() {
        if (!busLineItems.isEmpty()) {
            notifyItemRangeChanged(0, busLineItems.size());
        }
    }

    private boolean isStaleItem(BusApiClient.StationLineInfo item) {
        if (item == null || staleLineKeys.isEmpty()) return false;
        BusApiClient.LineDirection dir = item.up != null ? item.up : item.down;
        if (dir == null || dir.lineId == null) return false;
        return staleLineKeys.contains(dir.lineId + "_" + dir.stationId);
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