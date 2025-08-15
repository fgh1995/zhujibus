package org.zjfgh.zhujibus;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private TextView tv_search_line;
    private ViewFlipper viewFlipper;
    private BusApiClient client;
    private double currentLatitude = 120.235555;
    private double currentLongitude = 29.713397;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        recyclerView = findViewById(R.id.recyclerView);
        tv_search_line = findViewById(R.id.tv_search_line);
        viewFlipper = findViewById(R.id.view_flipper);
        client = new BusApiClient();

        // 首次加载数据
        loadNearbyStations();

        TTSUtils.getInstance(this);

        tv_search_line.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, BusRouteSearchActivity.class);
            startActivity(intent);
        });
        loadAnnouncements();
    }

    // 加载附近站点的方法
    private void loadNearbyStations() {
        client.getNearbyStations(currentLongitude, currentLatitude, "2", 3, 5, new BusApiClient.ApiCallback<>() {
            @Override
            public void onSuccess(BusApiClient.StationLineAroundResponse response) {
                List<StationItem> stations = new ArrayList<>();
                for (int i = 0; i < response.data.size(); i++) {
                    List<RouteItem> routes1 = new ArrayList<>();
                    List<BusApiClient.DistanceData> distanceDataList = response.data.get(i).distanceData;
                    if (distanceDataList != null) {
                        for (int j = 0; j < response.data.get(i).distanceData.size(); j++) {
                            BusApiClient.DistanceData distanceData = response.data.get(i).distanceData.get(j);
                            routes1.add(new RouteItem(
                                    distanceData.lineName,
                                    "距离" + distanceData.nextNumber + "站/" +
                                            DistanceUtils.formatDistance(distanceData.distance),
                                    distanceData.startStation,
                                    distanceData.endStation,
                                    distanceData.arrivalTime
                            ));
                        }
                    }
                    stations.add(new StationItem(
                            response.data.get(i).stationName,
                            DistanceUtils.formatDistance(response.data.get(i).distance),
                            routes1
                    ));
                }

                runOnUiThread(() -> {
                    StationRouteAdapter adapter = new StationRouteAdapter(stations);
                    recyclerView.setLayoutManager(new LinearLayoutManager(MainActivity.this));
                    recyclerView.setAdapter(adapter);
                    recyclerView.addItemDecoration(new DividerItemDecoration(MainActivity.this, DividerItemDecoration.VERTICAL));

                    // 设置点击监听器
                    adapter.setOnItemClickListener(new StationRouteAdapter.OnItemClickListener() {
                        @Override
                        public void onStationClick(StationItem station) {
                            showStationDetailsDialog(station.getStationName());
                        }

                        @Override
                        public void onRouteClick(RouteItem route) {
                            //queryLineDetails(route.getLineId(), route.getLineName());
                        }
                    });
                });
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Toast.makeText(MainActivity.this, "获取附近站点失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // 显示站点详情对话框
    private void showStationDetailsDialog(String stationName) {
        StationDetailsFragment stationDetailsFragment = new StationDetailsFragment(stationName);
        stationDetailsFragment.show(getSupportFragmentManager(), "dialog_tag");
    }

    private void loadAnnouncements() {
        client.getBusAnnouncements(1, 6, new BusApiClient.ApiCallback<BusApiClient.BusAnnouncementResponse>() {
            @Override
            public void onSuccess(BusApiClient.BusAnnouncementResponse response) {
                if (response == null) {
                    Log.w("-BusInfo-", "滚动公告请求失败-无数据");
                    return;
                }
                if (!"200".equals(response.code)) {
                    Log.w("-BusInfo-", "滚动公告请求失败-状态码异常：" + response.code);
                    return;
                }
                for (BusApiClient.BusAnnouncement announcement : response.data) {
                    TextView textView = getTextView(announcement);
                    viewFlipper.addView(textView);
                }
                viewFlipper.startFlipping();
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Log.w("-BusInfo-", "滚动公告请求失败-异常：" + e.getMessage());
            }
        });
    }

    @NonNull
    private TextView getTextView(BusApiClient.BusAnnouncement announcement) {
        TextView textView = new TextView(MainActivity.this);
        textView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        textView.setText(announcement.title);
        textView.setTextSize(15);
        textView.setTextColor(Color.BLACK);
        textView.setGravity(Gravity.CENTER_VERTICAL);
        return textView;
    }

    @Override
    public void onResume() {
        super.onResume();
        viewFlipper.startFlipping();
        // 页面恢复时刷新数据
        loadNearbyStations();
    }

    @Override
    public void onPause() {
        super.onPause();
        viewFlipper.stopFlipping();
    }
}