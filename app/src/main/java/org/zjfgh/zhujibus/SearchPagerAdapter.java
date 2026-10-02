package org.zjfgh.zhujibus;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import java.util.List;

public class SearchPagerAdapter extends FragmentStateAdapter {
    /** 搜索页的 Tab 类型。地点分类(PlaceFragment)已在 UI 中移除，不再出现。 */
    public enum Tab {
        LINE,      // 线路
        STATION    // 站点（仅诸暨可用，非诸暨不加入列表）
    }

    private final List<Tab> tabs;
    private final Fragment[] fragments;

    public SearchPagerAdapter(@NonNull FragmentActivity fragmentActivity, @NonNull List<Tab> tabs) {
        super(fragmentActivity);
        this.tabs = tabs;
        this.fragments = new Fragment[tabs.size()];
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        Fragment f;
        switch (tabs.get(position)) {
            case LINE:
                f = new LineFragment();
                break;
            case STATION:
                f = new StationFragment();
                break;
            default:
                throw new IllegalArgumentException("Invalid tab: " + tabs.get(position));
        }
        fragments[position] = f;
        return f;
    }

    public Fragment getFragment(int position) {
        return fragments[position];
    }

    @Override
    public int getItemCount() {
        return tabs.size();
    }
}
