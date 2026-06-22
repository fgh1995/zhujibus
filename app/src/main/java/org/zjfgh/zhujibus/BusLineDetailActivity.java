package org.zjfgh.zhujibus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationProvider;
import android.location.LocationManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.animation.ValueAnimator;
import android.os.Handler;
import android.text.Html;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.graphics.Color;

import org.zjfgh.zhujibus.view.DotMatrixLinearLayout;

import io.sgr.geometry.utils.GeometryUtils;

public class BusLineDetailActivity extends AppCompatActivity implements BusRealTimeManager.RealTimeUpdateListener {
    private String lineID;
    private String lineName;
    private String startStation;
    private String endStation;
    private BusApiClient busApiClient;
    private HorizontalScrollTextView routeNumber;
    private TextView navHasNotification;
    private TextView noticeText;
    private DotMatrixView accessibilityIcon;
    private HorizontalScrollTextView nextStationInfo;
    private HorizontalScrollTextView tips;
    private ImageView navIconMessageImg;
    private TextView navIconMessageText;
    // 当前显示的方向 (1:上行, 2:下行)
    private int currentDirection = 1;
    // 线路是否有双向
    private boolean isTwoWayLine = false;
    // 缓存线路数据
    private BusApiClient.BusLineDetailResponse cachedResponse;
    private BusRealTimeManager realTimeManager;
    private final Handler handler = new Handler();
    private BusLineView busLineView;
    private ScrollView stationScrollView;
    private final List<BusEtaItem> etaItems = new ArrayList<>();
    private BusEtaAdapter busEtaAdapter;
    private static final String TAG = "BusLineDetailActivity";
    MoreFragment moreFragment;
    String priceText = "0.00";
    // ⭐ 导航模块视图引用已迁移到 NavigationMainFragment，Activity 不再持有：
    //   navTimeHM / navTimeSecond / navDateText / navRouteNo / navNextStation / navDirection
    //   navigationTimeHandler / navigationTimeRunnable / updateNavigationTime()
    //   全部由 Fragment 内部管理。
    //
    // ⭐ tencentNavigation 也不再由 Activity 直接持有；
    //   通过 navigationMainFragment.getNavigation() 访问。
    /** ⭐ 标记 Activity 是否已 onResume：用于在 onGlobalLayout 后补一次 onResume */
    private boolean isActivityResumed = false;
    /** 当前 NavigationMainFragment 实例（用于通过它访问 navigation/调用更新方法） */
    private NavigationMainFragment navigationMainFragment;
    /** 当前 SpeakFragment 实例（用于转发 GPS 数据更新） */
    private SpeakFragment speakFragment;
    /** 当前是否在地图设置页（用于图标切换显示） */
    private boolean isShowingMapSettings = false;
    /** 当前是否显示排班页 */
    private boolean isShowingSchedule = false;
    /** 地图图标原始资源（点击切换时换图标用） */
    private android.widget.ImageView navIconMapImg;
    private android.widget.TextView navIconMapText;
    /** 排班图标 */
    private android.widget.ImageView navIconScheduleImg;
    private android.widget.TextView navIconScheduleText;

    /**
     * 导航模块时间更新方法
     */
    // ⭐ updateNavigationTime() 已迁移到 NavigationMainFragment，
    //   fragment 内部使用独立 Handler 每秒刷新，不需要 Activity 干预。

    TextView errorIndicator;
    TextView networkModeText;
    TextView modeTips;
    DotMatrixLinearLayout modeSwitch;
    TextView networkStatusIndicator;
    private ValueAnimator errorBlinkAnimator;
    private ValueAnimator gpsBlinkAnimator;
    private boolean isGpsSignalNormal = false;
    private String lastErrorMessage = null;
    private String lastErrorDetail = null;
    private Handler gpsTimeHandler = new Handler();
    private Runnable gpsTimeRunnable;
    private static final long GPS_TIME_UPDATE_INTERVAL = 1000L;

    // ---- 网络模式刷新倒计时（10 秒一次）----
    /** 网络模式默认刷新间隔（秒），同时也是倒计时初始值 */
    private static final int NETWORK_REFRESH_COUNTDOWN_SEC = 10;
    /** 倒计时定时器 */
    private final Handler refreshCountdownHandler = new Handler();
    private Runnable refreshCountdownRunnable;
    /** 当前倒计时剩余秒数（0 = 未启动 / 已结束） */
    private int refreshCountdownSec = 0;
    /** 最近一次刷新是否失败（true → networkModeText 显示"检查网络"） */
    private boolean networkRefreshFailed = false;

    public enum AnnounceMode {
        NETWORK,
        GPS
    }
    private AnnounceMode currentAnnounceMode = AnnounceMode.NETWORK;

    // GPS 模式下的状态变量由后台 HandlerThread 读取/写入，加 volatile 保证可见性
    private volatile int lastAnnouncedStationIndex = -1;
    private volatile boolean isInsideStationRadius = false;
    private volatile int lastInsideStationIndex = -1;
    private volatile boolean hasLeftTerminalStation = false;
    private volatile int gpsCurrentStationIndex = -1;

    private double currentGpsLat = 0;
    private double currentGpsLon = 0;
    private String nearestStationName = "";
    private double nearestStationDistance = -1;
    private double nearestStationDirectDistance = -1;
    private static final double DEFAULT_ENTER_STATION_RADIUS = 30.0;
    private static final double DEFAULT_EXIT_STATION_RADIUS = 80.0;
    // 后台 GPS 线程会读，滑块在主线程改，volatile 保证可见性
    private volatile double enterStationRadius = DEFAULT_ENTER_STATION_RADIUS;
    private volatile double exitStationRadius = DEFAULT_EXIT_STATION_RADIUS;
    // 后台 GPS 线程会读，主线程在 showDirection 中赋值，volatile 保证可见性
    private volatile List<io.sgr.geometry.Coordinate> routePoints;
    private static final double STATION_PROXIMITY_THRESHOLD_METERS = 50.0;

    private double lastLocationLat = 0;
    private double lastLocationLon = 0;
    private long lastLocationTimeForSpeed = 0;
    private int locationUpdateCount = 0;

    private static final long SPEED_TIMEOUT_MS = 2000;
    private static final int SPEED_WINDOW_SIZE = 3;
    private static final float MAX_VALID_SPEED_KMH = 120.0f;
    private static final float MIN_VALID_SPEED_KMH = 0.5f;
    private final ArrayList<Float> speedWindow = new ArrayList<>();
    private float currentSmoothedSpeedKmh = 0f;
    private Handler speedTimeoutHandler = new Handler();
    private Runnable speedTimeoutRunnable;
    // 保护 calculateRealTimeSpeed 涉及的字段：后台 HandlerThread 与主线程并发读写
    private final Object speedLock = new Object();
    /** 消息图标 */
    private LinearLayout navIconMessage;
    public enum DistanceMode {
        ALONG_ROUTE("沿线距离"),
        STRAIGHT_LINE("直线距离");

        private final String displayName;
        DistanceMode(String displayName) {
            this.displayName = displayName;
        }
        public String getDisplayName() {
            return displayName;
        }
    }
    private DistanceMode currentDistanceMode = DistanceMode.STRAIGHT_LINE;

    public enum CoordConvertMode {
        WGS_TO_GCJ("WGS→GCJ-02"),
        GCJ_TO_WGS("GCJ-02→WGS"),
        NO_CONVERT("不转换");

        private final String displayName;
        CoordConvertMode(String displayName) {
            this.displayName = displayName;
        }
        public String getDisplayName() {
            return displayName;
        }
    }
    // 高德定位SDK返回GCJ-02坐标，无需转换
    private CoordConvertMode currentCoordConvertMode = CoordConvertMode.NO_CONVERT;

    private static final int TIPS_INTERVAL = 3000;
    private static final String[] TIPS_TEXT_BASE = {"文明排队   上下有序", "严禁携带危险物品上车"};
    private static final int[] TIPS_COLOR_BASE = {0xFFFFFF00, 0xFF00FFFF};
    private static final int TIPS_COLOR_PURPLE = 0xFFAA00FF;
    // GPS 模式报站文案：中英双语"扫码评价"提示
    private static final String QR_HINT_CN = "        欢迎扫车内二维码对本次乘车服务进行评价。";
    private static final String QR_HINT_EN = "    Scan the QR code on board to rate your ride. Thank you!";
    private String[] currentTipsText = TIPS_TEXT_BASE;
    private int[] currentTipsColor = TIPS_COLOR_BASE;
    private Handler tipsHandler = new Handler();
    private int tipsAnimationIndex = 0;


    /** 当前是否显示喊话页 */
    private boolean isShowingSpeak = false;
    /** 当前是否显示更多应用页 */
    private boolean isShowingMore = false;

    /** 喊话图标 */
    private android.widget.ImageView navIconSpeakImg;
    private android.widget.TextView navIconSpeakText;

    /** 更多应用图标 */
    private android.widget.ImageView navIconMoreImg;
    private final Runnable tipsRunnable = new Runnable() {
        @Override
        public void run() {
            tipsAnimationIndex = (tipsAnimationIndex + 1) % currentTipsText.length;
            tips.setText(currentTipsText[tipsAnimationIndex]);
            tips.setTextColor(currentTipsColor[tipsAnimationIndex]);
            tipsHandler.postDelayed(this, TIPS_INTERVAL);
        }
    };

    private void startTipsAnimation() {
        tipsHandler.removeCallbacksAndMessages(null);
        tipsAnimationIndex = 0;
        tips.setText(currentTipsText[0]);
        tips.setTextColor(currentTipsColor[0]);
        tipsHandler.postDelayed(tipsRunnable, TIPS_INTERVAL);
    }

    private String formatDistance(double distance) {
        if (distance >= 1000) {
            return String.format(Locale.CHINA, "%.1fkm", distance / 1000);
        } else {
            return String.format(Locale.CHINA, "%.0fm", distance);
        }
    }

    private void showAnnounceModeDialog() {
        String[] modes = {"网络模式", "GPS模式"};
        int selectedIndex = currentAnnounceMode == AnnounceMode.NETWORK ? 0 : 1;
        new AlertDialog.Builder(this)
                .setTitle("选择报站模式")
                .setSingleChoiceItems(modes, selectedIndex, (dialog, which) -> {
                    AnnounceMode newMode = (which == 0) ? AnnounceMode.NETWORK : AnnounceMode.GPS;
                    if (newMode != currentAnnounceMode) {
                        currentAnnounceMode = newMode;
                        updateAnnounceModeState();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCoordConvertModeDialog() {
        String[] modes = {
                CoordConvertMode.WGS_TO_GCJ.getDisplayName(),
                CoordConvertMode.GCJ_TO_WGS.getDisplayName(),
                CoordConvertMode.NO_CONVERT.getDisplayName()
        };
        int selectedIndex;
        switch (currentCoordConvertMode) {
            case GCJ_TO_WGS:
                selectedIndex = 1;
                break;
            case NO_CONVERT:
                selectedIndex = 2;
                break;
            default:
                selectedIndex = 0;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择坐标转换模式")
                .setSingleChoiceItems(modes, selectedIndex, (dialog, which) -> {
                    CoordConvertMode newMode;
                    switch (which) {
                        case 1:
                            newMode = CoordConvertMode.GCJ_TO_WGS;
                            break;
                        case 2:
                            newMode = CoordConvertMode.NO_CONVERT;
                            break;
                        default:
                            newMode = CoordConvertMode.WGS_TO_GCJ;
                    }
                    if (newMode != currentCoordConvertMode) {
                        currentCoordConvertMode = newMode;
                        updateCoordConvertModeState();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateCoordConvertModeState() {
        Log.d(TAG, "坐标转换模式已切换为: " + currentCoordConvertMode.getDisplayName());
        updateCoordConvertModeDisplay();
    }

    private void updateCoordConvertModeDisplay() {
        Log.d(TAG, "坐标转换模式显示: " + currentCoordConvertMode.getDisplayName());
    }

    private void updateAnnounceModeState() {
        if (currentAnnounceMode == AnnounceMode.GPS) {
            if (!PermissionUtils.hasLocationPermission(this)) {
                PermissionUtils.requestLocationPermission(this, new PermissionUtils.PermissionCallback() {
                    @Override
                    public void onPermissionGranted() {
                        updateAnnounceModeState();
                    }

                    @Override
                    public void onPermissionDenied() {
                        currentAnnounceMode = AnnounceMode.NETWORK;
                        updateAnnounceModeDisplay();
                        Toast.makeText(BusLineDetailActivity.this, "没有位置权限，GPS模式不可用", Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            TTSUtils.getInstance(this).stopAll();
            lastVoiceStationOrder = -1;
            lastAnnouncedStationIndex = -1;
            isInsideStationRadius = false;
            lastInsideStationIndex = -1;
            hasLeftTerminalStation = false;
            gpsCurrentStationIndex = -1;
            synchronized (speedLock) {
                lastLocationTimeForSpeed = 0;
                lastLocationLat = 0;
                lastLocationLon = 0;
                locationUpdateCount = 0;
                speedWindow.clear();
                currentSmoothedSpeedKmh = 0f;
            }
            stopSpeedTimeout();
            if (realTimeManager != null) {
                realTimeManager.stopTracking();
            }
            clearEtaItems();
            if (busLineView != null) {
                busLineView.resetAllStations();
                busLineView.setGpsMode(true);
            }
            GpsWarmingUp.startWarmingUp(this);
            GpsWarmingUp.addListener(gpsActivityListener);
            GpsWarmingUp.addSatelliteListener(satelliteCountListener);
            Location lastLocation = GpsWarmingUp.getLastKnownLocation();
            if (lastLocation != null) {
                // post 到 GPS 后台线程，避免重计算阻塞主线程
                final Location snapshot = lastLocation;
                GpsWarmingUp.postToGpsThread(() -> handleGpsLocation(snapshot));
            }
            checkGpsProviderStatus();
            startGpsTimeUpdate();
            // GPS 模式：停掉网络模式刷新倒计时
            stopNetworkRefreshCountdown();
            // GPS 模式：显示 SpeakFragment 中的 GPS 信息
            if (speakFragment != null) {
                speakFragment.setGpsVisible(true);
            }
            // ⭐ GPS 模式：开启地图罗盘模式（3D 贴地视角）
            if (navigationMainFragment != null) {
                navigationMainFragment.setGpsMode(true);
                // GPS 模式下清除目标站点位置
                if (navigationMainFragment.getNavigation() != null) {
                    navigationMainFragment.getNavigation().clearTargetStation();
                }
            }
        } else {
            GpsWarmingUp.removeListener(gpsActivityListener);
            GpsWarmingUp.removeSatelliteListener(satelliteCountListener);
            GpsWarmingUp.stopWarmingUp();
            lastAnnouncedStationIndex = -1;
            isInsideStationRadius = false;
            lastInsideStationIndex = -1;
            hasLeftTerminalStation = false;
            gpsCurrentStationIndex = -1;
            // ⭐ 网络模式：关闭地图罗盘模式，保持自由视角
            if (navigationMainFragment != null) {
                navigationMainFragment.setGpsMode(false);
            }
            if (realTimeManager != null) {
                realTimeManager.startTracking(getCurrentDirectionId(), this);
            }
            if (busLineView != null) {
                busLineView.setGpsMode(false);
            }
            stopGpsTimeUpdate();
            // ⭐ 网络模式：启动 10 秒刷新倒计时
            startNetworkRefreshCountdown();
            // GPS 模式：隐藏 SpeakFragment 中的 GPS 信息
            if (speakFragment != null) {
                speakFragment.setGpsVisible(false);
            }
        }
        updateAnnounceModeDisplay();
    }

    private void checkGpsProviderStatus() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean isGpsEnabled = false;
        if (locationManager != null) {
            try {
                LocationProvider gpsProvider = locationManager.getProvider(LocationManager.GPS_PROVIDER);
                if (gpsProvider != null) {
                    List<String> enabledProviders = locationManager.getProviders(true);
                    for (String provider : enabledProviders) {
                        if (LocationManager.GPS_PROVIDER.equals(provider)) {
                            isGpsEnabled = true;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("BusLineDetailActivity", "检查GPS状态失败", e);
            }
        }
        updateNetworkStatusIndicator(isGpsEnabled);
    }

    private void updateAnnounceModeDisplay() {
        if (currentAnnounceMode == AnnounceMode.GPS) {
            // GPS 模式：模式文字标"GPS"（红色高亮），状态灯变绿
            // GPS 模式：显示卫星数（缓存值）
            int used = GpsWarmingUp.getSatelliteCount();
            int total = GpsWarmingUp.getTotalSatelliteCount();
            if (networkModeText != null) {
                networkModeText.setText("GPS " + used + "/" + total);
                networkModeText.setTextColor(0xFFFF0000);
            }
            // GPS 模式默认认为信号正常（GPS 未启动时 updateNetworkStatusIndicator 会置灰）
            isGpsSignalNormal = true;
            updateNetworkStatusIndicator(true);
            if (navigationMainFragment != null) {
                navigationMainFragment.setGpsMode(true);
            }
        } else {
            // 网络模式：模式文字标"网络"（红色高亮），网络 状态灯变绿/灰
            if (networkModeText != null) {
                networkModeText.setText("网络");
                networkModeText.setTextColor(0xFFFF0000);
            }
            isGpsSignalNormal = false;
            updateNetworkStatusIndicator(false);
            if (navigationMainFragment != null) {
                navigationMainFragment.setGpsMode(false);
            }
        }
    }

    private void startGpsTimeUpdate() {
        // GPS 模式下 networkModeText 由 satelliteCountListener 实时驱动，不再需要每秒 tick
        stopGpsTimeUpdate();
    }

    private void stopGpsTimeUpdate() {
        if (gpsTimeRunnable != null) {
            gpsTimeHandler.removeCallbacks(gpsTimeRunnable);
            gpsTimeRunnable = null;
        }
    }

    private final LocationListener gpsActivityListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            handleGpsLocation(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}
    };

    private final GpsWarmingUp.SatelliteCountListener satelliteCountListener = (usedCount, totalCount) -> {
        runOnUiThread(() -> {
            // GPS 模式：显示卫星数
            if (currentAnnounceMode == AnnounceMode.GPS && networkModeText != null) {
                networkModeText.setText("GPS " + usedCount + "/" + totalCount);
            }
        });
    };

    private void handleGpsLocation(Location location) {
        // 此方法由 GpsWarmingUp 的后台 HandlerThread 调用，重计算全在后台跑，UI 写入统一在主线程。
        if (currentAnnounceMode != AnnounceMode.GPS) {
            return;
        }
        if (realTimeManager == null || realTimeManager.getStationList() == null) {
            return;
        }

        // 坐标转换（纯计算）
        currentGpsLat = location.getLatitude();
        currentGpsLon = location.getLongitude();
        double gcjLat = currentGpsLat;
        double gcjLon = currentGpsLon;

        switch (currentCoordConvertMode) {
            case WGS_TO_GCJ:
                io.sgr.geometry.Coordinate wgsCoord = new io.sgr.geometry.Coordinate(currentGpsLat, currentGpsLon);
                io.sgr.geometry.Coordinate gcjCoordFromWgs = GeometryUtils.wgs2gcj(wgsCoord);
                gcjLat = gcjCoordFromWgs.getLat();
                gcjLon = gcjCoordFromWgs.getLng();
                break;
            case GCJ_TO_WGS:
                io.sgr.geometry.Coordinate gcjCoordForWgs = new io.sgr.geometry.Coordinate(currentGpsLat, currentGpsLon);
                io.sgr.geometry.Coordinate wgsCoordFromGcj = GeometryUtils.gcj2wgs(gcjCoordForWgs);
                gcjLat = wgsCoordFromGcj.getLat();
                gcjLon = wgsCoordFromGcj.getLng();
                break;
            case NO_CONVERT:
            default:
                break;
        }

        // 速度计算（纯计算）
        float speedKmh = calculateRealTimeSpeed(location, gcjLat, gcjLon);

        // 更新高德地图位置（后台线程调用，AmapNavigationView 内部已使用 LocationSource 回调）
        if (navigationMainFragment != null) {
            float bearing = location.hasBearing() ? location.getBearing() : 0f;
            navigationMainFragment.updateMyLocation(gcjLat, gcjLon, bearing);
        }

        // 主线程：更新"信号正常"指示、速度显示、坐标
        final double finalGcjLat = gcjLat;
        final double finalGcjLon = gcjLon;
        final float finalSpeedKmh = speedKmh;
        runOnUiThread(() -> {
            if (currentAnnounceMode != AnnounceMode.GPS) {
                return;
            }
            isGpsSignalNormal = true;
            updateNetworkStatusIndicator(true);
            if (navigationMainFragment != null) {
                navigationMainFragment.updateSpeed(finalSpeedKmh);
            }
            // 坐标信息
            if (speakFragment != null) {
                String coordSystemLabel;
                switch (currentCoordConvertMode) {
                    case NO_CONVERT:
                        coordSystemLabel = "原始";
                        break;
                    case GCJ_TO_WGS:
                        coordSystemLabel = "WGS";
                        break;
                    default:
                        coordSystemLabel = "GCJ-02";
                }
                speakFragment.updateGpsLocation(finalGcjLat, finalGcjLon, coordSystemLabel);
            }
        });

        // 快照当前状态（后台线程读，主线程可能并发改，volatile 已保证可见性）
        final boolean wasInsideStation = isInsideStationRadius;
        final int snapshotLastAnnounced = lastAnnouncedStationIndex;
        final int snapshotLastInside = lastInsideStationIndex;
        final boolean snapshotHasLeftTerminal = hasLeftTerminalStation;
        final int snapshotGpsCurrent = gpsCurrentStationIndex;

        // 遍历所有站点（重计算）
        List<BusApiClient.BusLineStation> stations = realTimeManager.getStationList();
        nearestStationName = "";
        nearestStationDistance = -1;
        nearestStationDirectDistance = -1;
        boolean isInsideRadius = false;
        boolean isBeyondExitRadius = false;
        int currentInsideStationIndex = -1;
        int leavingStationFinal = -1;
        boolean isLeavingTerminal = false;

        final float[] tmpResults = new float[1];
        for (int i = 0; i < stations.size(); i++) {
            BusApiClient.BusLineStation station = stations.get(i);
            double stationLat = station.poiOriginLat;
            double stationLon = station.poiOriginLon;
            if (stationLat == 0 && stationLon == 0) {
                continue;
            }

            // 先用直线距离做一次 cheap 过滤
            Location.distanceBetween(gcjLat, gcjLon, stationLat, stationLon, tmpResults);
            double straightDistance = tmpResults[0];
            double directDistance = straightDistance;
            double alongRouteDist = -1;
            double gpsToRouteDist = -1;
            double distance = straightDistance;

            boolean needAlongRoute = routePoints != null && !routePoints.isEmpty();
            // 剪枝条件：
            //   1) 直线距离 <= enterStationRadius（候选进站点）
            //   2) i 是上一帧 inside 的站点且直线距离 <= 2*exitStationRadius（候选离站点，放大以兜底"沿线近但直线远"）
            //   3) 直线距离 <= STATION_PROXIMITY_THRESHOLD_METERS 且需要 EAT 修正
            boolean isCandidate = straightDistance <= enterStationRadius
                    || (i == snapshotLastInside && straightDistance <= exitStationRadius * 2)
                    || straightDistance <= STATION_PROXIMITY_THRESHOLD_METERS;

            if (needAlongRoute && isCandidate) {
                io.sgr.geometry.utils.RouteGeometryUtils.RouteDistanceResult distResult =
                        io.sgr.geometry.utils.RouteGeometryUtils.calculateDistances(
                                gcjLat, gcjLon, stationLat, stationLon, routePoints);
                directDistance = distResult.directDistance;
                alongRouteDist = distResult.alongRouteDistance;
                gpsToRouteDist = distResult.gpsToRouteDistance;
                distance = alongRouteDist >= 0 ? alongRouteDist : directDistance;
            }

            double distanceForCompare;
            if (currentDistanceMode == DistanceMode.ALONG_ROUTE && needAlongRoute) {
                distanceForCompare = alongRouteDist >= 0 ? alongRouteDist : directDistance;
            } else {
                distanceForCompare = directDistance;
            }
            if (nearestStationDistance < 0 || distanceForCompare < nearestStationDistance) {
                nearestStationDistance = distanceForCompare;
                nearestStationName = station.stationName;
                nearestStationDirectDistance = directDistance;
            }

            if (distanceForCompare <= enterStationRadius) {
                isInsideRadius = true;
                currentInsideStationIndex = i;
                break;
            }

            if (i == snapshotLastInside && distanceForCompare > exitStationRadius) {
                isBeyondExitRadius = true;
            }
        }

        if (isBeyondExitRadius && snapshotLastInside != -1) {
            int leavingIndex = snapshotLastInside;
            int totalStations = stations.size();
            boolean isTerminalStation = leavingIndex >= totalStations - 1;

            if (isTerminalStation) {
                isLeavingTerminal = true;
            }

            leavingStationFinal = leavingIndex;
        } else if (isInsideRadius) {
            int totalStations = stations.size();
            boolean isTerminalStation = currentInsideStationIndex >= totalStations - 1;

            if (currentInsideStationIndex >= 0 && !isTerminalStation) {
                // 普通进站会重置 hasLeftTerminalStation
            }
        }

        // EAT 计算
        int eatRefIndex = currentInsideStationIndex >= 0 ? currentInsideStationIndex :
                (leavingStationFinal >= 0 ? leavingStationFinal : snapshotGpsCurrent);
        String eatText = calculateGpsEatText(stations, currentInsideStationIndex, leavingStationFinal, speedKmh, eatRefIndex);

        // 把后台计算的所有结果打成 final 快照
        final String resultNearestName = nearestStationName;
        final double resultNearestDistance = nearestStationDistance;
        final double resultNearestDirect = nearestStationDirectDistance;
        final String resultEatText = eatText;
        final boolean finalIsInsideRadius = isInsideRadius;
        final boolean finalIsBeyondExitRadius = isBeyondExitRadius;
        final int finalCurrentInsideStationIndex = currentInsideStationIndex;
        final int finalLeavingStationFinal = leavingStationFinal;
        final boolean finalIsLeavingTerminal = isLeavingTerminal;

        // 主线程：状态变量写、报站触发、View 更新
        runOnUiThread(() -> {
            if (currentAnnounceMode != AnnounceMode.GPS) {
                return;
            }
            int totalStations = stations.size();

            // ---- 状态变量更新 ----
            if (finalIsBeyondExitRadius && snapshotLastInside != -1) {
                int leavingIndex = snapshotLastInside;
                boolean isTerminalStation = leavingIndex >= totalStations - 1;
                boolean isStartStation = leavingIndex == 0;

                if (isTerminalStation) {
                    hasLeftTerminalStation = true;
                }
                isInsideStationRadius = false;
                lastInsideStationIndex = -1;
                gpsCurrentStationIndex = leavingIndex;

                if (isStartStation) {
                    lastAnnouncedStationIndex = leavingIndex;
                    announceStation(stations.get(leavingIndex).stationName, leavingIndex, totalStations);
                    Log.d(TAG, "起点站离开触发报站: " + stations.get(leavingIndex).stationName);
                } else if (!isTerminalStation) {
                    announceLeavingStation(stations.get(leavingIndex).stationName, leavingIndex, totalStations);
                    Log.d(TAG, "已触发离站报站: " + stations.get(leavingIndex).stationName);
                } else {
                    Log.d(TAG, "已离开终点站，不报站: " + stations.get(leavingIndex).stationName);
                }
            } else if (finalIsInsideRadius) {
                boolean isTerminalStation = finalCurrentInsideStationIndex >= totalStations - 1;
                boolean isStartStation = finalCurrentInsideStationIndex == 0;

                if (isTerminalStation && snapshotHasLeftTerminal) {
                    // 忽略
                } else if (isStartStation) {
                    // ⭐ 起点站进站时不播报，等到"离开起点站"（在途中）时再播
                    // 避免用户在起点站等车时就听到"欢迎乘坐..."的播报
                    if (snapshotLastAnnounced != finalCurrentInsideStationIndex) {
                        lastAnnouncedStationIndex = finalCurrentInsideStationIndex;
                        Log.d(TAG, "起点站进站，不播报，等待离开起点站时再播: " + stations.get(finalCurrentInsideStationIndex).stationName);
                    }
                } else {
                    if (!isInsideStationRadius || snapshotLastAnnounced != finalCurrentInsideStationIndex) {
                        isInsideStationRadius = true;
                        lastAnnouncedStationIndex = finalCurrentInsideStationIndex;
                        announceStation(stations.get(finalCurrentInsideStationIndex).stationName, finalCurrentInsideStationIndex, totalStations);
                        Log.d(TAG, "已触发报站: " + stations.get(finalCurrentInsideStationIndex).stationName);
                    }
                }
                if (finalCurrentInsideStationIndex >= 0 && !isTerminalStation) {
                    hasLeftTerminalStation = false;
                }
                if (finalCurrentInsideStationIndex >= 0) {
                    gpsCurrentStationIndex = finalCurrentInsideStationIndex;
                }
                if (isTerminalStation && snapshotHasLeftTerminal) {
                    // already handled above
                } else {
                    lastInsideStationIndex = finalCurrentInsideStationIndex;
                }
            }

            // ---- View 更新 ----
            // 转发到 SpeakFragment
            if (speakFragment != null) {
                boolean gpsVisible = currentAnnounceMode == AnnounceMode.GPS;
                speakFragment.setGpsVisible(gpsVisible);
                if (resultNearestDistance >= 0) {
                    speakFragment.updateNearestStation(resultNearestName, resultNearestDistance, resultNearestDirect);
                }
                speakFragment.updateEstimatedInfo(resultEatText);
                speakFragment.setDistanceMode(currentDistanceMode == DistanceMode.STRAIGHT_LINE);
            }

            // 更新原有的 BusLineView
            if (busLineView != null) {
                if (finalIsLeavingTerminal) {
                    busLineView.updateGpsPosition(-1, false);
                } else if (finalIsInsideRadius && finalCurrentInsideStationIndex >= 0) {
                    busLineView.updateGpsPosition(finalCurrentInsideStationIndex, true);
                } else if (!finalIsInsideRadius && finalLeavingStationFinal >= 0) {
                    busLineView.updateGpsPosition(finalLeavingStationFinal, false);
                }
            }

            // ========== 新增：同步 GPS 位置到 NavigationMainFragment ==========
            if (navigationMainFragment != null) {
                if (finalIsLeavingTerminal) {
                    navigationMainFragment.updateGpsPosition(-1, false);
                } else if (finalIsInsideRadius && finalCurrentInsideStationIndex >= 0) {
                    navigationMainFragment.updateGpsPosition(finalCurrentInsideStationIndex, true);
                } else if (!finalIsInsideRadius && finalLeavingStationFinal >= 0) {
                    navigationMainFragment.updateGpsPosition(finalLeavingStationFinal, false);
                }
            }
            // ============================================================

            if (finalIsLeavingTerminal) {
                // nothing
            } else if (finalIsInsideRadius) {
                if (!wasInsideStation) {
                    isInsideStationRadius = true;
                }
            } else {
                if (wasInsideStation) {
                    isInsideStationRadius = false;
                }
            }
        });
    }

    private String calculateGpsEatText(List<BusApiClient.BusLineStation> stations, int currentInsideStationIndex, int leavingStationIndex, float speedKmh, int gpsCurrentStationIndex) {
        if (stations == null || stations.isEmpty()) {
            return "";
        }

        int totalStations = stations.size();
        int nextStationIndex = -1;

        if (currentInsideStationIndex >= 0) {
            if (currentInsideStationIndex + 1 < totalStations) {
                nextStationIndex = currentInsideStationIndex + 1;
            }
        } else if (leavingStationIndex >= 0) {
            if (leavingStationIndex + 1 < totalStations) {
                nextStationIndex = leavingStationIndex + 1;
            }
        } else if (gpsCurrentStationIndex >= 0) {
            if (gpsCurrentStationIndex + 1 < totalStations) {
                nextStationIndex = gpsCurrentStationIndex + 1;
            }
        } else {
            for (int i = 0; i < totalStations; i++) {
                BusApiClient.BusLineStation s = stations.get(i);
                if (s.stationName != null && s.stationName.equals(nearestStationName)) {
                    if (i + 1 < totalStations) {
                        nextStationIndex = i + 1;
                    }
                    break;
                }
            }
        }

        double distanceToNext = 0;
        double distanceToNextDirect = 0;
        int currentStationIndex = -1;
        for (int i = 0; i < totalStations; i++) {
            BusApiClient.BusLineStation s = stations.get(i);
            if (s.stationName != null && s.stationName.equals(nearestStationName)) {
                currentStationIndex = i;
                break;
            }
        }
        if (nextStationIndex >= 0) {
            BusApiClient.BusLineStation nextStation = stations.get(nextStationIndex);
            double stationLat = nextStation.poiOriginLat;
            double stationLon = nextStation.poiOriginLon;
            double segmentDistance = 0;
            if (currentStationIndex >= 0 && currentStationIndex < totalStations - 1) {
                BusApiClient.BusLineStation currentStation = stations.get(currentStationIndex);
                double currentLat = currentStation.poiOriginLat;
                double currentLon = currentStation.poiOriginLon;
                if (routePoints != null && !routePoints.isEmpty()) {
                    io.sgr.geometry.utils.RouteGeometryUtils.RouteDistanceResult segResult =
                            io.sgr.geometry.utils.RouteGeometryUtils.calculateDistances(
                                    currentLat, currentLon, stationLat, stationLon, routePoints);
                    segmentDistance = segResult.alongRouteDistance >= 0 ? segResult.alongRouteDistance : segResult.directDistance;
                } else {
                    float[] results = new float[1];
                    Location.distanceBetween(currentLat, currentLon, stationLat, stationLon, results);
                    segmentDistance = results[0];
                }
            }
            if (routePoints != null && !routePoints.isEmpty()) {
                io.sgr.geometry.utils.RouteGeometryUtils.RouteDistanceResult distResult =
                        io.sgr.geometry.utils.RouteGeometryUtils.calculateDistances(
                                currentGpsLat, currentGpsLon, stationLat, stationLon, routePoints);
                distanceToNext = distResult.alongRouteDistance >= 0 ? distResult.alongRouteDistance : distResult.directDistance;
                distanceToNextDirect = distResult.directDistance;
                Log.d(TAG, String.format(Locale.CHINA, "EAT计算: 下一站[%d]%s 沿线=%.0fm, 直线=%.0fm, gpsToRoute=%.0fm",
                        nextStationIndex, nextStation.stationName, distResult.alongRouteDistance,
                        distResult.directDistance, distResult.gpsToRouteDistance));
            } else {
                float[] results = new float[1];
                Location.distanceBetween(currentGpsLat, currentGpsLon, stationLat, stationLon, results);
                distanceToNext = results[0];
                distanceToNextDirect = distanceToNext;
                Log.d(TAG, String.format(Locale.CHINA, "EAT计算: 下一站[%d]%s 直线=%.0fm (无沿线数据)",
                        nextStationIndex, nextStation.stationName, distanceToNext));
            }
            if (nearestStationDistance >= STATION_PROXIMITY_THRESHOLD_METERS && segmentDistance > 0) {
                double calculatedDistance = segmentDistance - nearestStationDistance - 100;
                if (calculatedDistance > 0) {
                    distanceToNext = calculatedDistance;
                    Log.d(TAG, String.format(Locale.CHINA, "EAT修正: 站点[%d]%s->[%d]%s 站间距离=%.0fm, GPS到站点=%.0fm, 修正后=%.0fm",
                            currentStationIndex, nearestStationName, nextStationIndex, nextStation.stationName,
                            segmentDistance, nearestStationDistance, distanceToNext));
                }
            } else {
                if (nearestStationDistance < STATION_PROXIMITY_THRESHOLD_METERS) {
                    distanceToNext = 0;
                    Log.d(TAG, String.format(Locale.CHINA, "EAT修正: 在站点[%d]%s停靠中，忽略到下一站距离",
                            currentStationIndex, nearestStationName));
                } else {
                    distanceToNext = nearestStationDistance;
                }
            }
        } else {
            Log.d(TAG, "EAT计算: 未找到有效的nextStationIndex");
        }

        double distanceToTerminal = 0;
        if (nextStationIndex >= 0) {
            distanceToTerminal = distanceToNext;
            if (routePoints != null && !routePoints.isEmpty()) {
                for (int i = nextStationIndex + 1; i < totalStations; i++) {
                    BusApiClient.BusLineStation station = stations.get(i);
                    double stationLat = station.poiOriginLat;
                    double stationLon = station.poiOriginLon;
                    if (i > 0) {
                        BusApiClient.BusLineStation prevStation = stations.get(i - 1);
                        double prevLat = prevStation.poiOriginLat;
                        double prevLon = prevStation.poiOriginLon;
                        io.sgr.geometry.utils.RouteGeometryUtils.RouteDistanceResult distResult =
                                io.sgr.geometry.utils.RouteGeometryUtils.calculateDistances(
                                        prevLat, prevLon, stationLat, stationLon, routePoints);
                        double segmentDist = distResult.alongRouteDistance >= 0 ? distResult.alongRouteDistance : distResult.directDistance;
                        distanceToTerminal += segmentDist;
                        Log.d(TAG, String.format(Locale.CHINA, "EAT计算: 段[%d]%s->[%d]%s 沿线=%.0fm, 直线=%.0fm",
                                i - 1, prevStation.stationName, i, station.stationName,
                                distResult.alongRouteDistance, distResult.directDistance));
                    }
                }
            } else {
                for (int i = nextStationIndex; i < totalStations - 1; i++) {
                    BusApiClient.BusLineStation station = stations.get(i);
                    if (station.distanceToNext > 0) {
                        distanceToTerminal += station.distanceToNext;
                    }
                }
                if (distanceToTerminal == 0 && distanceToNext > 0) {
                    distanceToTerminal = distanceToNext;
                    for (int i = nextStationIndex + 1; i < totalStations; i++) {
                        BusApiClient.BusLineStation station = stations.get(i);
                        if (station.distanceToNext > 0) {
                            distanceToTerminal += station.distanceToNext;
                        } else if (i > 0) {
                            BusApiClient.BusLineStation prev = stations.get(i - 1);
                            if (prev.distanceToNext > 0) {
                                distanceToTerminal += prev.distanceToNext;
                            }
                        }
                    }
                }
            }
        }

        Log.d(TAG, String.format(Locale.CHINA, "EAT计算: distanceToNext=%.0fm, distanceToTerminal=%.0fm",
                distanceToNext, distanceToTerminal));
        Log.d(TAG,String.format(Locale.CHINA, "最近站点: %s (沿线%s/直线%s)",
                nearestStationName, formatDistance(nearestStationDistance), formatDistance(nearestStationDirectDistance)));
        String nextEat = "--";
        String terminalEat = "--";
        Log.d(TAG, String.format(Locale.CHINA, "当前站到下一站沿线距离: %s", formatDistance(distanceToNext)));

        if (speedKmh > MIN_VALID_SPEED_KMH) {
            double speedMps = speedKmh / 3.6;
            if (distanceToNext > 0) {
                int secondsNext = (int) (distanceToNext / speedMps);
                nextEat = formatEtaTime(secondsNext);
            }
            if (distanceToTerminal > 0) {
                int secondsTerminal = (int) (distanceToTerminal / speedMps);
                terminalEat = formatEtaTime(secondsTerminal);
            }
        } else {
            if (distanceToNext > 0) {
                nextEat = formatDistance(distanceToNext);
            }
            if (distanceToTerminal > 0) {
                terminalEat = formatDistance(distanceToTerminal);
            }
        }

        String result = String.format(Locale.CHINA, "预计：下一站 %s，终点 %s", nextEat, terminalEat);
        Log.d(TAG, "EAT计算结果: " + result);
        return result;
    }

    private String formatEtaTime(int totalSeconds) {
        if (totalSeconds < 60) {
            return totalSeconds + "秒";
        } else if (totalSeconds < 3600) {
            int minutes = totalSeconds / 60;
            int seconds = totalSeconds % 60;
            if (seconds > 0) {
                return minutes + "分" + seconds + "秒";
            } else {
                return minutes + "分钟";
            }
        } else {
            int hours = totalSeconds / 3600;
            int minutes = (totalSeconds % 3600) / 60;
            if (minutes > 0) {
                return hours + "时" + minutes + "分";
            } else {
                return hours + "小时";
            }
        }
    }

    private float calculateRealTimeSpeed(Location location, double gcjLat, double gcjLon) {
        // 整个方法体在 speedLock 内执行，确保后台 HandlerThread 跑计算时与主线程 reset/clear 互斥
        synchronized (speedLock) {
            long currentTime = System.currentTimeMillis();
            float speedKmh = 0f;

            boolean hasGpsSpeed = location.hasSpeed();
            float gpsSpeedMps = hasGpsSpeed ? location.getSpeed() : 0f;
            float gpsSpeedKmh = gpsSpeedMps * 3.6f;

            float computedSpeedKmh = 0f;
            boolean hasComputedSpeed = false;
            if (locationUpdateCount >= 1 && lastLocationTimeForSpeed > 0) {
                long timeDiff = currentTime - lastLocationTimeForSpeed;
                if (timeDiff > 0 && timeDiff < 10000) {
                    float[] results = new float[1];
                    android.location.Location.distanceBetween(lastLocationLat, lastLocationLon, gcjLat, gcjLon, results);
                    double distanceMoved = results[0];
                    if (distanceMoved < 0.5) {
                        computedSpeedKmh = 0f;
                        hasComputedSpeed = true;
                    } else {
                        computedSpeedKmh = (float) (distanceMoved / (timeDiff / 1000.0)) * 3.6f;
                        hasComputedSpeed = true;
                    }
                }
            }
            locationUpdateCount++;
            lastLocationLat = gcjLat;
            lastLocationLon = gcjLon;
            lastLocationTimeForSpeed = currentTime;

            if (hasGpsSpeed) {
                if (hasComputedSpeed && computedSpeedKmh < MIN_VALID_SPEED_KMH && gpsSpeedKmh < MIN_VALID_SPEED_KMH) {
                    speedKmh = 0f;
                } else {
                    speedKmh = gpsSpeedKmh;
                }
            } else if (hasComputedSpeed) {
                speedKmh = computedSpeedKmh;
            }

            if (speedKmh < MIN_VALID_SPEED_KMH) {
                speedKmh = 0f;
            }

            speedWindow.add(speedKmh);
            if (speedWindow.size() > SPEED_WINDOW_SIZE) {
                speedWindow.remove(0);
            }
            if (!speedWindow.isEmpty()) {
                float sum = 0f;
                for (float s : speedWindow) {
                    sum += s;
                }
                currentSmoothedSpeedKmh = sum / speedWindow.size();
            } else {
                currentSmoothedSpeedKmh = speedKmh;
            }
        }

        startSpeedTimeout();

        return currentSmoothedSpeedKmh;
    }

    private void startSpeedTimeout() {
        stopSpeedTimeout();
        speedTimeoutRunnable = () -> {
            synchronized (speedLock) {
                currentSmoothedSpeedKmh = 0f;
                speedWindow.clear();
            }
            if (navigationMainFragment != null) {
                navigationMainFragment.resetSpeed();
            }
            Log.d(TAG, "速度超时未更新，归零");
        };
        speedTimeoutHandler.postDelayed(speedTimeoutRunnable, SPEED_TIMEOUT_MS);
    }

    private void stopSpeedTimeout() {
        if (speedTimeoutRunnable != null) {
            speedTimeoutHandler.removeCallbacks(speedTimeoutRunnable);
            speedTimeoutRunnable = null;
        }
    }

    private void announceStation(String stationName, int stationIndex, int totalStations) {
        TTSUtils tts = TTSUtils.getInstance(this);
        boolean isStartStation = stationIndex == 0;
        boolean isTerminalStation = stationIndex == totalStations - 1;

        if (isStartStation) {

            String nextStationName = "";
            if (stationIndex + 1 < totalStations) {
                List<BusApiClient.BusLineStation> stations = realTimeManager.getStationList();
                nextStationName = stations.get(stationIndex + 1).stationName;
            }
            tts.playGpsStartStationAnnouncement(lineName, startStation, endStation, nextStationName);
            setNextStationInfoText(nextStationName);
        } else if (isTerminalStation) {
            tts.playGpsTerminalStationAnnouncement(stationName);
            nextStationInfo.setText(stationName + " 到了！  We are now at " + stationName + " !");
        } else {
            tts.playGpsMiddleStationAnnouncement(stationName);
            nextStationInfo.setText(stationName + " 到了！  We are now at " + stationName + " !");
        }
    }

    private void announceLeavingStation(String stationName, int stationIndex, int totalStations) {
        TTSUtils tts = TTSUtils.getInstance(this);
        boolean isTerminalStation = stationIndex + 1 >= totalStations - 1;
        String nextStationName = "";
        if (stationIndex + 1 < totalStations) {
            List<BusApiClient.BusLineStation> stations = realTimeManager.getStationList();
            nextStationName = stations.get(stationIndex + 1).stationName;
        }
        tts.playGpsLeavingStationAnnouncement(nextStationName, isTerminalStation);
        setNextStationInfoText(nextStationName);
    }

    /**
     * 设置"下一站"信息文本。
     * GPS 模式追加"扫码评价"中英双语提示，网络模式保持原版文案。
     */
    private void setNextStationInfoText(String stationName) {
        if (currentAnnounceMode == AnnounceMode.GPS) {
            nextStationInfo.setText("下一站：" + stationName + QR_HINT_CN + "    Next Station:" + stationName + QR_HINT_EN);
        } else {
            nextStationInfo.setText("下一站：" + stationName + "    Next Station:" + stationName);
        }
        if (navigationMainFragment != null && stationName != null) {
            navigationMainFragment.updateNextStation(stationName);
        }
    }

    private void updatePriceTips(BusApiClient.BusLineDirection lineDirection) {
        String[] priceTips = buildPriceTips(lineDirection);
        if (priceTips != null) {
            int[] priceColors = new int[priceTips.length];
            for (int i = 0; i < priceTips.length; i++) {
                priceColors[i] = TIPS_COLOR_PURPLE;
            }
            int baseCount = TIPS_TEXT_BASE.length;
            currentTipsText = new String[priceTips.length + baseCount];
            currentTipsColor = new int[currentTipsText.length];
            System.arraycopy(priceTips, 0, currentTipsText, 0, priceTips.length);
            System.arraycopy(priceColors, 0, currentTipsColor, 0, priceColors.length);
            System.arraycopy(TIPS_TEXT_BASE, 0, currentTipsText, priceTips.length, baseCount);
            System.arraycopy(TIPS_COLOR_BASE, 0, currentTipsColor, priceTips.length, baseCount);
        } else {
            currentTipsText = TIPS_TEXT_BASE;
            currentTipsColor = TIPS_COLOR_BASE;
        }
    }

    private String toChineseNumber(double number) {
        if (number == 1.0) return "一";
        if (number == 2.0) return "二";
        if (number == 3.0) return "三";
        if (number == 4.0) return "四";
        if (number == 5.0) return "五";
        if (number == 6.0) return "六";
        if (number == 7.0) return "七";
        if (number == 8.0) return "八";
        if (number == 9.0) return "九";
        if (number == 10.0) return "十";
        return formatPrice(number);
    }

    private String[] buildPriceTips(BusApiClient.BusLineDirection lineDirection) {
        double price = lineDirection.totalPrice;
        int lineType = lineDirection.lineType;
        String lineTypeName = lineDirection.lineTypeName;

        boolean isCityBus = (lineType == 1 || "城市".equals(lineTypeName));
        boolean isIntercityBus = (lineTypeName != null && lineTypeName.contains("城乡"));

        if (!isCityBus && !isIntercityBus && price > 0) {
            if (price == 1.0) {
                isCityBus = true;
            } else if (price >= 2.0) {
                isIntercityBus = true;
            }
        }

        if (isCityBus && price == 1.0) {
            return new String[]{"无人售票   票价一元"};
        } else if (isIntercityBus && price == 2.0) {
            return new String[]{"无人售票   票价二元"};
        } else if (isIntercityBus && price > 0) {
            return new String[]{"多票制二~" + toChineseNumber(price) + "元", "上下车均需刷卡扫码"};
        } else if (price == 1.0) {
            return new String[]{"无人售票   票价一元"};
        }

        return null;
    }

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
            initViews(savedInstanceState);
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

    private void initViews(Bundle savedInstanceState) {

        routeNumber = findViewById(R.id.route_number);
        routeNumber.setText(lineName);
        routeNumber.setTextColor(0xFF00FF00);
        routeNumber.setGravity(0);
        routeNumber.setScrollSpeed(180f);
        Typeface dottedSongti = Typeface.createFromAsset(getAssets(), "fonts/ZiTiGuanJiaBoDian-2.ttf");
        routeNumber.setTypeface(dottedSongti);
        int maxWidth = (int) (120 * getResources().getDisplayMetrics().density);
        int textWidth = (int) routeNumber.getTextWidth();
        if (textWidth > 0 && textWidth < maxWidth) {
            routeNumber.getLayoutParams().width = textWidth;
        }

        LinearLayout noticeBar = findViewById(R.id.notice_bar);
        navHasNotification = findViewById(R.id.nav_has_notification);
        navHasNotification.setText("0");
        navHasNotification.setVisibility(View.GONE);
        noticeText = findViewById(R.id.notice_text);
        noticeBar.setVisibility(View.GONE);
        HorizontalScrollTextView endStationName = findViewById(R.id.end_station_name);
        endStationName.setGravity(2);
        endStationName.setText(endStation);
        endStationName.setTypeface(dottedSongti);
        endStationName.setScrollSpeed(180f);
        tips = findViewById(R.id.tips);
        tips.setTypeface(dottedSongti);
        tips.setGravity(1);
        startTipsAnimation();
        nextStationInfo = findViewById(R.id.next_station_info);
        nextStationInfo.setTypeface(dottedSongti);
        nextStationInfo.setTextColor(0xFFFF0000);
        nextStationInfo.setTextSize(30f);
        nextStationInfo.setText("欢迎乘坐 " + lineName + " 公交车" + "    " + "Welcome aboard the No." + formatLineNameForEnglish(lineName) +  " bus.");
        nextStationInfo.setScrollSpeed(180f);
        accessibilityIcon = findViewById(R.id.accessibility_icon);
        accessibilityIcon.setVisibility(View.GONE);
        updateTicketPrice();
        // ⭐ 初始化导航模块：旧版在 Activity 内 findViewById，
        // 现已迁移到 NavigationMainFragment，由 FragmentManager 加载并通过公开 API 交互。
        setupNavigationContent();
        // ⭐ 高德地图初始化已迁移到 NavigationMainFragment.onViewCreated()，
        //   这里不再需要手动创建 AmapNavigationView 和注册 layout listener。

        errorIndicator = findViewById(R.id.error_indicator);
        errorIndicator.setOnClickListener(v -> {
            if (lastErrorDetail != null && !lastErrorDetail.isEmpty()) {
                new AlertDialog.Builder(BusLineDetailActivity.this)
                    .setTitle("错误详情")
                    .setMessage(lastErrorDetail)
                    .setPositiveButton("确定", null)
                    .show();
            } else if (lastErrorMessage != null && !lastErrorMessage.isEmpty()) {
                new AlertDialog.Builder(BusLineDetailActivity.this)
                    .setTitle("错误详情")
                    .setMessage(lastErrorMessage)
                    .setPositiveButton("确定", null)
                    .show();
            }
        });

        networkModeText = findViewById(R.id.mode_text);
        modeTips = findViewById(R.id.mode_tips);
        modeSwitch = findViewById(R.id.mode_switch);
        networkStatusIndicator = findViewById(R.id.network_status_indicator);

        View.OnClickListener modeSwitchListener = v -> {
            if (currentAnnounceMode == AnnounceMode.GPS) {
                currentAnnounceMode = AnnounceMode.NETWORK;
            } else {
                currentAnnounceMode = AnnounceMode.GPS;
            }
            updateAnnounceModeState();
        };
        modeSwitch.setOnClickListener(modeSwitchListener);
        updateAnnounceModeDisplay();
        modeTips.setTypeface(dottedSongti);
        networkModeText.setTypeface(dottedSongti);
        // ⭐ gpsSpeedText 已迁移到 NavigationMainFragment，字体设置在 fragment.onViewCreated() 中完成

        startErrorBlinkAnimation();
    }

    private void setupListeners() {
        navigationMainFragment.setSwapOrientation(v -> swapDirection());
    }
    // 添加一个辅助方法来处理线路名称
    private String formatLineNameForEnglish(String lineName) {
        if (lineName == null || lineName.isEmpty()) {
            return lineName;
        }
        // 正则匹配：以数字结尾，后面跟"路"，然后可能还有内容
        // 匹配模式：数字 + "路"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^(\\d+)路(.*)$");
        java.util.regex.Matcher matcher = pattern.matcher(lineName);

        if (matcher.find()) {
            // 如果是数字+路的格式，去掉"路"，保留数字和后面的内容
            String number = matcher.group(1);  // 数字部分
            String rest = matcher.group(2);     // 后面的内容（如"公交车"等）
            return number + rest;
        }
        return lineName;
    }
    /**
     * 加载导航内容：
     *   - NavigationMainFragment 一次性添加，**常驻**（不销毁，保持地图状态）
     *   - MapSettingsFragment **按需添加**（用户首次进入设置页时才创建）
     *   - 退出设置页时**移除**该 fragment（避免内存堆积）
     * <p>
     * 主页（NavigationMainFragment） = 地图显示区 + 下方线路信息 + 右侧信息栏
     * 地图设置页（MapSettingsFragment） = 占位页
     */
    private void setupNavigationContent() {
        // 1. 一次性添加主页 fragment（用 add，常驻不销毁）
        navigationMainFragment = NavigationMainFragment.newInstance(lineName, endStation);
        getSupportFragmentManager().beginTransaction()
                .add(R.id.nav_content_container, navigationMainFragment, "NAV_MAIN")
                .commit();

        // 2. 绑定左侧"地图"图标点击
        View navIconMap = findViewById(R.id.nav_icon_map);
        navIconMapImg = findViewById(R.id.nav_icon_map_img);
        navIconMapText = findViewById(R.id.nav_icon_map_text);
        if (navIconMap != null) {
            navIconMap.setOnClickListener(v -> toggleNavigationPage());
        }

        // 3. 绑定左侧"排班"图标点击
        View navIconSchedule = findViewById(R.id.nav_icon_schedule);
        navIconScheduleImg = findViewById(R.id.nav_icon_schedule_img);
        navIconScheduleText = findViewById(R.id.nav_icon_schedule_text);
        if (navIconSchedule != null) {
            navIconSchedule.setOnClickListener(v -> toggleSchedulePage());
        }
        // 4. 绑定左侧"消息"图标点击
        View navIconMessageView = findViewById(R.id.nav_icon_message);
        if (navIconMessageView != null) {
            navIconMessageImg = navIconMessageView.findViewById(R.id.nav_icon_message_img);
            navIconMessageText = navIconMessageView.findViewById(R.id.nav_icon_message_text);
            navIconMessageView.setOnClickListener(v -> toggleMessagePage());
        }
        // 5. 绑定左侧"喊话"图标点击
        View navIconSpeakView = findViewById(R.id.nav_icon_speak);
        if (navIconSpeakView != null) {
            navIconSpeakImg = navIconSpeakView.findViewById(R.id.nav_icon_speak_img);
            navIconSpeakText = navIconSpeakView.findViewById(R.id.nav_icon_speak_text);
            navIconSpeakView.setOnClickListener(v -> toggleSpeakPage());
        }

        // 6. 绑定左侧"更多应用"图标点击
                View navIconMoreView = findViewById(R.id.nav_icon_more);
                if (navIconMoreView != null) {
                    navIconMoreImg = navIconMoreView.findViewById(R.id.nav_icon_more_img);
                    navIconMoreView.setOnClickListener(v -> toggleMorePage());
                }
    }

    /**
     * 切换更多应用页：显示/隐藏 MoreFragment
     */
    private void toggleMorePage() {
        FragmentManager fm = getSupportFragmentManager();
        Fragment mainFrag = fm.findFragmentByTag("NAV_MAIN");
        if (mainFrag == null) {
            Log.w(TAG, "toggleMorePage: main fragment missing, abort");
            return;
        }
        FragmentTransaction tx = fm.beginTransaction();

        // 如果当前在更多应用页，则关闭它
        if (isShowingMore) {
            Fragment moreFrag = fm.findFragmentByTag("MORE");
            if (moreFrag != null) {
                tx.remove(moreFrag);
            }
            isShowingMore = false;
            // 恢复更多应用图标
            if (navIconMoreImg != null) navIconMoreImg.setImageResource(R.drawable.yingyong);

            // 同时清理其他非主页页面
            cleanupOtherPages(tx);

            tx.show(mainFrag);
            tx.commit();
        } else {
            // 不在更多应用页：先处理其他非主页页面
            cleanupOtherPages(tx);

            // 隐藏主页
            tx.hide(mainFrag);
            // 创建更多应用页
            moreFragment = MoreFragment.newInstance();
            moreFragment.updatePriceText(priceText);
            tx.add(R.id.nav_content_container, moreFragment, "MORE");
            // 更多应用图标变为主页图标
            if (navIconMoreImg != null) navIconMoreImg.setImageResource(R.drawable.ic_nav_home);
            isShowingMore = true;
            tx.commit();
        }
    }
    /**
     * 清理所有非主页页面（地图设置页、排班页、消息页、喊话页、更多应用页）
     */
    private void cleanupOtherPages(FragmentTransaction tx) {
        // 清理地图设置页
        if (isShowingMapSettings) {
            Fragment settingsFrag = getSupportFragmentManager().findFragmentByTag("MAP_SETTINGS");
            if (settingsFrag != null) {
                tx.remove(settingsFrag);
            }
            if (navIconMapImg != null) navIconMapImg.setImageResource(R.drawable.huibao);
            if (navIconMapText != null) navIconMapText.setText("地图");
            isShowingMapSettings = false;
        }
        // 清理排班页
        if (isShowingSchedule) {
            Fragment scheduleFrag = getSupportFragmentManager().findFragmentByTag("SCHEDULE");
            if (scheduleFrag != null) {
                tx.remove(scheduleFrag);
            }
            if (navIconScheduleImg != null) navIconScheduleImg.setImageResource(R.drawable.paiban);
            if (navIconScheduleText != null) navIconScheduleText.setText("排班");
            isShowingSchedule = false;
        }
        // 清理消息页
        if (isShowingMessage) {
            Fragment messageFrag = getSupportFragmentManager().findFragmentByTag("MESSAGE");
            if (messageFrag != null) {
                tx.remove(messageFrag);
            }
            if (navIconMessageImg != null) navIconMessageImg.setImageResource(R.drawable.xiaoxi);
            if (navIconMessageText != null) navIconMessageText.setText("消息");
            isShowingMessage = false;
        }
        // 清理喊话页
        if (isShowingSpeak) {
            Fragment speakFrag = getSupportFragmentManager().findFragmentByTag("SPEAK");
            if (speakFrag != null) {
                tx.remove(speakFrag);
            }
            if (navIconSpeakImg != null) navIconSpeakImg.setImageResource(R.drawable.hanhua);
            if (navIconSpeakText != null) navIconSpeakText.setText("报站");
            isShowingSpeak = false;
        }
        // 清理更多应用页
        if (isShowingMore) {
            Fragment moreFrag = getSupportFragmentManager().findFragmentByTag("MORE");
            if (moreFrag != null) {
                tx.remove(moreFrag);
            }
            if (navIconMoreImg != null) navIconMoreImg.setImageResource(R.drawable.yingyong);
            isShowingMore = false;
        }
    }
    /**
     * 切换喊话页：显示/隐藏 SpeakFragment
     */
    private void toggleSpeakPage() {
        FragmentManager fm = getSupportFragmentManager();
        Fragment mainFrag = fm.findFragmentByTag("NAV_MAIN");
        if (mainFrag == null) {
            Log.w(TAG, "toggleSpeakPage: main fragment missing, abort");
            return;
        }
        FragmentTransaction tx = fm.beginTransaction();

        // 如果当前在喊话页，则关闭它
        if (isShowingSpeak) {
            Fragment speakFrag = fm.findFragmentByTag("SPEAK");
            if (speakFrag != null) {
                tx.remove(speakFrag);
                speakFragment = null;
            }
            isShowingSpeak = false;
            // 恢复喊话图标
            if (navIconSpeakImg != null) navIconSpeakImg.setImageResource(R.drawable.hanhua);
            if (navIconSpeakText != null) navIconSpeakText.setText("报站");

            // 同时清理其他非主页页面
            cleanupOtherPages(tx);

            tx.show(mainFrag);
            tx.commit();
        } else {
            // 不在喊话页：先处理其他非主页页面
            cleanupOtherPages(tx);

            // 隐藏主页
            tx.hide(mainFrag);
            // 创建喊话页
            speakFragment = SpeakFragment.newInstance();
            tx.add(R.id.nav_content_container, speakFragment, "SPEAK");
            // 同步半径值到 Fragment
            speakFragment.setEnterStationRadius((float) enterStationRadius);
            speakFragment.setExitStationRadius((float) exitStationRadius);
            // 监听半径变化，同步回 Activity
            speakFragment.setOnRadiusChangedListener(new SpeakFragment.OnRadiusChangedListener() {
                @Override
                public void onEnterRadiusChanged(float radius) {
                    enterStationRadius = radius;
                }

                @Override
                public void onExitRadiusChanged(float radius) {
                    exitStationRadius = radius;
                }
            });
            // 监听距离模式点击
            speakFragment.initDistanceModeToggle(isStraightLine -> {
                currentDistanceMode = isStraightLine ? DistanceMode.STRAIGHT_LINE : DistanceMode.ALONG_ROUTE;
            });
            // 喊话图标变为主页图标
            if (navIconSpeakImg != null) navIconSpeakImg.setImageResource(R.drawable.ic_nav_home);
            if (navIconSpeakText != null) navIconSpeakText.setText("主页");
            isShowingSpeak = true;
            tx.commit();
        }
    }

    /** 当前是否显示消息页 */
    private boolean isShowingMessage = false;

    /**
     * 切换消息页：显示/隐藏 MessageFragment
     */
    private void toggleMessagePage() {
        FragmentManager fm = getSupportFragmentManager();
        Fragment mainFrag = fm.findFragmentByTag("NAV_MAIN");
        if (mainFrag == null) {
            Log.w(TAG, "toggleMessagePage: main fragment missing, abort");
            return;
        }
        FragmentTransaction tx = fm.beginTransaction();

        if (isShowingMessage) {
            // 关闭消息页
            Fragment messageFrag = fm.findFragmentByTag("MESSAGE");
            if (messageFrag != null) {
                tx.remove(messageFrag);
            }
            isShowingMessage = false;
            if (navIconMessageImg != null) navIconMessageImg.setImageResource(R.drawable.xiaoxi);
            if (navIconMessageText != null) navIconMessageText.setText("消息");

            // ✅ 清理所有其他页面
            cleanupOtherPages(tx);

            tx.show(mainFrag);
            tx.commitNow();
        } else {
            // 打开消息页前先清理所有其他页面
            cleanupOtherPages(tx);

            tx.hide(mainFrag);
            MessageFragment messageFragment = MessageFragment.newInstance(currentNotificationText);
            tx.add(R.id.nav_content_container, messageFragment, "MESSAGE");
            if (navIconMessageImg != null) navIconMessageImg.setImageResource(R.drawable.ic_nav_home);
            if (navIconMessageText != null) navIconMessageText.setText("主页");
            isShowingMessage = true;
            tx.commitNow();
        }
    }

    private void toggleNavigationPage() {
        FragmentManager fm = getSupportFragmentManager();
        Fragment mainFrag = fm.findFragmentByTag("NAV_MAIN");
        if (mainFrag == null) {
            Log.w(TAG, "toggleNavigationPage: main fragment missing, abort");
            return;
        }
        FragmentTransaction tx = fm.beginTransaction();

        if (isShowingMapSettings) {
            // 切回主页
            Fragment settingsFrag = fm.findFragmentByTag("MAP_SETTINGS");
            if (settingsFrag != null) {
                tx.remove(settingsFrag);
            }
            tx.show(mainFrag);
            // 恢复地图图标
            if (navIconMapImg != null) navIconMapImg.setImageResource(R.drawable.huibao);
            if (navIconMapText != null) navIconMapText.setText("地图");
            isShowingMapSettings = false;
        } else {
            // 先清理其他非主页页面
            cleanupOtherPages(tx);

            // 切到地图设置页
            tx.hide(mainFrag);
            MapSettingsFragment mapSettingsFragment = new MapSettingsFragment();
            tx.add(R.id.nav_content_container, mapSettingsFragment, "MAP_SETTINGS");
            // 地图图标变为主页图标
            if (navIconMapImg != null) navIconMapImg.setImageResource(R.drawable.ic_nav_home);
            if (navIconMapText != null) navIconMapText.setText("主页");
            isShowingMapSettings = true;
        }
        tx.commit();
    }
    /** 当前线路公告内容 */
    private String currentNotificationText = null;
    /**
     * 切换排班页：显示/隐藏 ScheduleFragment
     */
    private void toggleSchedulePage() {
        FragmentManager fm = getSupportFragmentManager();
        Fragment mainFrag = fm.findFragmentByTag("NAV_MAIN");
        if (mainFrag == null) {
            Log.w(TAG, "toggleSchedulePage: main fragment missing, abort");
            return;
        }
        FragmentTransaction tx = fm.beginTransaction();

        if (isShowingSchedule) {
            // 当前在排班页：销毁排班页，显示主页，恢复排班图标
            Fragment scheduleFrag = fm.findFragmentByTag("SCHEDULE");
            if (scheduleFrag != null) {
                tx.remove(scheduleFrag);
            }
            tx.show(mainFrag);
            // 恢复排班图标
            if (navIconScheduleImg != null) navIconScheduleImg.setImageResource(R.drawable.paiban);
            if (navIconScheduleText != null) navIconScheduleText.setText("排班");
            isShowingSchedule = false;
            tx.commit();
        } else {
            // 先清理其他非主页页面
            cleanupOtherPages(tx);

            // 隐藏主页
            tx.hide(mainFrag);
            ScheduleFragment scheduleFragment = ScheduleFragment.newInstance();
            tx.add(R.id.nav_content_container, scheduleFragment, "SCHEDULE");
            // 排班图标变为主页图标
            if (navIconScheduleImg != null) navIconScheduleImg.setImageResource(R.drawable.ic_nav_home);
            if (navIconScheduleText != null) navIconScheduleText.setText("主页");
            isShowingSchedule = true;
            // 执行事务
            tx.commit();
            // 确保 Fragment 视图创建后加载数据
            fm.executePendingTransactions();
            scheduleFragment.loadData(busApiClient, getCurrentDirectionData(), lineName);
        }
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
                        // 保存公告内容
                        currentNotificationText = response.data.text;
                        if (response.data.hasNotification) {
                            navHasNotification.setText("1");
                            navHasNotification.setVisibility(View.VISIBLE);
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
                        } else if (isTwoWayLine && startStation != null && endStation != null) {
                            if (startStation.equals(response.data.up.startStation) && endStation.equals(response.data.up.endStation)) {
                                currentDirection = 1;
                            } else if (startStation.equals(response.data.down.startStation) && endStation.equals(response.data.down.endStation)) {
                                currentDirection = 2;
                            }
                        } else if (!isTwoWayLine) {
                            if (response.data.up != null) {
                                currentDirection = 1;
                            } else if (response.data.down != null) {
                                currentDirection = 2;
                            }
                        }

                        runOnUiThread(() -> {
                            try {
                                if (isTwoWayLine) {
                                    navigationMainFragment.setLoopLine(false);
                                } else {
                                    navigationMainFragment.setLoopLine(true);
                                    navigationMainFragment.updateRouteNo(lineName + "（环线）");
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
        trackedVehiclePlate = null;
        Log.e(TAG + "-BusInfo-", "切换方向到: " + (currentDirection == 1 ? "上行" : "下行"));
        updateStartEndStations();
        showDirection();

        nextStationInfo.setText("欢迎乘坐" + lineName + "公交车");

        if (currentAnnounceMode == AnnounceMode.GPS) {
            GpsWarmingUp.removeListener(gpsActivityListener);
            lastAnnouncedStationIndex = -1;
            isInsideStationRadius = false;
            lastInsideStationIndex = -1;
            gpsCurrentStationIndex = -1;
            realTimeManager.stopTracking();
            realTimeManager.startTracking(getCurrentDirectionId(), this);
            GpsWarmingUp.addListener(gpsActivityListener);
            GpsWarmingUp.addSatelliteListener(satelliteCountListener);
            Location lastLocation = GpsWarmingUp.getLastKnownLocation();
            if (lastLocation != null) {
                // post 到 GPS 后台线程，避免重计算阻塞主线程
                final Location snapshot = lastLocation;
                GpsWarmingUp.postToGpsThread(() -> handleGpsLocation(snapshot));
            }
        }
    }

    private void updateStartEndStations() {
        if (cachedResponse == null || cachedResponse.data == null) return;

        BusApiClient.BusLineDirection lineDirection = getCurrentDirectionData();

        if (lineDirection != null) {
            runOnUiThread(() -> {
                HorizontalScrollTextView endStationName = findViewById(R.id.end_station_name);
                endStationName.setText(lineDirection.endStation);
                startStation = lineDirection.startStation;
                endStation = lineDirection.endStation;
                if (navigationMainFragment != null) {
                    navigationMainFragment.updateDirection(lineDirection.endStation);
                }
            });
        }
    }

    private BusApiClient.BusLineDirection getCurrentDirectionData() {
        Log.d(TAG, "getCurrentDirectionData: currentDirection=" + currentDirection);

        if (cachedResponse == null || cachedResponse.data == null) {
            Log.w(TAG, "getCurrentDirectionData: cachedResponse或data为null");
            return null;
        }

        if (currentDirection == 1 && cachedResponse.data.up != null) {
            Log.d(TAG, "返回上行数据，stationList size=" +
                    (cachedResponse.data.up.stationList == null ? "null" : cachedResponse.data.up.stationList.size()));
            return cachedResponse.data.up;
        } else if (currentDirection == 2 && cachedResponse.data.down != null) {
            Log.d(TAG, "返回下行数据，stationList size=" +
                    (cachedResponse.data.down.stationList == null ? "null" : cachedResponse.data.down.stationList.size()));
            return cachedResponse.data.down;
        }

        Log.w(TAG, "getCurrentDirectionData: 未找到对应方向的数据");
        return null;
    }

    @SuppressLint("SetTextI18n")
    private void showDirection() {
        BusApiClient.BusLineDirection lineDirection = getCurrentDirectionData();
        // 添加日志
        Log.d(TAG, "=== showDirection 开始 ===");
        Log.d(TAG, "currentDirection = " + currentDirection);
        Log.d(TAG, "cachedResponse = " + (cachedResponse == null ? "null" : "not null"));
        if (lineDirection == null) {
            Log.e(TAG + "-BusInfo-", "显示方向失败: 方向数据为空");
            Log.d(TAG, "cachedResponse.data.up = " + (cachedResponse != null && cachedResponse.data.up != null ? "not null" : "null"));
            Log.d(TAG, "cachedResponse.data.down = " + (cachedResponse != null && cachedResponse.data.down != null ? "not null" : "null"));
            return;
        }
        Log.d(TAG, "lineDirection.id = " + lineDirection.id);
        Log.d(TAG, "lineDirection.stationList size = " + (lineDirection.stationList == null ? "null" : lineDirection.stationList.size()));
        if (navigationMainFragment != null) {
            navigationMainFragment.setLineData(lineDirection, isTwoWayLine, currentDirection);
        }
        // 更新导航卡片的方向（终点站）
        if (navigationMainFragment != null && lineDirection.endStation != null) {
            navigationMainFragment.updateDirection(lineDirection.endStation);
        }

        if (realTimeManager != null) {
            realTimeManager.stopTracking();
        }

        routePoints = io.sgr.geometry.utils.RouteGeometryUtils.parseGeometry(lineDirection.geometry);

        // 在高德地图上绘制路线（GCJ-02 坐标，与高德原生坐标系一致）
        if (navigationMainFragment != null && routePoints != null && !routePoints.isEmpty()) {
            java.util.List<com.amap.api.maps.model.LatLng> mapPoints = new java.util.ArrayList<>();
            for (io.sgr.geometry.Coordinate c : routePoints) {
                // routePoints 已经是 GCJ-02 坐标，可直接使用
                mapPoints.add(new com.amap.api.maps.model.LatLng(c.getLat(), c.getLng()));
            }
            if (navigationMainFragment != null) {
                navigationMainFragment.drawRoute(mapPoints);
            }
        }

        updateAccessibilityTag(lineDirection);
        updateBusTimes(lineDirection);
        updateRouteSummary(lineDirection);
        updateTicketPrice();
        updatePriceTips(lineDirection);
        startTipsAnimation();
        setupStationList(lineDirection);
        Log.d(TAG, "setupStationList 调用完成");
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
                accessibilityIcon.setVisibility(View.VISIBLE);
            } else {
                accessibilityIcon.setVisibility(View.GONE);
            }
        });
    }

    private void updateBusTimes(BusApiClient.BusLineDirection lineDirection) {
        // 不再需要 findViewById，改为通过 Fragment 更新
        try {
            // 解析原始时间 00:00:00
            SimpleDateFormat inputFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            // 输出格式 00:00
            SimpleDateFormat outputFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

            Date firstDate = inputFormat.parse(lineDirection.startFirst);
            Date lastDate = inputFormat.parse(lineDirection.startLast);

            String firstBusTimeStr = outputFormat.format(firstDate);
            String lastBusTimeStr = outputFormat.format(lastDate);

            // 通过 Fragment 更新首末班车时间
            if (navigationMainFragment != null) {
                navigationMainFragment.updateFirstBusTime(firstBusTimeStr);
                navigationMainFragment.updateLastBusTime(lastBusTimeStr);
            }
        } catch (ParseException e) {
            e.printStackTrace();
            // 解析失败时使用原值或截取
            if (navigationMainFragment != null) {
                navigationMainFragment.updateFirstBusTime(lineDirection.startFirst);
                navigationMainFragment.updateLastBusTime(lineDirection.startLast);
            }
        }
    }

    private void updateRouteSummary(BusApiClient.BusLineDirection lineDirection) {
        // 通过 Fragment 更新总里程
        if (navigationMainFragment != null && lineDirection.lineLength > 0) {
            navigationMainFragment.updateRouteSummary(String.valueOf(lineDirection.lineLength));
        }
    }

    private void updateTicketPrice() {
        BusApiClient.BusLineDirection lineDirection = getCurrentDirectionData();
        double price = 1.0;
        if (lineDirection != null && lineDirection.totalPrice > 0) {
            price = lineDirection.totalPrice;
        }
        priceText = String.format(Locale.getDefault(), "%.2f", price);
        if (navigationMainFragment != null) {
            navigationMainFragment.updatePriceText(priceText);
        }
    }

    private void setupStationList(BusApiClient.BusLineDirection lineDirection) {
        // 添加日志1: 检查传入数据
        Log.d(TAG, "=== setupStationList 开始 ===");
        Log.d(TAG, "lineDirection = " + (lineDirection == null ? "null" : "not null"));

        if (lineDirection == null) {
            Log.e(TAG, "setupStationList: lineDirection 为 null");
            return;
        }

        Log.d(TAG, "lineDirection.id = " + lineDirection.id);
        Log.d(TAG, "lineDirection.stationList = " + (lineDirection.stationList == null ? "null" : "size=" + lineDirection.stationList.size()));

        if (lineDirection.stationList == null || lineDirection.stationList.isEmpty()) {
            Log.w(TAG + "-BusInfo-", "无站点数据，stationList size=" +
                    (lineDirection.stationList == null ? "null" : lineDirection.stationList.size()));
            return;
        }

        runOnUiThread(() -> {
            try {
                Log.d(TAG, "runOnUiThread: 开始更新UI");

                busLineView = findViewById(R.id.bus_line_view);
                stationScrollView = findViewById(R.id.station_scroll_view);

                Log.d(TAG, "busLineView = " + busLineView);
                Log.d(TAG, "stationScrollView = " + stationScrollView);

                if (busLineView == null) {
                    Log.e(TAG, "busLineView 为 null，请检查布局文件 activity_bus_line_details.xml");
                }
                if (stationScrollView == null) {
                    Log.e(TAG, "stationScrollView 为 null，请检查布局文件 activity_bus_line_details.xml");
                }

                // 打印前3个站点信息
                for (int i = 0; i < Math.min(3, lineDirection.stationList.size()); i++) {
                    BusApiClient.BusLineStation station = lineDirection.stationList.get(i);
                    Log.d(TAG, String.format("站点[%d]: id=%s, name=%s, lat=%f, lon=%f, order=%d",
                            i, station.id, station.stationName, station.poiOriginLat, station.poiOriginLon, station.stationOrder));
                }

                busLineView.setStations(lineDirection.stationList);
                Log.d(TAG, "busLineView.setStations 完成");
                busLineView.post(() -> {
                    Log.d(TAG, "post: 强制重新测量布局");

                    // 方法1: 请求重新布局
                    stationScrollView.requestLayout();
                    busLineView.requestLayout();

                    // 方法2: 强制测量
                    int widthSpec = View.MeasureSpec.makeMeasureSpec(stationScrollView.getWidth(), View.MeasureSpec.EXACTLY);
                    int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
                    busLineView.measure(widthSpec, heightSpec);

                    Log.d(TAG, "post完成: busLineView测量高度=" + busLineView.getMeasuredHeight());

                    // 方法3: 如果高度还是0，尝试使用WRAP_CONTENT
                    if (busLineView.getMeasuredHeight() == 0) {
                        Log.w(TAG, "busLineView高度为0，尝试设置LayoutParams");
                        android.view.ViewGroup.LayoutParams params = busLineView.getLayoutParams();
                        if (params != null) {
                            params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                            busLineView.setLayoutParams(params);
                            busLineView.requestLayout();
                        }
                    }
                });
                busLineView.setOnStationClickListener(this::showStationDetails);
                busLineView.setOnGpsArrivalListener(this::scrollToStation);

                realTimeManager = new BusRealTimeManager(handler, lineDirection.stationList);
                realTimeManager.startTracking(lineDirection.id, BusLineDetailActivity.this);
                // 初始加载：启动 10 秒刷新倒计时（GPS 模式由 toggle 切到 GPS 时再处理）
                if (currentAnnounceMode == AnnounceMode.NETWORK) {
                    startNetworkRefreshCountdown();
                }

                // 初始化导航卡片的下一站
                if (navigationMainFragment != null && lineDirection.stationList != null && !lineDirection.stationList.isEmpty()) {
                    String firstStation = lineDirection.stationList.get(0).stationName;
                    if (firstStation != null) {
                        navigationMainFragment.updateNextStation(firstStation);
                        Log.d(TAG, "更新下一站: " + firstStation);
                    }
                }

                // 初始化导航卡片的方向（终点站）
                if (navigationMainFragment != null && lineDirection.endStation != null) {
                    navigationMainFragment.updateDirection(lineDirection.endStation);
                    Log.d(TAG, "更新方向(终点站): " + lineDirection.endStation);
                }

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
                        Log.d(TAG, "定位到指定站点: position=" + position + ", stationId=" + stationId);
                    }
                }
                if (navigationMainFragment != null) {
                    navigationMainFragment.setOnStationClickListener(this::showStationDetails);
                    navigationMainFragment.setOnGpsArrivalListener(this::scrollToStation);

                    // 如果有指定站点，选中它
                    stationId = getIntent().getStringExtra("station_id");
                    if (stationId != null && !stationId.isEmpty()) {
                        int position = findStationPositionById(stationId);
                        if (position != -1) {
                            navigationMainFragment.setSelectedPosition(position);
                        }
                    }
                }
                setupEtaList();
                Log.d(TAG, "=== setupStationList 完成 ===");

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
        rvLiveVehicles.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                outRect.right = 12;
            }
        });
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
                lp.width = (getResources().getDisplayMetrics().widthPixels);
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

    private void scrollToStation(int stationIndex) {
        if (stationScrollView != null && stationIndex >= 0) {
            stationScrollView.post(() -> {
                int startY = 60;
                int stationHeight = 150;
                int stationCenterY = startY + stationIndex * stationHeight + stationHeight / 2;
                int scrollViewHeight = stationScrollView.getHeight();
                int scrollY = stationCenterY - scrollViewHeight / 2;
                int childHeight = stationScrollView.getChildAt(0) != null ? stationScrollView.getChildAt(0).getHeight() : 0;
                int maxScrollY = childHeight - scrollViewHeight;
                scrollY = Math.max(0, Math.min(scrollY, maxScrollY));
                stationScrollView.smoothScrollTo(0, scrollY);
            });
        }
    }

    private void showStationDetails(BusApiClient.BusLineStation station, int position) {
        if (busLineView != null) {
            busLineView.setSelectedPosition(position);
        }
        trackedVehiclePlate = null;
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
        if (currentAnnounceMode == AnnounceMode.GPS) {
            return;
        }
        runOnUiThread(() -> {
            // ⭐ 防止 race：post 期间用户已切到 GPS 模式，跳过本次更新
            if (currentAnnounceMode != AnnounceMode.NETWORK) {
                return;
            }
            hideErrorIndicator();
            // 网络模式：拉数据成功 → 把倒计时重置回 10
            resetNetworkRefreshCountdown();
            updateNetworkStatusIndicator(true);

            if (!positions.isEmpty()) {
                if (navigationMainFragment != null) {
                    navigationMainFragment.updateBusPositions(positions);
                }
                if (busLineView != null) {
                    busLineView.updateBusPositions(positions);
                }
                int selectedStationIndex = busLineView != null ? busLineView.getSelectedPosition() : -1;
                if (selectedStationIndex != -1) {
                    // 设置目标站点位置（用于网络模式下计算最近车辆的速度）
                    List<BusApiClient.BusLineStation> stations = realTimeManager.getStationList();
                    if (stations != null && selectedStationIndex < stations.size()) {
                        BusApiClient.BusLineStation station = stations.get(selectedStationIndex);
                        if (station.poiOriginLat != 0 && station.poiOriginLon != 0) {
                            if (navigationMainFragment != null && navigationMainFragment.getNavigation() != null) {
                                navigationMainFragment.getNavigation().setTargetStation(station.poiOriginLat, station.poiOriginLon, selectedStationIndex);
                            }
                        }
                    }

                    if (trackedVehiclePlate == null) {
                        for (BusApiClient.BusPosition vehicle : positions) {
                            int vehicleStationIndex = vehicle.currentStationOrder - 1;
                            if (vehicleStationIndex == selectedStationIndex) {
                                trackedVehiclePlate = vehicle.plateNumber;
                                break;
                            }
                        }
                    }
                    if (trackedVehiclePlate != null) {
                        for (BusApiClient.BusPosition vehicle : positions) {
                            if (trackedVehiclePlate.equals(vehicle.plateNumber)) {
                                int vehicleStationIndex = vehicle.currentStationOrder - 1;
                                if (vehicleStationIndex != selectedStationIndex) {
                                    busLineView.setSelectedPosition(vehicleStationIndex);
                                    selectedStationIndex = vehicleStationIndex;
                                }
                                break;
                            }
                        }
                    }
                    updateEtaItems(positions, selectedStationIndex);
                    checkAndAnnounceArrival(positions, selectedStationIndex);
                }
            }
        });
    }

    // ============================================================
    //  网络模式刷新倒计时
    //  - 进入网络模式时启动，从 NETWORK_REFRESH_COUNTDOWN_SEC 倒数到 0
    //  - 收到成功数据时重置为初始值
    //  - 刷新失败时直接显示"刷新失败"，停止递减
    //  - 进入 GPS 模式时停掉
    // ============================================================

    /**
     * 启动 / 重置倒计时。
     * <p>
     * 会立即刷新一次显示（避免用户看到一帧 0 才跳到 10）。
     */
    private void startNetworkRefreshCountdown() {
        networkRefreshFailed = false;
        refreshCountdownSec = NETWORK_REFRESH_COUNTDOWN_SEC;
        renderRefreshCountdownText();
        scheduleNextCountdownTick();
    }

    /** 成功拉到数据 → 重置倒计时回 10 */
    private void resetNetworkRefreshCountdown() {
        networkRefreshFailed = false;
        refreshCountdownSec = NETWORK_REFRESH_COUNTDOWN_SEC;
        renderRefreshCountdownText();
        // 关键：旧 tick 在 0 时已经 return，必须重新排一轮
        scheduleNextCountdownTick();
    }

    /** 刷新失败 → 显示"失败"，但继续倒计时刷新 */
    private void markNetworkRefreshFailed() {
        networkRefreshFailed = true;
        // 不停止倒计时，继续每10秒刷新
        refreshCountdownSec = NETWORK_REFRESH_COUNTDOWN_SEC;
        renderRefreshCountdownText();
        scheduleNextCountdownTick();
    }

    /** 停掉定时器（切到 GPS 模式 / Activity 退出时用） */
    private void stopNetworkRefreshCountdown() {
        stopNetworkRefreshCountdownTick();
        refreshCountdownSec = 0;
        networkRefreshFailed = false;
    }

    private void stopNetworkRefreshCountdownTick() {
        if (refreshCountdownRunnable != null) {
            refreshCountdownHandler.removeCallbacks(refreshCountdownRunnable);
        }
    }

    private void scheduleNextCountdownTick() {
        stopNetworkRefreshCountdownTick();
        if (refreshCountdownRunnable == null) {
            refreshCountdownRunnable = new Runnable() {
                @Override
                public void run() {
                    refreshCountdownSec--;
                    if (refreshCountdownSec <= 0) {
                        // 倒数到 0 → 主动触发刷新，统一刷新节奏
                        if (networkModeText != null) {
                            networkModeText.setText("网络 --");
                        }
                        if (realTimeManager != null) {
                            realTimeManager.refreshNow();
                        }
                        // 本轮结束，等响应回来后 onBusPositionsUpdated / onError
                        // 会通过 resetNetworkRefreshCountdown / markNetworkRefreshFailed 处理
                        return;
                    }
                    renderRefreshCountdownText();
                    refreshCountdownHandler.postDelayed(this, GPS_TIME_UPDATE_INTERVAL);
                }
            };
        }
        refreshCountdownHandler.postDelayed(refreshCountdownRunnable, GPS_TIME_UPDATE_INTERVAL);
    }

    private void renderRefreshCountdownText() {
        if (networkModeText == null) return;
        if (networkRefreshFailed) {
            networkModeText.setText("失败 " + String.format(Locale.getDefault(), "%d", refreshCountdownSec));
        } else {
            networkModeText.setText("网络 " + String.format(Locale.getDefault(), "%d", refreshCountdownSec));
        }
    }

    private void clearEtaItems() {
        if (busEtaAdapter == null) {
            return;
        }
        etaItems.clear();
        busEtaAdapter.notifyDataSetChanged();
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
        // networkModeText 已在 onBusPositionsUpdated 顶部重置为 10 秒倒计时，这里不再覆盖
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

        if (nearestVehicle != null && lastVoiceStationOrder != nearestVehicle.currentStationOrder) {
            int nextStationIndex = nearestVehicle.currentStationOrder + 1;
            if (nextStationIndex > 0 && nextStationIndex <= realTimeManager.getStationList().size()) {
                BusApiClient.BusLineStation nextStation = realTimeManager.getStationList().get(nextStationIndex - 1);
                TTSUtils tts = TTSUtils.getInstance(this);
                tts.playLineDetailAnnouncement(lineName, startStation, endStation, nextStation.stationName);
                setNextStationInfoText(nextStation.stationName);
                lastVoiceStationOrder = nearestVehicle.currentStationOrder;
            }
        }

    }

    int lastVoiceStationOrder;
    boolean lastVehicleWasArrived = false;
    private String trackedVehiclePlate = null;

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
        updateNetworkStatusIndicator(false);
    }

    private void hideErrorIndicator() {
        if (errorIndicator != null) {
            errorIndicator.setVisibility(View.GONE);
        }
    }

    private void updateNetworkStatusIndicator(boolean isOnline) {
        if (networkStatusIndicator == null) {
            return;
        }
        stopGpsBlinkAnimation();
        if (currentAnnounceMode == AnnounceMode.GPS) {
            // GPS 模式：信号好=绿色，差=闪烁
            if (isOnline && isGpsSignalNormal) {
                networkStatusIndicator.setTextColor(0xFF00FF00);
            } else {
                startGpsBlinkAnimation();
            }
        } else {
            // 网络模式：在线=蓝色，离线=灰
            if (isOnline) {
                networkStatusIndicator.setTextColor(0xFF37D4F4);
            } else {
                networkStatusIndicator.setTextColor(0xFF555555);
            }
        }
    }

    private void startGpsBlinkAnimation() {
        if (gpsBlinkAnimator != null) {
            gpsBlinkAnimator.cancel();
        }
        gpsBlinkAnimator = ValueAnimator.ofFloat(0f, 1f);
        gpsBlinkAnimator.setDuration(500);
        gpsBlinkAnimator.setRepeatCount(ValueAnimator.INFINITE);
        gpsBlinkAnimator.setRepeatMode(ValueAnimator.RESTART);
        gpsBlinkAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            float alpha = progress < 0.5f ? 1f : 0f;
            if (networkStatusIndicator != null) {
                networkStatusIndicator.setAlpha(alpha);
            }
        });
        gpsBlinkAnimator.start();
    }

    private void stopGpsBlinkAnimation() {
        if (gpsBlinkAnimator != null) {
            gpsBlinkAnimator.cancel();
            gpsBlinkAnimator = null;
        }
        if (networkStatusIndicator != null) {
            networkStatusIndicator.setAlpha(1f);
        }
    }

    @Override
    public void onError(String message) {
        String userMessage = extractUserFriendlyMessage(message);
        String detailMessage = buildDetailMessage(message, getCurrentDirectionData());

        runOnUiThread(() -> {
            lastErrorMessage = userMessage;
            lastErrorDetail = detailMessage;
            showErrorIndicator();
            // 刷新失败 → networkModeText 显示"检查网络"（GPS 模式不动）
            if (currentAnnounceMode == AnnounceMode.NETWORK) {
                markNetworkRefreshFailed();
            }
        });
    }

    private String extractUserFriendlyMessage(String message) {
        if (message == null) return "未知错误";
        if (message.contains("SocketTimeoutException") || message.contains("timeout")) {
            return "网络超时，请检查网络连接";
        }
        if (message.contains("UnknownHostException")) {
            return "无法解析服务器地址";
        }
        if (message.contains("ConnectException")) {
            return "无法连接到服务器";
        }
        if (message.contains("UnrecognizedPropertyException") || message.contains("JSON")) {
            return "数据解析错误";
        }
        if (message.contains("API调用失败")) {
            return "API请求失败";
        }
        if (message.contains("HTTP错误")) {
            return "服务器响应错误";
        }
        return "获取实时数据失败";
    }

    private String buildDetailMessage(String message, BusApiClient.BusLineDirection lineDirection) {
        StringBuilder sb = new StringBuilder();
        sb.append("技术详情:\n");
        sb.append(message).append("\n\n");
        if (lineDirection != null) {
            sb.append("线路ID: ").append(lineDirection.id).append("\n");
        }
        if (cachedResponse != null) {
            sb.append("线路详情API状态: ").append(cachedResponse.code).append("\n");
            sb.append("线路详情返回信息: ").append(cachedResponse.returnInfo).append("\n");
        }
        return sb.toString();
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
                    HorizontalScrollTextView endStationName = findViewById(R.id.end_station_name);
                    endStationName.setText(endStation);
                    busLineView = findViewById(R.id.bus_line_view);
                    stationScrollView = findViewById(R.id.station_scroll_view);

                    busLineView.setStations(testStations);
                    busLineView.setOnStationClickListener(this::showStationDetails);

                    realTimeManager = new BusRealTimeManager(handler, testStations);
                    realTimeManager.startTracking("test_line_001", BusLineDetailActivity.this);

                    // 初始化导航卡片的下一站
                    if (navigationMainFragment != null && !testStations.isEmpty()) {
                        String firstStation = testStations.get(0).stationName;
                        if (firstStation != null) {
                            navigationMainFragment.updateNextStation(firstStation);
                        }
                    }

                    // 初始化导航卡片的方向（终点站）
                    if (navigationMainFragment != null && endStation != null) {
                        navigationMainFragment.updateDirection(endStation);
                    }

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
        isActivityResumed = true;
        // ⭐ Fragment.onResume() 内部会自动调用 tencentNavigation.onResume()，
        //   这里不再需要 Activity 手动转发。
        if (navigationMainFragment != null) {
            navigationMainFragment.notifyHostResumed(true);
        }
        if (currentAnnounceMode == AnnounceMode.NETWORK && realTimeManager != null) {
            realTimeManager.startTracking(getCurrentDirectionId(), this);
            // onResume → 重新进入网络模式，重启倒计时
            startNetworkRefreshCountdown();
        }
        if (currentAnnounceMode == AnnounceMode.GPS) {
            GpsWarmingUp.addListener(gpsActivityListener);
            GpsWarmingUp.addSatelliteListener(satelliteCountListener);
            // GPS 模式：显示缓存的卫星数
            if (networkModeText != null) {
                networkModeText.setText("GPS " + GpsWarmingUp.getSatelliteCount() + "/" + GpsWarmingUp.getTotalSatelliteCount());
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityResumed = false;
        if (navigationMainFragment != null) {
            navigationMainFragment.notifyHostResumed(false);
        }
        if (realTimeManager != null) {
            //realTimeManager.stopTracking();
        }
        if (currentAnnounceMode == AnnounceMode.GPS) {
            GpsWarmingUp.removeListener(gpsActivityListener);
            GpsWarmingUp.removeSatelliteListener(satelliteCountListener);
        }
        // ⭐ tencentNavigation.onPause() 由 Fragment.onPause() 自动调用
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle outState) {
        super.onSaveInstanceState(outState);
        // ⭐ Fragment.onSaveInstanceState() 已自动调用 tencentNavigation.onSaveInstanceState()
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        // ⭐ Fragment.onLowMemory() 已自动调用 tencentNavigation.onLowMemory()
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 停掉网络模式刷新倒计时，防止 Handler 引用泄漏
        stopNetworkRefreshCountdown();
        if (errorBlinkAnimator != null) {
            errorBlinkAnimator.cancel();
            errorBlinkAnimator = null;
        }
        if (gpsBlinkAnimator != null) {
            gpsBlinkAnimator.cancel();
            gpsBlinkAnimator = null;
        }
        if(realTimeManager != null){
            realTimeManager.stopTracking();
        }
        GpsWarmingUp.removeListener(gpsActivityListener);
        GpsWarmingUp.removeSatelliteListener(satelliteCountListener);
        GpsWarmingUp.stopWarmingUp();
        realTimeManager = null;
        if (tipsHandler != null) {
            tipsHandler.removeCallbacksAndMessages(null);
        }
        stopSpeedTimeout();
        if (speedTimeoutHandler != null) {
            speedTimeoutHandler.removeCallbacksAndMessages(null);
        }
        // 取消所有网络请求
        if (busApiClient != null) {
            busApiClient.cancelAllRequests();
        }
        // ⭐ Fragment.onDestroyView() 会自动调用 tencentNavigation.onDestroy() 并清空实例引用
        navigationMainFragment = null;
    }

    private String getCurrentDirectionId() {
        BusApiClient.BusLineDirection direction = getCurrentDirectionData();
        return direction != null ? direction.id : "";
    }
}