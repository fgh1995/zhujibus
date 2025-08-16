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
    private String lineID;
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
            lineID = intent.getStringExtra("line_id");
            lineName = intent.getStringExtra("line_name");
            startStation = intent.getStringExtra("start_station");
            endStation = intent.getStringExtra("end_station");
            initViews();
            setupListeners();
            if (lineName == null) {
                Toast.makeText(this, "线路信息获取失败", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                initData();
            }
        }
    }

    private int findStationPositionById(String stationId) {
        if (stationAdapter != null && stationAdapter.getStationList() != null) {
            List<BusApiClient.BusLineStation> stations = stationAdapter.getStationList();
            for (int i = 0; i < stations.size(); i++) {
                if (stationId.equals(String.valueOf(stations.get(i).id))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void initViews() {
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

        LinearLayout loopLineTag = findViewById(R.id.loop_line_tag);
        loopLineTag.setVisibility(View.GONE);

        accessibilityTag = findViewById(R.id.accessibility_tag);
        accessibilityTag.setVisibility(View.GONE);
    }

    private void setupListeners() {
        swapOrientation.setOnClickListener(v -> swapDirection());
    }

    private void initData() {
        busApiClient = new BusApiClient();

        // 查询线路通知
        queryLineNotification();

        // 查询公交线路详情
        queryBusLineDetail();
    }

    private void queryLineNotification() {
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
                        LinearLayout noticeBar = findViewById(R.id.notice_bar);
                        noticeBar.setVisibility(View.VISIBLE);
                        noticeText.setText(HtmlParser.htmlToFormattedText(response.data.text));
                        noticeBar.setOnClickListener(v -> showFullNoticeDialog(response.data.text));
                    });
                }
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Log.e(TAG + "-BusInfo-", "公告-网络请求失败：" + e.getMessage());
            }
        });
    }

    private void queryBusLineDetail() {
        busApiClient.queryBusLineDetail(lineName, 1, new BusApiClient.ApiCallback<>() {
            @Override
            public void onSuccess(BusApiClient.BusLineDetailResponse response) {
                cachedResponse = response;

                if (response == null || response.data == null) {
                    Log.e(TAG + "-BusInfo-", "公交线路-无数据");
                    return;
                }
                if (!"200".equals(response.code)) {
                    Log.e(TAG + "-BusInfo-", "公交线路-状态码错误：" + response.code);
                    return;
                }

                isTwoWayLine = (response.data.up != null && response.data.down != null);

                // 根据lineID确定默认方向
                if (lineID != null) {
                    if (response.data.up != null && lineID.equals(response.data.up.id)) {
                        currentDirection = 1;
                    } else if (response.data.down != null && lineID.equals(response.data.down.id)) {
                        currentDirection = 2;
                    }
                }

                runOnUiThread(() -> {
                    if (isTwoWayLine) {
                        swapOrientation.setVisibility(View.VISIBLE);
                    } else {
                        LinearLayout loopLineTag = findViewById(R.id.loop_line_tag);
                        loopLineTag.setVisibility(View.VISIBLE);
                    }

                    showDirection();
                });
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Log.e(TAG + "-BusInfo-", "获取公交线路失败");
            }
        });
    }

    private void swapDirection() {
        if (!isTwoWayLine) return;

        currentDirection = (currentDirection == 1) ? 2 : 1;
        Log.e(TAG + "-BusInfo-", "切换方向到: " + (currentDirection == 1 ? "上行" : "下行"));
        updateStartEndStations();
        showDirection();
    }

    private void updateStartEndStations() {
        if (cachedResponse == null || cachedResponse.data == null) return;

        BusApiClient.BusLineDirection lineDirection = getCurrentDirectionData();

        if (lineDirection != null) {
            runOnUiThread(() -> {
                TextView startStationName = findViewById(R.id.start_station_name);
                TextView endStationName = findViewById(R.id.end_station_name);
                startStationName.setText(lineDirection.startStation);
                endStationName.setText(lineDirection.endStation);

                startStation = lineDirection.startStation;
                endStation = lineDirection.endStation;
            });
        }
    }

    private BusApiClient.BusLineDirection getCurrentDirectionData() {
        if (cachedResponse == null || cachedResponse.data == null) return null;

        if (currentDirection == 1 && cachedResponse.data.up != null) {
            return cachedResponse.data.up;
        } else if (currentDirection == 2 && cachedResponse.data.down != null) {
            return cachedResponse.data.down;
        }
        return null;
    }

    @SuppressLint("SetTextI18n")
    private void showDirection() {
        BusApiClient.BusLineDirection lineDirection = getCurrentDirectionData();
        if (lineDirection == null) {
            Log.e(TAG + "-BusInfo-", "显示方向失败: 方向数据为空");
            return;
        }

        scheduleButton.setOnClickListener(v -> showScheduleForDirection(lineDirection));

        if (realTimeManager != null) {
            realTimeManager.stopTracking();
        }

        updateAccessibilityTag(lineDirection);
        updateBusTimes(lineDirection);
        updateRouteSummary(lineDirection);
        setupStationList(lineDirection);
    }

    private void showScheduleForDirection(BusApiClient.BusLineDirection lineDirection) {
        busApiClient.getBusLinePlanTime(lineDirection.id, new BusApiClient.ApiCallback<BusApiClient.BusLinePlanTimeResponse>() {
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
        });
    }

    private void updateAccessibilityTag(BusApiClient.BusLineDirection lineDirection) {
        runOnUiThread(() -> {
            if (lineDirection.hasCj == 1) {
                accessibilityTag.setVisibility(View.VISIBLE);
            } else {
                accessibilityTag.setVisibility(View.GONE);
            }
        });
    }

    private void updateBusTimes(BusApiClient.BusLineDirection lineDirection) {
        runOnUiThread(() -> {
            TextView firstBusTime = findViewById(R.id.first_bus_time);
            TextView lastBusTime = findViewById(R.id.last_bus_time);
            firstBusTime.setText(lineDirection.startFirst);
            lastBusTime.setText(lineDirection.startLast);
        });
    }

    private void updateRouteSummary(BusApiClient.BusLineDirection lineDirection) {
        runOnUiThread(() -> {
            TextView routeSummary = findViewById(R.id.route_summary);
            routeSummary.setText("总里程：" + lineDirection.lineLength + " 公里\n票价：" + formatPrice(lineDirection.totalPrice) + " 元");
        });
    }

    private void setupStationList(BusApiClient.BusLineDirection lineDirection) {
        if (lineDirection.stationList == null) {
            Log.w(TAG + "-BusInfo-", "无站点数据");
            return;
        }

        runOnUiThread(() -> {
            stationListRecyclerView = findViewById(R.id.station_list);
            stationListRecyclerView.setLayoutManager(new LinearLayoutManager(BusLineDetailActivity.this) {
                @Override
                public boolean supportsPredictiveItemAnimations() {
                    return false;
                }
            });
            stationAdapter = new StationAdapter(lineDirection.stationList, BusLineDetailActivity.this::showStationDetails);
            stationListRecyclerView.setAdapter(stationAdapter);
            realTimeManager = new BusRealTimeManager(handler, lineDirection.stationList);
            realTimeManager.startTracking(lineDirection.id, BusLineDetailActivity.this);
            // 获取传递过来的站点ID
            String stationId = getIntent().getStringExtra("station_id");
            if (stationId != null && !stationId.isEmpty()) {
                int position = findStationPositionById(stationId);
                if (position != -1) {
                    stationAdapter.setSelectedPosition(position);
                    // 获取RecyclerView的LayoutManager
                    LinearLayoutManager layoutManager = (LinearLayoutManager) stationListRecyclerView.getLayoutManager();
                    // 使用平滑滚动并居中
                    stationListRecyclerView.post(new Runnable() {
                        @Override
                        public void run() {
                            // 获取目标View的位置
                            View targetView = null;
                            if (layoutManager != null) {
                                targetView = layoutManager.findViewByPosition(position);
                            }
                            if (targetView != null) {
                                // 计算居中需要的偏移量
                                int top = targetView.getTop();
                                int height = targetView.getHeight();
                                int screenHeight = stationListRecyclerView.getHeight();
                                int offset = top - (screenHeight / 2 - height / 2);

                                // 平滑滚动到计算出的位置
                                stationListRecyclerView.smoothScrollBy(0, offset);
                            } else {
                                // 如果View还未加载，先滚动到附近位置
                                layoutManager.scrollToPosition(position);
                                // 然后再次尝试居中
                                stationListRecyclerView.post(this);
                            }
                        }
                    });
                }
            }
            setupEtaList();
        });
    }

    private void setupEtaList() {
        RecyclerView rvLiveVehicles = findViewById(R.id.rv_live_vehicles);
        rvLiveVehicles.setLayoutManager(new LinearLayoutManager(
                this,
                LinearLayoutManager.HORIZONTAL,
                false
        ));
        busEtaAdapter = new BusEtaAdapter(etaItems, item -> {
        });
        rvLiveVehicles.setAdapter(busEtaAdapter);
    }

    private void showFullNoticeDialog(String noticeContent) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.ModalDialogTheme);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_notice, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(true);

        TextView noticeTextView = dialogView.findViewById(R.id.notice_content);
        Spanned html = Html.fromHtml(noticeContent, Html.FROM_HTML_MODE_COMPACT);
        noticeTextView.setText(html);

        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setDimAmount(0.4f);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.copyFrom(window.getAttributes());
                lp.width = (int) (getResources().getDisplayMetrics().widthPixels);
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                window.setAttributes(lp);
            }
        });

        dialog.show();
    }

    private void showScheduleDialog(List<String> scheduleTimes) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_bus_schedule, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView scheduleText = dialogView.findViewById(R.id.schedule_text);
        scheduleText.setTypeface(Typeface.MONOSPACE);

        StringBuilder formattedSchedule = new StringBuilder();
        int itemsPerRow = 5;

        for (int i = 0; i < scheduleTimes.size(); i++) {
            if ((i + 1) % itemsPerRow == 0 || i == scheduleTimes.size() - 1) {
                formattedSchedule.append(scheduleTimes.get(i));
            } else {
                formattedSchedule.append(String.format("%-6s", scheduleTimes.get(i)));
            }

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

    @SuppressLint("DefaultLocale")
    private String formatPrice(double price) {
        if (price == (long) price) {
            return String.format("%d", (long) price);
        } else {
            String formatted = String.format("%.2f", price);
            return formatted.replaceAll("0*$", "").replaceAll("\\.$", "");
        }
    }

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
                    updateEtaItems(positions, selectedStationIndex);
                    checkAndAnnounceArrival(positions, selectedStationIndex);
                }
            }
        });
    }

    private void updateEtaItems(List<BusApiClient.BusPosition> positions, int selectedStationIndex) {
        etaItems.clear();
        List<BusEtaItem> tempList = new ArrayList<>();

        for (BusApiClient.BusPosition vehicle : positions) {
            int vehicleStationIndex = vehicle.currentStationOrder - 1;

            if (vehicleStationIndex < 0 || vehicleStationIndex >= stationAdapter.getItemCount()) {
                continue;
            }

            if (vehicleStationIndex <= selectedStationIndex) {
                int totalDistanceMeters = vehicle.distanceToNext;
                for (int stationIndex = vehicleStationIndex + 1; stationIndex < selectedStationIndex; stationIndex++) {
                    if (stationIndex < stationAdapter.getItemCount()) {
                        totalDistanceMeters += stationAdapter.getBusLineStation(stationIndex).lastDistance;
                    }
                }
                int etaMinutes = realTimeManager.busAverageSpeed > 0 ?
                        Math.round((float) totalDistanceMeters / realTimeManager.busAverageSpeed) : 0;
                tempList.add(new BusEtaItem(selectedStationIndex - vehicleStationIndex, etaMinutes, totalDistanceMeters, vehicle.isArrived));
            }
        }

        Collections.reverse(tempList);
        etaItems.addAll(tempList);
        busEtaAdapter.notifyDataSetChanged();
    }

    private void checkAndAnnounceArrival(List<BusApiClient.BusPosition> positions, int selectedStationIndex) {
        BusApiClient.BusPosition nearestVehicle = null;
        int minStopCount = Integer.MAX_VALUE;

        for (BusApiClient.BusPosition vehicle : positions) {
            int vehicleStationIndex = vehicle.currentStationOrder - 1;
            int stopCount = selectedStationIndex - vehicleStationIndex;

            if (stopCount >= 0 && stopCount <= minStopCount) {
                minStopCount = stopCount;
                nearestVehicle = vehicle;
            }
        }


        if (nearestVehicle != null && nearestVehicle.isArrived && lastSelectedStationIndex != selectedStationIndex) {
            int nextStationIndex = nearestVehicle.currentStationOrder;
            if (nextStationIndex < stationAdapter.getItemCount()) {
                BusApiClient.BusLineStation nextStation = stationAdapter.getBusLineStation(nextStationIndex);
                String announcement = "开往" + endStation + "方向的" + lineName +
                        "公交车，即将到达：" + nextStation.stationName;
                TTSUtils.getInstance(this).speak(announcement);
                lastSelectedStationIndex = selectedStationIndex;
            }
        }
    }

    int lastSelectedStationIndex;

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, "车辆实时位置更新失败: " + message, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (realTimeManager != null) {
            realTimeManager.startTracking(getCurrentDirectionId(), this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (realTimeManager != null) {
            realTimeManager.stopTracking();
        }
    }

    private String getCurrentDirectionId() {
        BusApiClient.BusLineDirection direction = getCurrentDirectionData();
        return direction != null ? direction.id : "";
    }
}