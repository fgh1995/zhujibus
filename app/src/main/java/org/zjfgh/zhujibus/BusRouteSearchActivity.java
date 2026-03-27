package org.zjfgh.zhujibus;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class BusRouteSearchActivity extends AppCompatActivity {
    private EditText edSearchBusLine;
    private ViewPager2 viewPager;
    private SearchPagerAdapter adapter;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;
    private static final long DEBOUNCE_DELAY = 500; // 防抖延迟时间，单位毫秒
    private TextView tvTest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_bus_route_search);

            edSearchBusLine = findViewById(R.id.ed_search_bus);
            viewPager = findViewById(R.id.viewPager);
            TabLayout tabLayout = findViewById(R.id.tabLayout);
            tvTest = findViewById(R.id.tv_test);

            adapter = new SearchPagerAdapter(this);
            viewPager.setAdapter(adapter);
            viewPager.setUserInputEnabled(false);

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

            viewPager.setOffscreenPageLimit(2);

            viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    super.onPageSelected(position);
                    performSearchWithDebounce(edSearchBusLine.getText().toString().trim());
                }
            });

            edSearchBusLine.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    performSearchWithDebounce(s.toString().trim());
                }
            });

            tvTest.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startTestActivity();
                }
            });
        } catch (Exception e) {
            Log.e("BusRouteSearchActivity", "初始化失败", e);
        }
    }

    /**
     * 带防抖的搜索方法
     */
    private void performSearchWithDebounce(String keyword) {
        // 移除之前未执行的搜索任务
        if (searchRunnable != null) {
            handler.removeCallbacks(searchRunnable);
        }

        // 如果关键字为空，不执行搜索
        if (keyword.isEmpty()) {
            return;
        }

        // 创建新的搜索任务
        searchRunnable = new Runnable() {
            @Override
            public void run() {
                performSearch(keyword);
            }
        };

        // 延迟执行搜索任务
        handler.postDelayed(searchRunnable, DEBOUNCE_DELAY);
    }

    /**
     * 实际的搜索执行方法
     */
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

    /**
     * 启动测试活动
     */
    private void startTestActivity() {
        Intent intent = new Intent(this, BusLineDetailActivity.class);
        intent.putExtra("line_id", "test_line_001");
        intent.putExtra("line_name", "测试线路");
        intent.putExtra("start_station", "测试起点站");
        intent.putExtra("end_station", "测试终点站");
        intent.putExtra("station_id", "test_station_005");
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 移除所有回调，防止内存泄漏
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }
}