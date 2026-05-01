package org.zjfgh.zhujibus;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import io.sgr.geometry.Coordinate;
import io.sgr.geometry.utils.GeometryUtils;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;
import android.widget.Button;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
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
    private TextView tvNoData;
    private boolean locationObtained = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            recyclerView = findViewById(R.id.recyclerView);
            tv_search_line = findViewById(R.id.tv_search_line);
            viewFlipper = findViewById(R.id.view_flipper);
            tvNoData = findViewById(R.id.tv_no_data);
            client = new BusApiClient();
            if (PermissionUtils.hasLocationPermission(this)) {
                startGpsIfNeeded();
            } else {
                PermissionUtils.requestLocationPermission(this, new PermissionUtils.PermissionCallback() {
                    @Override
                    public void onPermissionGranted() {
                        runOnUiThread(() -> startGpsIfNeeded());
                    }

                    @Override
                    public void onPermissionDenied() {
                        runOnUiThread(() -> {
                            if (tvNoData != null) {
                                tvNoData.setText("需要位置权限才能获取附近站点~");
                            }
                        });
                    }
                });
            }
            TTSUtils.getInstance(this);
            tv_search_line.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(MainActivity.this, BusRouteSearchActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e("MainActivity", "跳转搜索页面失败", e);
                    Toast.makeText(MainActivity.this, "页面跳转失败", Toast.LENGTH_SHORT).show();
                }
            });
            loadAnnouncements();
        } catch (Exception e) {
            Log.e("MainActivity", "初始化失败", e);
            Toast.makeText(this, "应用初始化失败", Toast.LENGTH_LONG).show();
        }
    }

    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (!locationObtained) {
                locationObtained = true;
                Coordinate wgsCoord = new Coordinate(location.getLatitude(), location.getLongitude());
                Coordinate gcjCoord = GeometryUtils.wgs2gcj(wgsCoord);
                currentLatitude = gcjCoord.getLat();
                currentLongitude = gcjCoord.getLng();
                Log.d("MainActivity", String.format("GPS定位成功: WGS(%.6f,%.6f) -> GCJ(%.6f,%.6f)",
                        location.getLatitude(), location.getLongitude(), currentLatitude, currentLongitude));

                GpsWarmingUp.removeListener(this);
                GpsWarmingUp.stopWarmingUp();
                runOnUiThread(() -> loadNearbyStations());
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        GpsWarmingUp.removeListener(gpsListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (locationObtained) {
            runOnUiThread(() -> loadNearbyStations());
        } else if (PermissionUtils.hasLocationPermission(this)) {
            startGpsIfNeeded();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionUtils.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void startGpsIfNeeded() {
        if (!GpsWarmingUp.isWarmingUp()) {
            try {
                GpsWarmingUp.startWarmingUp(this);
                GpsWarmingUp.addListener(gpsListener);
            } catch (Exception e) {
                Log.e("MainActivity", "GPS初始化失败", e);
            }
        }
    }

    private void loadNearbyStations() {
        try {
            client.getNearbyStations(currentLongitude, currentLatitude, "2", 3, 5, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.StationLineAroundResponse response) {
                    try {
                        if (response == null || response.data == null) {
                            Log.w("MainActivity", "附近站点数据为空");
                            return;
                        }
                        List<StationItem> stations = new ArrayList<>();
                        for (int i = 0; i < response.data.size(); i++) {
                            BusApiClient.NearbyStationInfo stationInfo = response.data.get(i);
                            if (stationInfo == null) continue;

                            List<RouteItem> routes1 = new ArrayList<>();
                            List<BusApiClient.DistanceData> distanceDataList = stationInfo.distanceData;
                            if (distanceDataList != null) {
                                for (int j = 0; j < distanceDataList.size(); j++) {
                                    BusApiClient.DistanceData distanceData = distanceDataList.get(j);
                                    if (distanceData != null) {
                                        String distanceStr = (distanceData.nextNumber == -1 || distanceData.distance == -1)
                                                ? "" : "距离" + distanceData.nextNumber + "站/" + DistanceUtils.formatDistance(distanceData.distance);
                                        int arrivalTimeInt = (distanceData.arrivalTime <= 0) ? 0 : distanceData.arrivalTime;
                                        routes1.add(new RouteItem(
                                                distanceData.lineName,
                                                distanceStr,
                                                distanceData.startStation,
                                                distanceData.endStation,
                                                arrivalTimeInt
                                        ));
                                    }
                                }
                            }
                            stations.add(new StationItem(
                                    stationInfo.stationName,
                                    DistanceUtils.formatDistance(stationInfo.distance),
                                    routes1
                            ));
                        }

                        runOnUiThread(() -> {
                            try {
                                StationRouteAdapter adapter = new StationRouteAdapter(stations);
                                recyclerView.setLayoutManager(new LinearLayoutManager(MainActivity.this));
                                recyclerView.setAdapter(adapter);
                                recyclerView.addItemDecoration(new DividerItemDecoration(MainActivity.this, DividerItemDecoration.VERTICAL));
                                if (tvNoData != null) {
                                    tvNoData.setVisibility(View.GONE);
                                }

                                adapter.setOnItemClickListener(new StationRouteAdapter.OnItemClickListener() {
                                    @Override
                                    public void onStationClick(StationItem station) {
                                        try {
                                            showStationDetailsDialog(station.getStationName());
                                        } catch (Exception e) {
                                            Log.e("MainActivity", "显示站点详情失败", e);
                                        }
                                    }

                                    @Override
                                    public void onRouteClick(RouteItem route) {
                                    }
                                });
                            } catch (Exception e) {
                                Log.e("MainActivity", "更新UI失败", e);
                            }
                        });
                    } catch (Exception e) {
                        Log.e("MainActivity", "处理站点数据失败", e);
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e("MainActivity", "获取附近站点失败: " + e.getMessage(), e);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "获取附近站点失败", Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Exception e) {
            Log.e("MainActivity", "加载附近站点异常", e);
            Toast.makeText(this, "加载附近站点失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void showStationDetailsDialog(String stationName) {
        StationDetailsFragment stationDetailsFragment = new StationDetailsFragment(stationName);
        stationDetailsFragment.show(getSupportFragmentManager(), "dialog_tag");
    }

    private void loadAnnouncements() {
        try {
            client.getBusAnnouncements(1, 6, new BusApiClient.ApiCallback<BusApiClient.BusAnnouncementResponse>() {
                @Override
                public void onSuccess(BusApiClient.BusAnnouncementResponse response) {
                    try {
                        if (response == null) {
                            Log.w("-BusInfo-", "滚动公告请求失败-无数据");
                            return;
                        }
                        if (!"200".equals(response.code)) {
                            Log.w("-BusInfo-", "滚动公告请求失败-状态码异常：" + response.code);
                            return;
                        }
                        if (response.data == null || response.data.isEmpty()) {
                            Log.w("-BusInfo-", "滚动公告数据为空");
                            return;
                        }
                        for (BusApiClient.BusAnnouncement announcement : response.data) {
                            try {
                                TextView textView = getTextView(announcement);
                                viewFlipper.addView(textView);
                            } catch (Exception e) {
                                Log.e("-BusInfo-", "添加公告视图失败", e);
                            }
                        }
                        viewFlipper.startFlipping();
                    } catch (Exception e) {
                        Log.e("-BusInfo-", "处理公告数据失败", e);
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.w("-BusInfo-", "滚动公告请求失败-异常：" + e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e("MainActivity", "加载公告异常", e);
        }
    }

    @NonNull
    private TextView getTextView(BusApiClient.BusAnnouncement announcement) {
        TextView textView = new TextView(MainActivity.this);
        textView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        textView.setText(announcement.title);
        textView.setTextSize(15);
        textView.setTextColor(Color.parseColor("#000000"));
        textView.setGravity(Gravity.CENTER_VERTICAL);
        return textView;
    }
}