package org.zjfgh.zhujibus;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.navigation.ui.AppBarConfiguration;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;


import java.util.ArrayList;
import java.util.List;

public class BusLineDetailActivity extends AppCompatActivity {
    
    private String lineName;
    private String startStation;
    private String endStation;
    private BusApiClient busApiClient;
    private TextView routeNumber;
    private TextView noticeText;
    private LinearLayout swapOrientation;
    private LinearLayout accessibilityTag;

    // 当前显示的方向 (1:上行, 2:下行)
    private int currentDirection = 1;
    // 线路是否有双向
    private boolean isTwoWayLine = false;
    // 缓存线路数据
    private BusApiClient.BusLineDetailResponse cachedResponse;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bus_line_details);
        Intent intent = getIntent();
        if (intent != null) {
            lineName = intent.getStringExtra("line_name");
            startStation = intent.getStringExtra("start_station");
            endStation = intent.getStringExtra("end_station");
            routeNumber = findViewById(R.id.route_number);
            routeNumber.setText(lineName);
            LinearLayout noticeBar = findViewById(R.id.notice_bar);
            noticeText = findViewById(R.id.notice_text);
            noticeBar.setVisibility(View.GONE);
            TextView startStationName = findViewById(R.id.start_station_name);
            startStationName.setText(startStation);
            TextView endStationName = findViewById(R.id.end_station_name);
            endStationName.setText(endStation);
            swapOrientation = findViewById(R.id.swap_orientation);
            swapOrientation.setVisibility(View.GONE);
            // 设置切换方向按钮的点击事件
            swapOrientation.setOnClickListener(v -> swapDirection());
            LinearLayout loopLineTag = findViewById(R.id.loop_line_tag);
            loopLineTag.setVisibility(View.GONE);
            accessibilityTag = findViewById(R.id.accessibility_tag);
            accessibilityTag.setVisibility(View.GONE);

            // 数据验证
            if (lineName == null) {
                Toast.makeText(this, "线路信息获取失败", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                busApiClient = new BusApiClient();

                // 先查询线路通知
                busApiClient.queryLineNotification(lineName, new BusApiClient.ApiCallback<>() {
                    @Override
                    public void onSuccess(BusApiClient.LineNotificationResponse response) {
                        if (response == null || response.data == null) {
                            Log.e("BusInfo", "通知响应数据为空");
                            return;
                        }
                        if (!"200".equals(response.code)) {
                            Log.e("BusInfo", "通知请求失败：" + response.code);
                            return;
                        }
                        if (response.data.hasNotification) {
                            runOnUiThread(() -> {
                                noticeBar.setVisibility(View.VISIBLE);
                                noticeText.setText(HtmlParser.htmlToFormattedText(response.data.text));
                            });
                        }
                    }

                    @Override
                    public void onError(BusApiClient.BusApiException e) {
                        Log.e("API Error", "获取通知失败: " + e.getMessage());
                    }
                });

                // 查询公交线路详情（获取双向数据）
                busApiClient.queryBusLineDetail(lineName, 1, new BusApiClient.ApiCallback<>() { // 0表示获取双向数据
                    @Override
                    public void onSuccess(BusApiClient.BusLineDetailResponse response) {
                        // 缓存响应数据
                        cachedResponse = response;

                        // 安全处理公交线路数据
                        if (response == null || response.data == null) {
                            Log.e("BusInfo", "线路响应数据为空");
                            runOnUiThread(() ->
                                    Toast.makeText(BusLineDetailActivity.this, "线路数据异常", Toast.LENGTH_SHORT).show());
                            return;
                        }
                        if (!"200".equals(response.code)) {
                            Log.e("BusInfo", "线路请求失败：" + response.code);
                            runOnUiThread(() ->
                                    Toast.makeText(BusLineDetailActivity.this, "获取线路数据失败", Toast.LENGTH_SHORT).show());
                            return;
                        }

                        // 检查是否是双向线路
                        isTwoWayLine = (response.data.up != null && response.data.down != null);
                        runOnUiThread(() -> {
                            if (isTwoWayLine) {
                                // 双向线路，显示切换按钮
                                swapOrientation.setVisibility(View.VISIBLE);
                                // 默认显示上行方向
                                currentDirection = 1;
                                showDirection(currentDirection);
                            } else if (response.data.up != null) {
                                // 只有上行方向
                                currentDirection = 1;
                                showDirection(currentDirection);
                                loopLineTag.setVisibility(View.VISIBLE);
                            } else if (response.data.down != null) {
                                // 只有下行方向
                                currentDirection = 2;
                                showDirection(currentDirection);
                                loopLineTag.setVisibility(View.VISIBLE);
                            } else {
                                // 没有方向数据
                                Log.e("BusInfo", "线路无方向数据");
                                Toast.makeText(BusLineDetailActivity.this, "线路数据异常", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void onError(BusApiClient.BusApiException e) {
                        Log.e("API Error", "获取线路失败: " + e.getMessage());
                        runOnUiThread(() ->
                                Toast.makeText(BusLineDetailActivity.this, "获取线路数据失败", Toast.LENGTH_SHORT).show());
                    }
                });
            }
        }
    }

    /**
     * 切换线路方向
     */
    private void swapDirection() {
        if (!isTwoWayLine) return;

        // 切换方向
        currentDirection = (currentDirection == 1) ? 2 : 1;
        Log.d("BusInfo", "切换方向到: " + (currentDirection == 1 ? "上行" : "下行"));

        // 使用缓存数据显示新方向
        showDirection(currentDirection);
    }

    private List<BusApiClient.BusLineStation> stationData = new ArrayList<>();

    /**
     * 显示指定方向的线路数据（使用缓存数据）
     *
     * @param direction 方向 (1:上行, 2:下行)
     */
    @SuppressLint("SetTextI18n")
    private void showDirection(int direction) {
        if (cachedResponse == null || cachedResponse.data == null) {
            Log.e("BusInfo", "显示方向失败: 缓存数据为空");
            return;
        }

        BusApiClient.BusLineDirection lineDirection = null;
        String directionName = "";

        if (direction == 1 && cachedResponse.data.up != null) {
            lineDirection = cachedResponse.data.up;
            directionName = "上行";
        } else if (direction == 2 && cachedResponse.data.down != null) {
            lineDirection = cachedResponse.data.down;
            directionName = "下行";
        }

        if (lineDirection != null) {
            Log.d("BusInfo", "显示" + directionName + "方向: " +
                    (lineDirection.startStation != null ? lineDirection.startStation : "未知起点") +
                    " → " +
                    (lineDirection.endStation != null ? lineDirection.endStation : "未知终点"));

            if (lineDirection.hasCj == 1) {
                accessibilityTag.setVisibility(View.VISIBLE);
            }
            TextView firstBusTime = findViewById(R.id.first_bus_time);
            TextView lastBusTime = findViewById(R.id.last_bus_time);
            firstBusTime.setText(lineDirection.startFirst);
            lastBusTime.setText(lineDirection.startLast);
            TextView routeSummary = findViewById(R.id.route_summary);
            routeSummary.setText("总里程：" + lineDirection.lineLength + " 公里  票价：" + formatPrice(lineDirection.totalPrice) + " 元");
            // 处理站点列表
            if (lineDirection.stationList != null) {
                // 设置RecyclerView
                RecyclerView stationListRecyclerView = findViewById(R.id.station_list);
                stationListRecyclerView.setLayoutManager(new LinearLayoutManager(this));

                // 创建并设置适配器
                StationAdapter adapter = new StationAdapter(lineDirection.stationList, station -> {
                    // 处理站点点击事件
                    //showStationDetails(station);
                });
                stationListRecyclerView.setAdapter(adapter);
                // 添加装饰器（可选）
                stationListRecyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
                for (BusApiClient.BusLineStation station : lineDirection.stationList) {
                    if (station != null) {
                        stationData.add(station);
                        String stationName = station.stationName != null ? station.stationName : "未知站点";
                        Log.d("BusInfo", station.stationOrder + ". " + stationName);
                    }
                }
                // TODO: 这里可以更新UI显示站点列表
            } else {
                Log.w("BusInfo", directionName + "方向无站点数据");
            }

        } else {
            Log.e("BusInfo", "无法显示" + (direction == 1 ? "上行" : "下行") + "方向: 数据为空");
        }
    }

    /**
     * 格式化double类型的票价显示
     *
     * @param price 原始价格值
     * @return 格式化后的价格字符串
     */
    @SuppressLint("DefaultLocale")
    private String formatPrice(double price) {
        // 检查是否为整数
        if (price == (long) price) {
            return String.format("%d", (long) price); // 整数形式，如5.0 → "5"
        } else {
            // 先格式化为两位小数，然后去除不必要的0
            String formatted = String.format("%.2f", price);
            // 去除末尾的0和小数点(如果有)
            formatted = formatted.replaceAll("0*$", "").replaceAll("\\.$", "");
            return formatted; // 如4.5 → "4.5", 2.75 → "2.75"
        }
    }

    /**
     * 重载方法，处理可能为null的Double对象
     *
     * @param price Double对象
     * @return 格式化后的价格字符串，"未知"或"免费"等特殊情况需在调用前处理
     */
    private String formatPrice(Double price) {
        if (price == null) {
            return "未知";
        }
        return formatPrice(price.doubleValue());
    }
}