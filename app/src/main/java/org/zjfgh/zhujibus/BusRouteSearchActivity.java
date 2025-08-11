package org.zjfgh.zhujibus;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import org.zjfgh.zhujibus.databinding.ActivityBusRouteSearchBinding;

public class BusRouteSearchActivity extends AppCompatActivity {
    private EditText edSearchBus;
    private ViewPager2 viewPager;
    private SearchPagerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bus_route_search);
        // 初始化视图
        edSearchBus = findViewById(R.id.ed_search_bus);
        viewPager = findViewById(R.id.viewPager);
        TabLayout tabLayout = findViewById(R.id.tabLayout);
        // 设置适配器
        adapter = new SearchPagerAdapter(this);
        viewPager.setAdapter(adapter);
        viewPager.setUserInputEnabled(false);
        // 绑定 TabLayout 和 ViewPager2
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0:
                    tab.setText("线路");
                    break;
                case 1:
                    tab.setText("站点");
                    break;
                case 2:
                    tab.setText("地点");
                    break;
            }
        }).attach();

        // 可选：设置预加载页面数
        viewPager.setOffscreenPageLimit(2);

        // 可选：监听页面切换事件
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                performSearch(edSearchBus.getText().toString().trim());
            }
        });
        edSearchBus.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                performSearch(s.toString().trim());
            }
        });

    }

    private void performSearch(String keyword) {
        // 获取当前显示的Fragment
        Fragment currentFragment = adapter.getFragment(viewPager.getCurrentItem());

        // 根据当前Tab执行不同的搜索逻辑
        switch (viewPager.getCurrentItem()) {
            case 0: // 线路
                if (currentFragment instanceof LineFragment) {
                    ((LineFragment) currentFragment).searchLines(keyword);
                }
                break;
            case 1: // 站点
                if (currentFragment instanceof StationFragment) {
                    ((StationFragment) currentFragment).searchStations(keyword);
                }
                break;
            case 2: // 地点
                if (currentFragment instanceof PlaceFragment) {
                    ((PlaceFragment) currentFragment).searchPlaces(keyword);
                }
                break;
        }
    }
}