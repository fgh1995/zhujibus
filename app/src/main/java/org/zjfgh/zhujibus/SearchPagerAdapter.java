package org.zjfgh.zhujibus;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class SearchPagerAdapter extends FragmentStateAdapter {
    private static final int TAB_COUNT = 3;
    private final Fragment[] fragments = new Fragment[TAB_COUNT];

    public SearchPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                fragments[0] = new LineFragment();
                return fragments[0];
            case 1:
                fragments[1] = new StationFragment();
                return fragments[1];
            case 2:
                fragments[2] = new PlaceFragment();
                return fragments[2];
            default:
                throw new IllegalArgumentException("Invalid position: " + position);
        }
    }

    public Fragment getFragment(int position) {
        return fragments[position];
    }

    @Override
    public int getItemCount() {
        return TAB_COUNT;
    }
}
