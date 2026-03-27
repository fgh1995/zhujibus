package org.zjfgh.zhujibus;

import static com.google.android.material.internal.ViewUtils.dpToPx;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.animation.ValueAnimator;
import android.os.Handler;
import android.text.Html;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.navigation.ui.AppBarConfiguration;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.graphics.Color;

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
    private BusLineView busLineView;
    private ScrollView stationScrollView;
    private List<BusEtaItem> etaItems = new ArrayList<>();
    private BusEtaAdapter busEtaAdapter;
    private TextView scheduleButton;
    private static final String TAG = "BusLineDetailActivity";
    TextView refreshTime;
    TextView errorIndicator;
    private ValueAnimator errorBlinkAnimator;

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
        if (realTimeManager != null && realTimeManager.getStationList() != null) {
            for (int i = 0; i < realTimeManager.getStationList().size(); i++) {
                if (stationId.equals(String.valueOf(realTimeManager.getStationList().get(i).id))) {
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
        refreshTime = findViewById(R.id.refresh_time);
        errorIndicator = findViewById(R.id.error_indicator);
        
        startErrorBlinkAnimation();
        
        // 创建格式化器，指定格式为 00:00:00
        SimpleDateFormat formatter = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        // 获取当前时间
        Date currentDate = new Date();
        refreshTime.setText("刷新时间：" + formatter.format(currentDate));
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
        try {
            busApiClient.queryLineNotification(lineName, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.LineNotificationResponse response) {
                    try {
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
                                try {
                                    LinearLayout noticeBar = findViewById(R.id.notice_bar);
                                    noticeBar.setVisibility(View.VISIBLE);
                                    noticeText.setText(HtmlParser.htmlToFormattedText(response.data.text));
                                    noticeBar.setOnClickListener(v -> {
                                        try {
                                            showFullNoticeDialog(response.data.text);
                                        } catch (Exception e) {
                                            Log.e(TAG, "显示公告详情失败", e);
                                        }
                                    });
                                } catch (Exception e) {
                                    Log.e(TAG, "更新公告UI失败", e);
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG + "-BusInfo-", "处理公告数据失败", e);
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e(TAG + "-BusInfo-", "公告-网络请求失败：" + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "查询线路通知异常", e);
        }
    }

    private void queryBusLineDetail() {
        try {
            if (lineID != null && lineID.startsWith("test_line_")) {
                loadTestData();
                return;
            }
            
            busApiClient.queryBusLineDetail(lineName, 1, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.BusLineDetailResponse response) {
                    try {
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

                        if (lineID != null) {
                            if (response.data.up != null && lineID.equals(response.data.up.id)) {
                                currentDirection = 1;
                            } else if (response.data.down != null && lineID.equals(response.data.down.id)) {
                                currentDirection = 2;
                            }
                        }

                        runOnUiThread(() -> {
                            try {
                                if (isTwoWayLine) {
                                    swapOrientation.setVisibility(View.VISIBLE);
                                } else {
                                    LinearLayout loopLineTag = findViewById(R.id.loop_line_tag);
                                    loopLineTag.setVisibility(View.VISIBLE);
                                }

                                showDirection();
                            } catch (Exception e) {
                                Log.e(TAG, "更新线路详情UI失败", e);
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG + "-BusInfo-", "处理公交线路数据失败", e);
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e(TAG + "-BusInfo-", "获取公交线路失败: " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "查询公交线路详情异常", e);
        }
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
        try {
            busApiClient.getBusLinePlanTime(lineDirection.id, new BusApiClient.ApiCallback<BusApiClient.BusLinePlanTimeResponse>() {
                @Override
                public void onSuccess(BusApiClient.BusLinePlanTimeResponse response) {
                    try {
                        if (response == null || response.data == null) {
                            Log.e(TAG + "-BusInfo-", "时刻表-无数据");
                            runOnUiThread(() -> Toast.makeText(BusLineDetailActivity.this, "时刻表数据为空", Toast.LENGTH_SHORT).show());
                            return;
                        }
                        if (!"200".equals(response.code)) {
                            Log.e(TAG + "-BusInfo-", "时刻表-状态码错误：" + response.code);
                            runOnUiThread(() -> Toast.makeText(BusLineDetailActivity.this, "时刻表获取失败：" + response.code, Toast.LENGTH_SHORT).show());
                            return;
                        }
                        runOnUiThread(() -> {
                            try {
                                showScheduleDialog(response.data);
                            } catch (Exception e) {
                                Log.e(TAG, "显示时刻表失败", e);
                                Toast.makeText(BusLineDetailActivity.this, "显示时刻表失败", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG + "-BusInfo-", "处理时刻表数据失败", e);
                        runOnUiThread(() -> Toast.makeText(BusLineDetailActivity.this, "处理时刻表数据失败", Toast.LENGTH_SHORT).show());
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e(TAG + "-BusInfo-", "时刻表-请求失败：" + e.getMessage(), e);
                    runOnUiThread(() -> Toast.makeText(BusLineDetailActivity.this, "时刻表请求失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "获取时刻表异常", e);
        }
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
            try {
                busLineView = findViewById(R.id.bus_line_view);
                stationScrollView = findViewById(R.id.station_scroll_view);
                
                busLineView.setStations(lineDirection.stationList);
                busLineView.setOnStationClickListener(this::showStationDetails);
                
                realTimeManager = new BusRealTimeManager(handler, lineDirection.stationList);
                realTimeManager.startTracking(lineDirection.id, BusLineDetailActivity.this);
                
                String stationId = getIntent().getStringExtra("station_id");
                if (stationId != null && !stationId.isEmpty()) {
                    int position = findStationPositionById(stationId);
                    if (position != -1) {
                        busLineView.setSelectedPosition(position);
                        busLineView.post(() -> {
                            int stationHeight = 120;
                            int scrollY = position * stationHeight - busLineView.getHeight() / 2;
                            stationScrollView.smoothScrollTo(0, scrollY);
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "设置站点列表失败", e);
            }
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

        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        Date currentTime = new Date();
        int currentHour = currentTime.getHours();
        int currentMinute = currentTime.getMinutes();
        int currentTimeInMinutes = currentHour * 60 + currentMinute;

        int lastBusTimeInMinutes = -1;
        if (!scheduleTimes.isEmpty()) {
            try {
                String lastBusTime = scheduleTimes.get(scheduleTimes.size() - 1);
                String[] parts = lastBusTime.split(":");
                if (parts.length == 2) {
                    lastBusTimeInMinutes = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
                }
            } catch (Exception e) {
                Log.e(TAG, "解析末班车时间失败", e);
            }
        }

        boolean isAfterLastBus = lastBusTimeInMinutes != -1 && currentTimeInMinutes > lastBusTimeInMinutes;

        int itemsPerRow = 3;
        String timePadding = "   ";
        
        SpannableStringBuilder spannableBuilder = new SpannableStringBuilder();
        int itemsInCurrentRow = 0;
        boolean nextBusFound = false;
        int nextBusIndex = -1;

        for (int i = 0; i < scheduleTimes.size(); i++) {
            String timeStr = scheduleTimes.get(i);
            int startIndex = spannableBuilder.length();
            spannableBuilder.append(timeStr);

            if (!isAfterLastBus) {
                try {
                    String[] parts = timeStr.split(":");
                    if (parts.length == 2) {
                        int busTimeInMinutes = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
                        if (busTimeInMinutes < currentTimeInMinutes) {
                            spannableBuilder.setSpan(
                                new ForegroundColorSpan(Color.GRAY),
                                startIndex,
                                spannableBuilder.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            );
                        } else if (!nextBusFound) {
                            nextBusFound = true;
                            nextBusIndex = i;
                            spannableBuilder.setSpan(
                                new ForegroundColorSpan(Color.RED),
                                startIndex,
                                spannableBuilder.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            );
                            spannableBuilder.setSpan(
                                new StyleSpan(Typeface.BOLD),
                                startIndex,
                                spannableBuilder.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            );
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析时间失败", e);
                }
            }

            itemsInCurrentRow++;

            if (itemsInCurrentRow < itemsPerRow && i < scheduleTimes.size() - 1) {
                spannableBuilder.append(timePadding);
            }

            if (itemsInCurrentRow == itemsPerRow && i < scheduleTimes.size() - 1) {
                spannableBuilder.append("\n");
                itemsInCurrentRow = 0;
            }
        }

        scheduleText.setText(spannableBuilder);
        dialog.show();
    }

    private void showStationDetails(BusApiClient.BusLineStation station, int position) {
        if (busLineView != null) {
            busLineView.setSelectedPosition(position);
        }
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
            hideErrorIndicator();
            
            if (!positions.isEmpty()) {
                if (busLineView != null) {
                    busLineView.updateBusPositions(positions);
                }

                int selectedStationIndex = busLineView != null ? busLineView.getSelectedPosition() : -1;
                if (selectedStationIndex != -1) {
                    updateEtaItems(positions, selectedStationIndex);
                    checkAndAnnounceArrival(positions, selectedStationIndex);
                }
            }
        });
    }

    private void updateEtaItems(List<BusApiClient.BusPosition> positions, int selectedStationIndex) {
        if (busEtaAdapter == null) {
            return;
        }
        
        etaItems.clear();
        List<BusEtaItem> tempList = new ArrayList<>();

        for (BusApiClient.BusPosition vehicle : positions) {
            int vehicleStationIndex = vehicle.currentStationOrder - 1;

            if (vehicleStationIndex < 0 || vehicleStationIndex >= realTimeManager.getStationList().size()) {
                continue;
            }
            Log.w(TAG, vehicleStationIndex + "/" + selectedStationIndex);
            if (vehicleStationIndex < selectedStationIndex) {
                int totalDistanceMeters = vehicle.distanceToNext;
                for (int stationIndex = vehicleStationIndex + 1; stationIndex < selectedStationIndex + 1; stationIndex++) {
                    if (stationIndex < realTimeManager.getStationList().size()) {
                        totalDistanceMeters += realTimeManager.getStationList().get(stationIndex).lastDistance;
                    }
                }
                int etaMinutes = realTimeManager.busAverageSpeed > 0 ?
                        Math.round((float) totalDistanceMeters / realTimeManager.busAverageSpeed) : 0;

                tempList.add(new BusEtaItem(selectedStationIndex - vehicleStationIndex, etaMinutes, totalDistanceMeters, vehicle.isArrived, vehicle.plateNumber));
            }
        }

        Collections.reverse(tempList);
        etaItems.addAll(tempList);
        busEtaAdapter.notifyDataSetChanged();
    }

    @SuppressLint("SetTextI18n")
    private void checkAndAnnounceArrival(List<BusApiClient.BusPosition> positions, int selectedStationIndex) {
        // 创建格式化器，指定格式为 00:00:00
        SimpleDateFormat formatter = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        // 获取当前时间
        Date currentDate = new Date();
        refreshTime.setText("刷新时间：" + formatter.format(currentDate));
        BusApiClient.BusPosition nearestVehicle = null;
        for (BusApiClient.BusPosition vehicle : positions) {
            if (selectedStationIndex >= vehicle.currentStationOrder) {
                nearestVehicle = vehicle;
            }
        }
        if (nearestVehicle != null && lastVoiceStationOrder > 0 && (nearestVehicle.currentStationOrder - lastVoiceStationOrder) > 1) {
            boolean shouldAnnounceSkip = false;
            
            if (lastVehicleWasArrived && !nearestVehicle.isArrived) {
                shouldAnnounceSkip = true;
            }
            
            if (shouldAnnounceSkip) {
                String skipStationCn = "因网络延迟导致站点更新跳站，请注意确认车辆位置。";
                String skipStationEn = "Due to network delay, station updates may skip. Please verify vehicle location.";
                String skipStationCombined = skipStationCn + " " + skipStationEn;
                
                TTSUtils tts = TTSUtils.getInstance(this);
                tts.speak(skipStationCombined, "skip_announcement");
            }
        }
        
        if (nearestVehicle != null) {
            lastVehicleWasArrived = nearestVehicle.isArrived;
        }
        
        if (nearestVehicle != null && nearestVehicle.isArrived && lastVoiceStationOrder != nearestVehicle.currentStationOrder) {
            int nextStationIndex = nearestVehicle.currentStationOrder + 1;
            if (nextStationIndex > 0 && nextStationIndex <= realTimeManager.getStationList().size()) {
                String announcementCombined = getAnnouncementCombined(nextStationIndex);

                TTSUtils tts = TTSUtils.getInstance(this);
                tts.speak(announcementCombined, "bus_arrival_announcement");
                
                lastVoiceStationOrder = nearestVehicle.currentStationOrder;
            }
        }

    }

    @NonNull
    private String getAnnouncementCombined(int nextStationIndex) {
        BusApiClient.BusLineStation nextStation = realTimeManager.getStationList().get(nextStationIndex - 1);
        String lineNameEn = lineName.replace("路", "");
        String announcementCn = "开往" + endStation + "方向的" + lineName +
                "公交车，即将到达：" + nextStation.stationName;
        String announcementEn = "The ，" + lineNameEn + "， bus heading to ，" + endStation +
                "， is arriving at，" + nextStation.stationName;
        return announcementCn + "，，" + announcementEn;
    }

    int lastVoiceStationOrder;
    boolean lastVehicleWasArrived = false;

    private void startErrorBlinkAnimation() {
        errorBlinkAnimator = ValueAnimator.ofFloat(0f, 1f);
        errorBlinkAnimator.setDuration(1000);
        errorBlinkAnimator.setRepeatCount(ValueAnimator.INFINITE);
        errorBlinkAnimator.setRepeatMode(ValueAnimator.RESTART);
        errorBlinkAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            if (errorIndicator.getVisibility() == View.VISIBLE) {
                float alpha = progress < 0.5f ? 1f : 0f;
                errorIndicator.setAlpha(alpha);
            }
        });
        errorBlinkAnimator.start();
    }

    private void showErrorIndicator() {
        if (errorIndicator != null) {
            errorIndicator.setVisibility(View.VISIBLE);
            errorIndicator.setAlpha(1f);
        }
    }

    private void hideErrorIndicator() {
        if (errorIndicator != null) {
            errorIndicator.setVisibility(View.GONE);
        }
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            showErrorIndicator();
        });
    }

    private void loadTestData() {
        try {
            List<BusApiClient.BusLineStation> testStations = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                BusApiClient.BusLineStation station = new BusApiClient.BusLineStation();
                station.id = "test_station_" + String.format("%03d", i);
                station.stationName = "测试站点" + i + "号";
                station.stationOrder = i;
                station.lastDistance = 500;
                
                if (i == 4) {
                    station.status = BusApiClient.BusLineStation.StationStatus.NEXT_STATION;
                    station.plateNumber = "京A12345";
                } else if (i < 4) {
                    station.status = BusApiClient.BusLineStation.StationStatus.PASSED;
                } else {
                    station.status = BusApiClient.BusLineStation.StationStatus.NORMAL;
                }
                
                testStations.add(station);
            }

            runOnUiThread(() -> {
                try {
                    routeNumber.setText(lineName);
                    TextView startStationName = findViewById(R.id.start_station_name);
                    startStationName.setText(startStation);
                    TextView endStationName = findViewById(R.id.end_station_name);
                    endStationName.setText(endStation);

                    busLineView = findViewById(R.id.bus_line_view);
                    stationScrollView = findViewById(R.id.station_scroll_view);
                    
                    busLineView.setStations(testStations);
                    busLineView.setOnStationClickListener(this::showStationDetails);

                    realTimeManager = new BusRealTimeManager(handler, testStations);
                    realTimeManager.startTracking("test_line_001", BusLineDetailActivity.this);

                    String stationId = getIntent().getStringExtra("station_id");
                    if (stationId != null && !stationId.isEmpty()) {
                        int position = 4;
                        busLineView.setSelectedPosition(position);
                    }

                    setupEtaList();
                } catch (Exception e) {
                    Log.e(TAG, "加载测试数据失败", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "创建测试数据失败", e);
        }
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
            //realTimeManager.stopTracking();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (errorBlinkAnimator != null) {
            errorBlinkAnimator.cancel();
            errorBlinkAnimator = null;
        }
        realTimeManager.stopTracking();
        realTimeManager = null;
    }

    private String getCurrentDirectionId() {
        BusApiClient.BusLineDirection direction = getCurrentDirectionData();
        return direction != null ? direction.id : "";
    }
}