package org.zjfgh.zhujibus;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.amap.api.maps.TextureMapView;
import com.amap.api.navi.AMapNaviView;
import com.amap.api.maps.model.LatLng;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NavigationMainFragment extends Fragment {

    private static final String TAG = "NavigationMainFragment";

    private static final String ARG_LINE_NAME = "line_name";
    private static final String ARG_END_STATION = "end_station";

    private String lineName;
    private String endStation;

    // ---- 视图引用 ----
    private TextureMapView naviMapView;  // ⭐ 改回 TextureMapView（官方自定义方式）
    private TextView navTimeHM;
    private TextView navTimeSecond;
    private TextView navDateText;
    private HorizontalScrollTextView navRouteNo;
    private TextView navSwapOrientation;
    private HorizontalScrollTextView navNextStation;
    private HorizontalScrollTextView navDirection;
    private TextView gpsSpeedText;
    private TextView firstBusTime;
    private TextView lastBusTime;
    private TextView routeSummary;
    private TextView ticket;
    private ImageView iconGpsSignal;
    private ImageView iconNetworkSignal;
    private IBusCloudLineView iBusCloudLineView; // 新增的 IBusCloudLineView

    // ---- 地图导航管理 ----
    private AmapNavigationView navigation;

    // ---- 时间更新 ----
    private Handler navigationTimeHandler;
    private Runnable navigationTimeRunnable;
    private Typeface digitalTypeface;

    // ---- 速度平滑处理（EWMA + 变化率限制） ----
    private static final float EWMA_ALPHA = 0.3f;       // EWMA 权重：新值权重 0.3，旧值权重 0.7
    private static final float MAX_SPEED_CHANGE = 5f;   // 最大速度变化率（km/h/帧），防止跳变
    private float smoothedSpeed = 0f;                   // EWMA 平滑后的速度
    private boolean speedInitialized = false;           // 是否已初始化速度

    // ---- 数据 ----
    private List<BusApiClient.BusLineStation> stations;
    private boolean isGpsMode = false;
    private int gpsPositionIndex = -1;
    private boolean isGpsArriving = false;
    private BusRealTimeManager realTimeManager;
    private String currentDirectionId;
    private boolean isTwoWayLine = false;
    private int currentDirection = 1;

    // ---- 回调 ----
    private OnStationClickListener stationClickListener;
    private OnGpsArrivalListener gpsArrivalListener;
    private View.OnClickListener swapOrientationListener;
    private boolean isLoopLine = false;

    // ---- 交互监听器接口 ----
    public interface OnStationClickListener {
        void onStationClick(BusApiClient.BusLineStation station, int position);
    }

    public interface OnGpsArrivalListener {
        void onGpsArrival(int stationIndex);
    }

    public static NavigationMainFragment newInstance(String lineName, String endStation) {
        NavigationMainFragment f = new NavigationMainFragment();
        Bundle args = new Bundle();
        if (lineName != null) args.putString(ARG_LINE_NAME, lineName);
        if (endStation != null) args.putString(ARG_END_STATION, endStation);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            lineName = args.getString(ARG_LINE_NAME);
            endStation = args.getString(ARG_END_STATION);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_navigation_main, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            // 1. 找到所有视图
            naviMapView = view.findViewById(R.id.amap_map_view);
            navTimeHM = view.findViewById(R.id.nav_time_hm);
            navTimeSecond = view.findViewById(R.id.nav_time_second);
            navDateText = view.findViewById(R.id.nav_date_text);
            navRouteNo = view.findViewById(R.id.nav_route_no);
            navSwapOrientation = view.findViewById(R.id.nav_swap_orientation);
            navNextStation = view.findViewById(R.id.nav_next_station);
            navDirection = view.findViewById(R.id.nav_direction);
            gpsSpeedText = view.findViewById(R.id.gps_speed_text);
            iconGpsSignal = view.findViewById(R.id.icon_gps_signal);
            iconNetworkSignal = view.findViewById(R.id.icon_network_signal);
            firstBusTime = view.findViewById(R.id.first_bus_time);
            lastBusTime = view.findViewById(R.id.last_bus_time);
            routeSummary = view.findViewById(R.id.route_summary);
            ticket = view.findViewById(R.id.ticket);
            iBusCloudLineView = view.findViewById(R.id.i_bus_cloud_line_view); // 获取 IBusCloudLineView

            // 初始化信号指示器（默认值：GPS 无信号、网络 0 格）
            SignalIndicatorManager.setGpsSignal(iconGpsSignal, false);
            SignalIndicatorManager.setNetworkSignal(iconNetworkSignal, SignalIndicatorManager.NET_LEVEL_0);

            // 2. 加载数码字体
            try {
                digitalTypeface = Typeface.createFromAsset(requireContext().getAssets(), "fonts/DS-DIGIB-2.ttf");
                if (navTimeHM != null) navTimeHM.setTypeface(digitalTypeface);
                if (navTimeSecond != null) navTimeSecond.setTypeface(digitalTypeface);
                if (gpsSpeedText != null) gpsSpeedText.setTypeface(digitalTypeface);
            } catch (Throwable t) {
                Log.w(TAG, "加载数码字体失败: " + t.getMessage());
            }

            // 3. 初始化文本
            if (navRouteNo != null && lineName != null) {
                navRouteNo.setText(lineName);
            }
            if (navNextStation != null) {
                navNextStation.setText("加载中");
            }
            if (navDirection != null && endStation != null) {
                navDirection.setText("往 " + endStation + "方向");
            }

            // 4. 启动时间更新
            navigationTimeHandler = new Handler();
            navigationTimeRunnable = new Runnable() {
                @Override
                public void run() {
                    updateNavigationTime();
                    if (navigationTimeHandler != null) {
                        navigationTimeHandler.postDelayed(this, 1000);
                    }
                }
            };
            navigationTimeHandler.post(navigationTimeRunnable);

            // 5. 设置换向按钮
            if (swapOrientationListener != null && navSwapOrientation != null) {
                navSwapOrientation.setOnClickListener(swapOrientationListener);
            }
            if (navSwapOrientation != null) {
                if (isLoopLine) {
                    navSwapOrientation.setVisibility(View.GONE);
                } else {
                    navSwapOrientation.setVisibility(View.VISIBLE);
                }
            }

            // 6. 初始化高德地图
            initMapWhenReady(savedInstanceState);

            // 7. 设置 IBusCloudLineView 监听
            if (iBusCloudLineView != null) {
                iBusCloudLineView.setOnStationClickListener((station, position) -> {
                    if (stationClickListener != null) {
                        stationClickListener.onStationClick(station, position);
                    }
                });
                iBusCloudLineView.setOnGpsArrivalListener(position -> {
                    if (gpsArrivalListener != null) {
                        gpsArrivalListener.onGpsArrival(position);
                    }
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated 失败", e);
        }
    }

    private void initMapWhenReady(Bundle savedInstanceState) {
        if (naviMapView == null) {
            Log.e(TAG, "initMapWhenReady skipped: naviMapView is null");
            return;
        }
        final Bundle finalSavedState = savedInstanceState;
        naviMapView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (naviMapView.getWidth() <= 0 || naviMapView.getHeight() <= 0) {
                            return;
                        }
                        naviMapView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        try {
                            navigation = new AmapNavigationView(requireContext(), naviMapView);
                            // 设置速度回调，网络模式下显示车辆移动速度
                            navigation.setSpeedChangeListener(speedKmh -> {
                                if (!isGpsMode) {
                                    updateSpeed(speedKmh);
                                }
                            });
                            Log.d(TAG, "onGlobalLayout -> start onCreate (w=" +
                                    naviMapView.getWidth() + ", h=" + naviMapView.getHeight() + ")");
                            navigation.onCreate(finalSavedState);
                            if (isHostResumed && navigation != null) {
                                navigation.onResume();
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "delayed onCreate failed: " + t.getMessage(), t);
                        }
                    }
                });
    }

    // ============================================================
    //  公开 API
    // ============================================================

    public AmapNavigationView getNavigation() {
        return navigation;
    }

    public void setGpsMode(boolean gpsMode) {
        this.isGpsMode = gpsMode;
        if (navigation != null) navigation.setGpsMode(gpsMode);
        if (iBusCloudLineView != null) {
            iBusCloudLineView.setGpsMode(gpsMode);
            if (!gpsMode) {
                iBusCloudLineView.clearGpsPosition();
            }
        }
        // 切换模式时重置速度窗口
        resetSpeed();
    }

    public void setSwapOrientation(View.OnClickListener listener) {
        this.swapOrientationListener = listener;
        if (navSwapOrientation != null && listener != null) {
            navSwapOrientation.setOnClickListener(listener);
        }
    }

    public void setLoopLine(boolean isLoopLine) {
        this.isLoopLine = isLoopLine;
        if (navSwapOrientation != null) {
            if (isLoopLine) {
                navSwapOrientation.setVisibility(View.GONE);
            } else {
                navSwapOrientation.setVisibility(View.VISIBLE);
            }
        }
    }

    public void updateSpeed(float speedKmh) {
        if (gpsSpeedText != null) {
            // speedKmh < 0 表示无有效速度，显示 "--"
            if (speedKmh < 0) {
                gpsSpeedText.setText("--");
                return;
            }

            // EWMA 平滑处理
            if (!speedInitialized) {
                // 首次初始化，直接使用当前速度
                smoothedSpeed = speedKmh;
                speedInitialized = true;
            } else {
                // EWMA: smoothedSpeed = alpha * newSpeed + (1 - alpha) * oldSpeed
                float ewmaSpeed = EWMA_ALPHA * speedKmh + (1 - EWMA_ALPHA) * smoothedSpeed;

                // 变化率限制：防止速度跳变太快
                float speedChange = ewmaSpeed - smoothedSpeed;
                if (Math.abs(speedChange) > MAX_SPEED_CHANGE) {
                    // 限制变化幅度
                    if (speedChange > 0) {
                        ewmaSpeed = smoothedSpeed + MAX_SPEED_CHANGE;
                    } else {
                        ewmaSpeed = smoothedSpeed - MAX_SPEED_CHANGE;
                    }
                }

                smoothedSpeed = ewmaSpeed;
            }

            // 显示平滑后的速度
            gpsSpeedText.setText(String.format(Locale.CHINA, "%.0f", smoothedSpeed));
        }
    }

    public void resetSpeed() {
        if (gpsSpeedText != null) {
            gpsSpeedText.setText("--");
            // 重置 EWMA 状态
            smoothedSpeed = 0f;
            speedInitialized = false;
        }
    }

    public void updateNextStation(String stationName) {
        if (navNextStation != null) {
            navNextStation.setText("下一站: " + stationName);
        }
    }

    public void updateDirection(String directionEndStation) {
        if (navDirection != null && directionEndStation != null) {
            navDirection.setText("往 " + directionEndStation + "方向");
        }
    }

    public void updateRouteNo(String lineName) {
        if (navRouteNo != null && lineName != null) {
            navRouteNo.setText(lineName);
        }
    }

    public void showEnteringHint() {
        if (navNextStation != null) navNextStation.setText("进场");
    }

    public void updateMyLocation(double gcjLat, double gcjLng, float bearing) {
        if (navigation != null) {
            navigation.updateMyLocation(gcjLat, gcjLng, bearing);
        }
    }

    public void drawRoute(List<LatLng> mapPoints) {
        if (navigation != null) navigation.drawRoute(mapPoints);
    }

    public void notifyHostResumed(boolean resumed) {
        isHostResumed = resumed;
    }

    // ============================================================
    //  线路数据加载方法（新增）
    // ============================================================

    /**
     * 设置线路数据
     */
    public void setLineData(BusApiClient.BusLineDirection directionData, boolean twoWayLine, int direction) {
        this.currentDirection = direction;
        this.isTwoWayLine = twoWayLine;
        this.currentDirectionId = directionData.id;

        if (directionData == null) {
            Log.e(TAG, "setLineData: directionData is null");
            return;
        }

        this.stations = directionData.stationList;

        // ⭐ 设置公交线路起点和终点（用于GPS导航）
        if (stations != null && !stations.isEmpty() && navigation != null) {
            BusApiClient.BusLineStation startStation = stations.get(0);  // 起点站
            BusApiClient.BusLineStation endStation = stations.get(stations.size() - 1);  // 终点站
            
            if (startStation.poiOriginLat != 0 && startStation.poiOriginLon != 0 &&
                endStation.poiOriginLat != 0 && endStation.poiOriginLon != 0) {
                navigation.setBusLineStartAndEnd(
                        startStation.poiOriginLat, startStation.poiOriginLon,
                        endStation.poiOriginLat, endStation.poiOriginLon
                );
                Log.d(TAG, "[GPS] 已设置导航起点和终点: 起点=" + startStation.stationName + ", 终点=" + endStation.stationName);
            }
        }

        // 0. 切换方向时，先清空旧方向的所有车辆 marker
        //    （避免上一方向的车辆残留在新方向地图上）
        if (navigation != null) {
            navigation.clearBusMarkers();
        }

        // 1. 更新 IBusCloudLineView
        if (iBusCloudLineView != null) {
            iBusCloudLineView.setStations(stations);
        }

        // 2. 更新首末班车
        updateBusTimes(directionData);

        // 3. 更新总里程
        if (directionData.lineLength > 0) {
            updateRouteSummary(String.valueOf(directionData.lineLength));
        }

        // 4. 更新票价
        double price = directionData.totalPrice > 0 ? directionData.totalPrice : 1.0;
        updatePriceText(String.format(Locale.getDefault(), "%.2f", price));

        // 5. 更新方向
        if (directionData.endStation != null) {
            updateDirection(directionData.endStation);
        }

        // 6. 更新下一站（默认显示第一站）
        if (stations != null && !stations.isEmpty()) {
            String firstStation = stations.get(0).stationName;
            if (firstStation != null) {
                updateNextStation(firstStation);
            }
        }

        // 7. 如果支持双向，更新换向按钮
        if (navSwapOrientation != null) {
            if (isLoopLine) {
                navSwapOrientation.setVisibility(View.GONE);
            } else {
                navSwapOrientation.setVisibility(View.VISIBLE);
            }
        }

        Log.d(TAG, "setLineData 完成: " + stations.size() + " 个站点");
    }

    /**
     * 更新车辆位置
     */
    public void updateBusPositions(List<BusApiClient.BusPosition> positions) {
        if (iBusCloudLineView != null) {
            iBusCloudLineView.updateBusPositions(positions);
        }

        // 在地图上绘制所有车辆 marker（仅网络模式生效，GPS 模式内部会清空）
        if (navigation != null) {
            navigation.updateBusMarkers(positions);
        }

        // 自动更新下一站
        if (positions != null && !positions.isEmpty() && stations != null && !stations.isEmpty()) {
            for (BusApiClient.BusPosition vehicle : positions) {
                if (vehicle.currentStationOrder > 0 && vehicle.currentStationOrder <= stations.size()) {
                    int vehicleIndex = vehicle.currentStationOrder - 1;
                    if (vehicleIndex + 1 < stations.size()) {
                        String nextStation = stations.get(vehicleIndex + 1).stationName;
                        updateNextStation(nextStation);
                    } else if (vehicleIndex == stations.size() - 1) {
                        updateNextStation("终点站");
                    }
                    break;
                }
            }
        }
    }

    /**
     * 更新 GPS 位置（用于 GPS 报站模式）
     */
    public void updateGpsPosition(int position, boolean isArriving) {
        this.gpsPositionIndex = position;
        this.isGpsArriving = isArriving;
        if (iBusCloudLineView != null) {
            iBusCloudLineView.updateGpsPosition(position, isArriving);
        }
        // 更新下一站
        if (isArriving && stations != null && position >= 0 && position + 1 < stations.size()) {
            String nextStation = stations.get(position + 1).stationName;
            updateNextStation(nextStation);
        }
    }

    public void clearGpsPosition() {
        this.gpsPositionIndex = -1;
        this.isGpsArriving = false;
        if (iBusCloudLineView != null) {
            iBusCloudLineView.clearGpsPosition();
        }
    }

    public void setSelectedPosition(int position) {
        if (iBusCloudLineView != null) {
            iBusCloudLineView.setSelectedPosition(position);
        }
    }

    public int getSelectedPosition() {
        return iBusCloudLineView != null ? iBusCloudLineView.getSelectedPosition() : -1;
    }

    public void setOnStationClickListener(OnStationClickListener listener) {
        this.stationClickListener = listener;
        if (iBusCloudLineView != null) {
            iBusCloudLineView.setOnStationClickListener((station, position) -> {
                if (listener != null) {
                    listener.onStationClick(station, position);
                }
            });
        }
    }

    public void setOnGpsArrivalListener(OnGpsArrivalListener listener) {
        this.gpsArrivalListener = listener;
        if (iBusCloudLineView != null) {
            iBusCloudLineView.setOnGpsArrivalListener(position -> {
                if (listener != null) {
                    listener.onGpsArrival(position);
                }
            });
        }
    }

    public void resetAllStations() {
        if (iBusCloudLineView != null) {
            iBusCloudLineView.resetAllStations();
        }
    }

    // ============================================================
    //  内部方法
    // ============================================================

    private void updateNavigationTime() {
        if (getContext() == null) return;
        try {
            Calendar calendar = Calendar.getInstance();
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            int minute = calendar.get(Calendar.MINUTE);
            int second = calendar.get(Calendar.SECOND);
            int day = calendar.get(Calendar.DAY_OF_MONTH);
            String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
            int weekDay = calendar.get(Calendar.DAY_OF_WEEK) - 1;
            if (weekDay < 0) weekDay = 0;

            if (navTimeHM != null) {
                navTimeHM.setText(String.format(Locale.getDefault(), "%02d:%02d", hour, minute));
            }
            if (navTimeSecond != null) {
                navTimeSecond.setText(String.format(Locale.getDefault(), "%02d", second));
            }
            if (navDateText != null) {
                navDateText.setText(String.format(Locale.getDefault(), "%d月%d日 %s",
                        calendar.get(Calendar.MONTH) + 1, day, weekDays[weekDay]));
            }

            // 顺手刷新信号指示器（每秒都重算 + 重设；manager 内部有去重，不会真的反复切图）
            updateSignalIndicators();
        } catch (Throwable t) {
            Log.w(TAG, "updateNavigationTime failed: " + t.getMessage());
        }
    }

    /**
     * 刷新 GPS / 网络信号图标
     * <p>
     * GPS 判定：5 秒内有过成功定位（errorCode == 0）算"有信号
     */
    private void updateSignalIndicators() {
        if (getContext() == null) return;
        try {
            // GPS
            long lastGps = navigation != null ? navigation.getLastGpsSuccessTimeMs() : 0L;
            SignalIndicatorManager.setGpsSignalByTime(iconGpsSignal, lastGps);

            // 网络
            int netLevel = SignalIndicatorManager.getNetworkSignalLevel(requireContext());
            SignalIndicatorManager.setNetworkSignal(iconNetworkSignal, netLevel);
        } catch (Throwable t) {
            Log.w(TAG, "updateSignalIndicators failed: " + t.getMessage());
        }
    }

    private void updateBusTimes(BusApiClient.BusLineDirection lineDirection) {
        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            SimpleDateFormat outputFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

            Date firstDate = inputFormat.parse(lineDirection.startFirst);
            Date lastDate = inputFormat.parse(lineDirection.startLast);

            String firstBusTimeStr = outputFormat.format(firstDate);
            String lastBusTimeStr = outputFormat.format(lastDate);

            updateFirstBusTime(firstBusTimeStr);
            updateLastBusTime(lastBusTimeStr);
        } catch (ParseException e) {
            e.printStackTrace();
            updateFirstBusTime(lineDirection.startFirst);
            updateLastBusTime(lineDirection.startLast);
        }
    }

    public void updateFirstBusTime(String time) {
        if (firstBusTime != null && time != null) {
            firstBusTime.setText("首：" + time);
        }
    }

    public void updateLastBusTime(String time) {
        if (lastBusTime != null && time != null) {
            lastBusTime.setText("末：" + time);
        }
    }

    public void updateRouteSummary(String mileage) {
        if (routeSummary != null && mileage != null) {
            routeSummary.setText("总里程：" + mileage + " 公里");
        }
    }

    public void updatePriceText(String priceText) {
        if (ticket != null && priceText != null) {
            ticket.setText("票价：" + priceText + " 元");
        }
    }

    // ---- Fragment 生命周期 ----

    private boolean isHostResumed = false;

    @Override
    public void onResume() {
        super.onResume();
        isHostResumed = true;
        if (navigation != null) {
            navigation.onResume();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        isHostResumed = false;
        if (navigation != null) {
            navigation.onPause();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (navigation != null) {
            navigation.onSaveInstanceState(outState);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (navigation != null) {
            navigation.onLowMemory();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (navigationTimeHandler != null && navigationTimeRunnable != null) {
            navigationTimeHandler.removeCallbacks(navigationTimeRunnable);
        }
        navigationTimeHandler = null;
        navigationTimeRunnable = null;

        if (navigation != null) {
            navigation.onDestroy();
            navigation = null;
        }

        naviMapView = null;
        navTimeHM = null;
        navTimeSecond = null;
        navDateText = null;
        navRouteNo = null;
        navNextStation = null;
        navDirection = null;
        gpsSpeedText = null;
        iBusCloudLineView = null;
    }
}