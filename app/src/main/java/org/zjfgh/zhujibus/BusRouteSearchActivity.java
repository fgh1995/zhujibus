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
                    showTTSOptionsDialog();
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

    private void showTTSOptionsDialog() {
        String[] options = {
                "1. 单车到站（完整）",
                "2. 两车同时到（第二辆合并）",
                "3. 三车同时到（第二三辆合并）",
                "4. 线路详情播报（完整含方向）",
                "5. GPS-起点站报站",
                "6. GPS-中途站报站",
                "7. GPS-终点站报站",
                "8. GPS-离站报站(下一站非终点)",
                "9. GPS-离站报站(下一站是终点)"
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle("测试语音播报")
                .setItems(options, (dialog, which) -> {
                    TTSUtils tts = TTSUtils.getInstance(this);
                    switch (which) {
                        case 0:
                            tts.playArrivalAnnouncement("1路", "上海城", "小商品市场", "白门下村");
                            break;
                        case 1:
                            tts.playArrivalAnnouncement("1路", "上海城", "小商品市场", "白门下村");
                            tts.queueArrivalAnnouncement("2路", "跨湖新村", "小商品市场", "白门下村");
                            break;
                        case 2:
                            tts.playArrivalAnnouncement("1路", "跨湖新村", "小商品市场", "白门下村");
                            tts.queueArrivalAnnouncement("2路", "八方热电厂", "小商品市场", "白门下村");
                            tts.queueArrivalAnnouncement("3路", "跨湖新村", "小商品市场", "白门下村");
                            break;
                        case 3:
                            tts.playLineDetailAnnouncement("1路", "小商品市场", "跨湖新村", "白门下村");
                            break;
                        case 4:
                            tts.playGpsStartStationAnnouncement("1路", "小商品市场", "上海城", "白门下村");
                            break;
                        case 5:
                            tts.playGpsMiddleStationAnnouncement("白门下村");
                            break;
                        case 6:
                            tts.playGpsTerminalStationAnnouncement("上海城");
                            break;
                        case 7:
                            tts.playGpsLeavingStationAnnouncement("跨湖新村", false);
                            break;
                        case 8:
                            tts.playGpsLeavingStationAnnouncement("八方热电厂", true);
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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