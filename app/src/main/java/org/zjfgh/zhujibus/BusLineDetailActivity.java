package org.zjfgh.zhujibus;

import android.content.Intent;
import android.os.Bundle;

import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import org.zjfgh.zhujibus.databinding.ActivityBusLineDetailBinding;

import java.util.Objects;

public class BusLineDetailActivity extends AppCompatActivity {

    private AppBarConfiguration appBarConfiguration;
    private ActivityBusLineDetailBinding binding;
    private String lineName;
    private String startStation;
    private String endStation;
    private BusApiClient busApiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityBusLineDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_bus_line_detail);
        appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
        Intent intent = getIntent();
        if (intent != null) {
            lineName = intent.getStringExtra("line_name");
            startStation = intent.getStringExtra("start_station");
            endStation = intent.getStringExtra("end_station");

            // 可选：添加数据验证
            if (lineName == null) {
                Toast.makeText(this, "线路信息获取失败", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                busApiClient = new BusApiClient();
                // 查询18路公交线路详情
                busApiClient.queryBusLineDetail(lineName, 1, new BusApiClient.ApiCallback<BusApiClient.BusLineDetailResponse>() {
                    @Override
                    public void onSuccess(BusApiClient.BusLineDetailResponse response) {
                        // 安全处理公交线路数据
                        if (response == null || response.data == null) {
                            Log.e("BusInfo", "响应数据为空");
                            return;
                        }
                        if (!"200".equals(response.code)) {
                            Log.e("BusInfo", "请求失败：状态码：" + response.code);
                            return;
                        }
                        // 处理上行方向（如果存在）
                        if (response.data.up != null) {
                            BusApiClient.BusLineDirection upDirection = response.data.up;

                            // 安全获取方向信息
                            String upStart = upDirection.startStation != null ? upDirection.startStation : "未知起点";
                            String upEnd = upDirection.endStation != null ? upDirection.endStation : "未知终点";

                            Log.d("BusInfo", "上行方向: " + upStart + " → " + upEnd);

                            // 遍历站点（如果存在）
                            if (upDirection.stationList != null) {
                                for (BusApiClient.BusLineStation station : upDirection.stationList) {
                                    if (station != null) {
                                        String stationName = station.stationName != null ? station.stationName : "未知站点";
                                        Log.d("BusInfo", station.stationOrder + ". " + stationName);
                                    }
                                }
                            } else {
                                Log.w("BusInfo", "上行方向无站点数据");
                            }
                        } else {
                            Log.w("BusInfo", "无上行方向数据");
                        }

                        // 处理下行方向（如果存在）
                        if (response.data.down != null) {
                            BusApiClient.BusLineDirection downDirection = response.data.down;

                            // 安全获取方向信息
                            String downStart = downDirection.startStation != null ? downDirection.startStation : "未知起点";
                            String downEnd = downDirection.endStation != null ? downDirection.endStation : "未知终点";

                            Log.d("BusInfo", "下行方向: " + downStart + " → " + downEnd);

                            // 遍历站点（如果存在）
                            if (downDirection.stationList != null) {
                                for (BusApiClient.BusLineStation station : downDirection.stationList) {
                                    if (station != null) {
                                        String stationName = station.stationName != null ? station.stationName : "未知站点";
                                        Log.d("BusInfo", station.stationOrder + ". " + stationName);
                                    }
                                }
                            } else {
                                Log.w("BusInfo", "下行方向无站点数据");
                            }
                        } else {
                            Log.w("BusInfo", "无下行方向数据");
                        }
                    }

                    @Override
                    public void onError(BusApiClient.BusApiException e) {
                        Log.e("API Error", Objects.requireNonNull(e.getMessage()));
                    }
                });
            }
        }
        binding.fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAnchorView(R.id.fab)
                        .setAction("Action", null).show();
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_bus_line_detail);
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }
}