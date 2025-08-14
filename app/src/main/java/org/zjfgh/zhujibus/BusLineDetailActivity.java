package org.zjfgh.zhujibus;

import static com.google.android.material.internal.ViewUtils.dpToPx;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Handler;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.navigation.ui.AppBarConfiguration;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class BusLineDetailActivity extends AppCompatActivity implements BusRealTimeManager.RealTimeUpdateListener {

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
    private BusRealTimeManager realTimeManager;
    private Handler handler = new Handler();
    private RecyclerView stationListRecyclerView;
    private StationAdapter stationAdapter;
    private List<BusEtaItem> etaItems = new ArrayList<>();
    private BusEtaAdapter busEtaAdapter;
    private TextView scheduleButton;
    private static final String TAG = "BusLineDetailActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bus_line_details);
        Intent intent = getIntent();
        if (intent != null) {
            lineName = intent.getStringExtra("line_name");
            startStation = intent.getStringExtra("start_station");
            endStation = intent.getStringExtra("end_station");
            scheduleButton = findViewById(R.id.schedule_button);
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
                            Log.e(TAG + "-BusInfo-", "公告-无数据");
                            return;
                        }
                        if (!"200".equals(response.code)) {
                            Log.e(TAG + "-BusInfo-", "公告-状态码错误：" + response.code);
                            return;
                        }
                        if (response.data.hasNotification) {
                            runOnUiThread(() -> {
                                noticeBar.setVisibility(View.VISIBLE);
                                noticeText.setText(HtmlParser.htmlToFormattedText(response.data.text));
                                // 设置公告栏点击事件
                                noticeBar.setOnClickListener(v -> showFullNoticeDialog(response.data.text));
                            });
                        }
                    }

                    @Override
                    public void onError(BusApiClient.BusApiException e) {
                        Log.e(TAG + "-BusInfo-", "公告-网络请求失败：" + e.getMessage());
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
                            Log.e(TAG + "-BusInfo-", "公交线路-无数据");
                            return;
                        }
                        if (!"200".equals(response.code)) {
                            Log.e(TAG + "-BusInfo-", "公交线路-状态码错误：" + response.code);
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
                                Log.e(TAG + "-BusInfo-", "公交线路-没有方向数据：" + response.code);
                            }
                        });
                    }

                    @Override
                    public void onError(BusApiClient.BusApiException e) {
                        Log.e(TAG + "-BusInfo-", "获取公交线路失败");
                    }
                });
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

    }

    @Override
    protected void onPause() {
        super.onPause();
        // 停止获取实时数据
        realTimeManager.stopTracking();
    }

    // 显示完整公告的弹窗方法
    private void showFullNoticeDialog(String noticeContent) {
        // 1. 创建模态对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.ModalDialogTheme);
        // 2. 加载布局
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_notice, null);
        builder.setView(dialogView);

        // 3. 创建并显示对话框
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(true); // 点击外部不消失

        // 4. 处理HTML内容
        TextView noticeTextView = dialogView.findViewById(R.id.notice_content);
        Spanned html = Html.fromHtml(noticeContent, Html.FROM_HTML_MODE_COMPACT);
        noticeTextView.setText(html);
        // 6. 显示前配置窗口参数（优化显示效果）
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) {
                // 设置背景变暗效果
                window.setDimAmount(0.4f);
                // 设置宽度为屏幕的90%
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.copyFrom(window.getAttributes());
                lp.width = (int) (getResources().getDisplayMetrics().widthPixels);
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                window.setAttributes(lp);
            }
        });

        dialog.show();
    }

    /**
     * 切换线路方向
     */
    private void swapDirection() {
        if (!isTwoWayLine) return;
        // 切换方向
        currentDirection = (currentDirection == 1) ? 2 : 1;
        Log.e(TAG + "-BusInfo-", "切换方向到: " + (currentDirection == 1 ? "上行" : "下行"));
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
            Log.e(TAG + "-BusInfo-", "显示方向失败: 缓存数据为空");
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
            BusApiClient.BusLineDirection finalLineDirection = lineDirection;
            scheduleButton.setOnClickListener(v -> busApiClient.getBusLinePlanTime(finalLineDirection.id, new BusApiClient.ApiCallback<BusApiClient.BusLinePlanTimeResponse>() {
                @Override
                public void onSuccess(BusApiClient.BusLinePlanTimeResponse response) {
                    if (response == null || response.data == null) {
                        Log.e(TAG + "-BusInfo-", "时刻表-无数据");
                        return;
                    }
                    if (!"200".equals(response.code)) {
                        Log.e(TAG + "-BusInfo-", "时刻表-状态码错误：" + response.code);
                        return;
                    }
                    runOnUiThread(() -> showScheduleDialog(response.data));
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e(TAG + "-BusInfo-", "时刻表-请求失败：" + e.getMessage());
                }
            }));
            //停止之前的实时数据刷新
            if (realTimeManager != null) {
                realTimeManager.stopTracking();
            }
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
                stationListRecyclerView = findViewById(R.id.station_list);
                LinearLayoutManager layoutManager = new LinearLayoutManager(this) {
                    @Override
                    public boolean supportsPredictiveItemAnimations() {
                        return false;
                    }
                };
                stationListRecyclerView.setLayoutManager(layoutManager);
                // 创建并设置适配器
                stationAdapter = new StationAdapter(lineDirection.stationList, this::showStationDetails);
                stationListRecyclerView.setAdapter(stationAdapter);
                for (BusApiClient.BusLineStation station : lineDirection.stationList) {
                    if (station != null) {
                        stationData.add(station);
                    }
                }
                // TODO: 这里可以更新UI显示站点列表
                realTimeManager = new BusRealTimeManager(handler, lineDirection.stationList);
                realTimeManager.startTracking(lineDirection.id, this);
                RecyclerView rvLiveVehicles = findViewById(R.id.rv_live_vehicles);
                LinearLayoutManager rvLiveVehiclesLayoutManager = new LinearLayoutManager(
                        this,
                        LinearLayoutManager.HORIZONTAL,  // 关键设置
                        false
                );
                rvLiveVehicles.setLayoutManager(rvLiveVehiclesLayoutManager);
                busEtaAdapter = new BusEtaAdapter(etaItems, item -> {
                });
                rvLiveVehicles.setAdapter(busEtaAdapter);
            } else {
                Log.w(TAG + "-BusInfo-", directionName + "方向无站点数据");
            }

        } else {
            Log.w(TAG + "-BusInfo-", "无法显示" + (direction == 1 ? "上行" : "下行") + "方向: 数据为空");
        }
    }

    private void showScheduleDialog(List<String> scheduleTimes) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_bus_schedule, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView scheduleText = dialogView.findViewById(R.id.schedule_text);
        scheduleText.setTypeface(Typeface.MONOSPACE); // 等宽字体保证对齐

        StringBuilder formattedSchedule = new StringBuilder();
        int itemsPerRow = 5; // 每行4个时间点

        for (int i = 0; i < scheduleTimes.size(); i++) {
            // 关键修改：最后一个不加空格
            if ((i + 1) % itemsPerRow == 0 || i == scheduleTimes.size() - 1) {
                formattedSchedule.append(scheduleTimes.get(i)); // 行尾直接追加，不加空格
            } else {
                // 非行尾时间点：固定7字符宽度（左对齐）
                formattedSchedule.append(String.format("%-6s", scheduleTimes.get(i)));
            }

            // 换行逻辑
            if ((i + 1) % itemsPerRow == 0 && i != scheduleTimes.size() - 1) {
                formattedSchedule.append("\n\n");
            }
        }

        scheduleText.setText(formattedSchedule.toString());
        dialog.show();
    }

    private void showStationDetails(BusApiClient.BusLineStation station, int position) {
        stationAdapter.setSelectedPosition(position);
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

    private int lastSelectedStationIndex;

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

    @Override
    public void onBusPositionsUpdated(List<BusApiClient.BusPosition> positions) {
        runOnUiThread(() -> {
            if (!positions.isEmpty()) {
                stationAdapter.updateBusAverageSpeed(realTimeManager.busAverageSpeed);
                stationAdapter.updateBusPositions(positions);

                int selectedStationIndex = stationAdapter.getSelectedPosition();
                if (selectedStationIndex != -1) {
                    BusApiClient.BusLineStation selectedStation = stationAdapter.getBusLineStation(selectedStationIndex);
                    etaItems.clear();

                    // 临时列表用于保持原始顺序
                    List<BusEtaItem> tempList = new ArrayList<>();

                    // 找出距离选中站点最近的车辆
                    BusApiClient.BusPosition nearestVehicle = null;
                    int minStopCount = Integer.MAX_VALUE;

                    for (BusApiClient.BusPosition vehicle : positions) {
                        int vehicleStationIndex = vehicle.currentStationOrder - 1;

                        // 安全检查
                        if (vehicleStationIndex < 0 || vehicleStationIndex >= stationAdapter.getItemCount()) {
                            continue;
                        }

                        int stopCount = selectedStationIndex - vehicleStationIndex;

                        // 更新最近车辆
                        if (stopCount >= 0 && stopCount <= minStopCount) {
                            minStopCount = stopCount;
                            nearestVehicle = vehicle;
                        }

                        // 计算距离和时间
                        if (vehicleStationIndex <= selectedStationIndex) {
                            int totalDistanceMeters = vehicle.distanceToNext;
                            for (int stationIndex = vehicleStationIndex + 1; stationIndex < selectedStationIndex; stationIndex++) {
                                if (stationIndex < stationAdapter.getItemCount()) {
                                    totalDistanceMeters += stationAdapter.getBusLineStation(stationIndex).lastDistance;
                                }
                            }
                            int etaMinutes = realTimeManager.busAverageSpeed > 0 ?
                                    Math.round((float) totalDistanceMeters / realTimeManager.busAverageSpeed) : 0;
                            tempList.add(new BusEtaItem(stopCount, etaMinutes, totalDistanceMeters));
                        }
                    }

                    // 反转etaItems列表（关键修改点）
                    Collections.reverse(tempList);
                    etaItems.addAll(tempList);
                    assert nearestVehicle != null;
                    if (!nearestVehicle.isArrived) {
                        lastSelectedStationIndex = 0;
                    }

                    // 播报逻辑保持不变
                    if (nearestVehicle != null && nearestVehicle.isArrived && lastSelectedStationIndex != selectedStationIndex) {
                        int nextStationIndex = nearestVehicle.currentStationOrder;
                        if (nextStationIndex < stationAdapter.getItemCount()) {
                            BusApiClient.BusLineStation nextStation = stationAdapter.getBusLineStation(nextStationIndex);
                            String announcement = "开往" + startStation + "方向的" + lineName +
                                    "公交车，即将到达：" + nextStation.stationName;
                            TTSUtils.getInstance(this).speak(announcement);
                            lastSelectedStationIndex = selectedStationIndex;
                        }
                    }

                    busEtaAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, "车辆实时位置更新失败: " + message, Toast.LENGTH_SHORT).show();
        });
    }
}