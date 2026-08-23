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
import androidx.constraintlayout.widget.ConstraintLayout;
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
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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


import io.sgr.geometry.utils.GeometryUtils;

public class BusLineDetailActivity extends AppCompatActivity implements BusRealTimeManager.RealTimeUpdateListener {
    private String lineID;
    private String lineName;
    private String startStation;
    private String endStation;
    private HorizontalScrollTextView endStationNameView;  // ⭐ 保存终点站View引用，供POV面板读取
    private HorizontalScrollTextView endStationEnNameView; // ⭐ 终点站英文名视图
    private BusApiClient busApiClient;
    private HorizontalScrollTextView routeNumber;
    private HorizontalScrollTextView routeEnNumber;
    private TextView navHasNotification;
    private TextView noticeText;
    private DotMatrixView accessibilityIcon;
    private HorizontalScrollTextView nextStationInfo;
    private HorizontalScrollTextView tips;
    private HorizontalScrollTextView enTips;
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
    boolean isNavHidden;
    /** 隐藏前导航区的真实运行高度（运行时动态赋值，隐藏时快照，显示时恢复） */
    int navRootSavedHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
    // ===== 语音包状态条 =====
    private LinearLayout llVoicepackStatus;
    private TextView tvVoicepackStatus;
    private Button btnVoicepackDownload;
    private Button btnVoicepackDetail;
    private ProgressBar pbVoicepackLine;
    private volatile boolean voicepackDownloadingLine = false;
    private List<String> voicepackStationNames = new ArrayList<>();
    /** 配置状态监听器：统一由 VoicePackManager 推送 LOADING/READY/FAILED，避免本页面自行轮询/重试 */
    private VoicePackManager.ConfigStateListener voicepackConfigListener = null;
    private int lastMissingCount = 0;
    private VoicePackManager.MissingStat lastMissingStat = null;
    /** 本页面已展示过的清理事件时间戳，避免同一事件重复提示 */
    private long lastDisplayedCleanupTime = 0L;

    // ===== POV模式静态引用 =====
    /** ⭐ 当前 Activity 实例的静态引用（用于POV模式暂停/恢复业务） */
    private static BusLineDetailActivity currentInstance = null;

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
    LinearLayout modeSwitch;
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

    /**
     * ⭐ 获取当前公交线路路线点（供CameraActivity的POV地图使用）
     */
    public static List<io.sgr.geometry.Coordinate> getCurrentRoutePoints() {
        BusLineDetailActivity instance = currentInstance;
        if (instance != null) {
            return instance.routePoints;
        }
        return null;
    }

    /**
     * ⭐ 获取当前公交线路起点站和终点站坐标（供CameraActivity的POV导航使用）
     * @return [startLat, startLng, endLat, endLng]，如果无法获取则返回null
     */
    public static double[] getCurrentStartEndStationCoords() {
        BusLineDetailActivity instance = currentInstance;
        if (instance != null && instance.realTimeManager != null) {
            List<BusApiClient.BusLineStation> stations = instance.realTimeManager.getStationList();
            if (stations != null && stations.size() >= 2) {
                BusApiClient.BusLineStation first = stations.get(0);
                BusApiClient.BusLineStation last = stations.get(stations.size() - 1);
                if (first.poiOriginLat != 0 && first.poiOriginLon != 0
                        && last.poiOriginLat != 0 && last.poiOriginLon != 0) {
                    return new double[]{
                            first.poiOriginLat, first.poiOriginLon,
                            last.poiOriginLat, last.poiOriginLon
                    };
                }
            }
        }
        return null;
    }

    /**
     * ⭐ 获取当前公交线路名称（供CameraActivity的POV信息面板使用）
     */
    public static String getCurrentLineName() {
        BusLineDetailActivity instance = currentInstance;
        return instance != null ? instance.lineName : null;
    }

    /**
     * ⭐ 获取当前公交终点站名称（供CameraActivity的POV信息面板使用）
     */
    public static String getCurrentEndStation() {
        BusLineDetailActivity instance = currentInstance;
        if (instance == null) return null;
        // 优先从View读取当前显示的终点站名（换向后endStation字段可能还没更新）
        if (instance.endStationNameView != null) {
            String viewText = instance.endStationNameView.getText();
            if (viewText != null && !viewText.isEmpty()) return viewText;
        }
        return instance.endStation;
    }

    /**
     * ⭐ 获取起点站名称（供POV面板使用）
     */
    public static String getCurrentStartStation() {
        BusLineDetailActivity instance = currentInstance;
        return instance != null ? instance.startStation : null;
    }

    /**
     * ⭐ 获取当前GPS坐标（供POV面板使用）
     */
    public static double[] getCurrentGpsCoords() {
        BusLineDetailActivity instance = currentInstance;
        if (instance != null) {
            return new double[]{instance.currentGpsLat, instance.currentGpsLon};
        }
        return null;
    }

    /**
     * ⭐ 获取站点列表（供CameraActivity回退计算下一站使用）
     */
    public static List<BusApiClient.BusLineStation> getCurrentStationList() {
        BusLineDetailActivity instance = currentInstance;
        if (instance != null && instance.realTimeManager != null) {
            return instance.realTimeManager.getStationList();
        }
        return null;
    }

    /**
     * ⭐ 获取当前方向的线路数据（供CameraActivity的POV页面直接复用，避免重新请求接口）
     * 返回 currentDirection 对应的 BusLineDirection（含 stationList、geometry、起终点等），
     * 数据来源与 showDirection() 完全一致，不存在换向后时序不一致问题。
     */
    public static BusApiClient.BusLineDirection getCurrentLineDirection() {
        BusLineDetailActivity instance = currentInstance;
        if (instance == null) return null;
        return instance.getCurrentDirectionData();
    }

    /**
     * ⭐ 获取进站半径（供CameraActivity判断进出站使用）
     */
    public static double getEnterStationRadius() {
        BusLineDetailActivity instance = currentInstance;
        return instance != null ? instance.enterStationRadius : DEFAULT_ENTER_STATION_RADIUS;
    }

    /**
     * ⭐ 获取出站半径（供CameraActivity判断进出站使用）
     */
    public static double getExitStationRadius() {
        BusLineDetailActivity instance = currentInstance;
        return instance != null ? instance.exitStationRadius : DEFAULT_EXIT_STATION_RADIUS;
    }

    /**
     * ⭐ 获取POV下一站/当前站信息（供CameraActivity面板使用）
     * @return [isAtStation, stationName]
     *   isAtStation=true: 在站内，stationName=当前站名
     *   isAtStation=false: 出站，stationName=下一站名
     */
    public static Object[] getPovNextStationInfo() {
        BusLineDetailActivity instance = currentInstance;
        if (instance == null || instance.realTimeManager == null) return null;
        List<BusApiClient.BusLineStation> stations = instance.realTimeManager.getStationList();
        if (stations == null || stations.isEmpty()) return null;

        boolean atStation = instance.isInsideStationRadius;
        int currentIndex = instance.gpsCurrentStationIndex;

        if (atStation && currentIndex >= 0 && currentIndex < stations.size()) {
            // 在站内：显示当前站
            return new Object[]{true, stations.get(currentIndex).stationName};
        } else {
            // 出站：显示下一站
            int nextIndex = currentIndex + 1;
            if (nextIndex < stations.size()) {
                return new Object[]{false, stations.get(nextIndex).stationName};
            } else if (currentIndex >= 0 && currentIndex < stations.size()) {
                // 已到终点站
                return new Object[]{true, stations.get(currentIndex).stationName};
            }
            return null;
        }
    }

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
    private static final String[] TIPS_EN_TEXT_BASE = {"Queue in order, board in turn", "Dangerous items strictly prohibited"};
    private static final int[] TIPS_COLOR_BASE = {0xFFFFFF00, 0xFF00FFFF};
    private static final int TIPS_COLOR_PURPLE = 0xFFAA00FF;
    // GPS 模式报站文案：中英双语"扫码评价"提示
    private static final String QR_HINT_CN = "        欢迎扫车内二维码对本次乘车服务进行评价。";
    private static final String QR_HINT_EN = "    Scan the QR code on board to rate your ride. Thank you!";
    private String[] currentTipsText = TIPS_TEXT_BASE;
    private String[] currentTipsEnText = TIPS_EN_TEXT_BASE;
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
            if (enTips != null) {
                enTips.setText(currentTipsEnText[tipsAnimationIndex]);
                enTips.setTextColor(currentTipsColor[tipsAnimationIndex]);
            }
            tipsHandler.postDelayed(this, TIPS_INTERVAL);
        }
    };

    private void startTipsAnimation() {
        tipsHandler.removeCallbacksAndMessages(null);
        tipsAnimationIndex = 0;
        tips.setText(currentTipsText[0]);
        tips.setTextColor(currentTipsColor[0]);
        if (enTips != null) {
            enTips.setText(currentTipsEnText[0]);
            enTips.setTextColor(currentTipsColor[0]);
        }
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
                networkModeText.setText("GPS " + String.format(Locale.getDefault(), "%02d", used)
                        + "/" + String.format(Locale.getDefault(), "%02d", total));
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
                networkModeText.setText("GPS " + String.format(Locale.getDefault(), "%02d", usedCount)
                        + "/" + String.format(Locale.getDefault(), "%02d", totalCount));
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
            nextStationInfo.setText(stationName + " 到了！  We are now at " + VoicePackManager.getInstance(this).getStationEnglish(stationName) + " !");
        } else {
            tts.playGpsMiddleStationAnnouncement(stationName);
            nextStationInfo.setText(stationName + " 到了！  We are now at " + VoicePackManager.getInstance(this).getStationEnglish(stationName) + " !");
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
            nextStationInfo.setText("下一站：" + stationName + QR_HINT_CN + "    Next Station:" + VoicePackManager.getInstance(this).getStationEnglish(stationName) + QR_HINT_EN);
        } else {
            nextStationInfo.setText("下一站：" + stationName + "    Next Station:" + VoicePackManager.getInstance(this).getStationEnglish(stationName));
        }
        if (navigationMainFragment != null && stationName != null) {
            navigationMainFragment.updateNextStation(stationName);
        }
    }

    private void updatePriceTips(BusApiClient.BusLineDirection lineDirection) {
        String[] priceTips = buildPriceTips(lineDirection);
        String[] priceTipsEn = buildPriceTipsEn(lineDirection);
        if (priceTips != null) {
            int[] priceColors = new int[priceTips.length];
            for (int i = 0; i < priceTips.length; i++) {
                priceColors[i] = TIPS_COLOR_PURPLE;
            }
            int baseCount = TIPS_TEXT_BASE.length;
            currentTipsText = new String[priceTips.length + baseCount];
            currentTipsColor = new int[currentTipsText.length];
            currentTipsEnText = new String[priceTips.length + baseCount];
            System.arraycopy(priceTips, 0, currentTipsText, 0, priceTips.length);
            System.arraycopy(priceColors, 0, currentTipsColor, 0, priceColors.length);
            System.arraycopy(TIPS_TEXT_BASE, 0, currentTipsText, priceTips.length, baseCount);
            System.arraycopy(TIPS_COLOR_BASE, 0, currentTipsColor, priceTips.length, baseCount);
            System.arraycopy(priceTipsEn, 0, currentTipsEnText, 0, priceTipsEn.length);
            System.arraycopy(TIPS_EN_TEXT_BASE, 0, currentTipsEnText, priceTipsEn.length, baseCount);
        } else {
            currentTipsText = TIPS_TEXT_BASE;
            currentTipsColor = TIPS_COLOR_BASE;
            currentTipsEnText = TIPS_EN_TEXT_BASE;
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

    /** 与 buildPriceTips 平行：返回价格提示的英文翻译，索引与中文一一对应 */
    private String[] buildPriceTipsEn(BusApiClient.BusLineDirection lineDirection) {
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
            return new String[]{"No conductor. Fare: 1 yuan"};
        } else if (isIntercityBus && price == 2.0) {
            return new String[]{"No conductor. Fare: 2 yuan"};
        } else if (isIntercityBus && price > 0) {
            return new String[]{"Multi-fare system Fare: 2~" + formatPrice(price) + " yuan", "Tap or scan on both entry and exit"};
        } else if (price == 1.0) {
            return new String[]{"No conductor. Fare: 1 yuan"};
        }

        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ⭐ 保存当前实例的静态引用（用于POV模式）
        currentInstance = this;
        setContentView(R.layout.activity_bus_line_details);
        applySquareNavigationLayout(0.8f);
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

    /**
     * 车机布局:把 activity_navigation 的 <include> 高度强制为宽度(任意分辨率下保持方形布局)。
     * <p>父布局(ScrollView 等)给的是 wrap_content/0dp 时,高度=宽度保证图片中三列布局比例不被破坏。
     */
    /**
     * 车机布局:把 activity_navigation 的 <include> 高度设置为宽度的指定比例
     * @param ratio 比例值，如 0.5f 表示高度=宽度×0.5
     */
    private void applySquareNavigationLayout(float ratio) {
        final View navigationSection = findViewById(R.id.navigation_section);
        if (navigationSection == null) return;

        navigationSection.post(new Runnable() {
            @Override
            public void run() {
                int width = navigationSection.getWidth();
                if (width <= 0) {
                    navigationSection.postDelayed(this, 50L);
                    return;
                }

                // 高度 = 宽度 × 比例
                int height = (int) (width * ratio);

                ViewGroup.LayoutParams params = navigationSection.getLayoutParams();
                if (params.height != height) {
                    params.height = height;
                    navigationSection.setLayoutParams(params);
                    Log.d(TAG, "车机布局:宽度=" + width + "px, 高度=宽度×" + ratio + "=" + height + "px");
                }
            }
        });
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
        int maxWidth = (int) (150 * getResources().getDisplayMetrics().density);
        int textWidth = (int) routeNumber.getTextWidth();
        if (textWidth > 0 && textWidth < maxWidth) {
            routeNumber.getLayoutParams().width = textWidth + 10;
        }
        routeEnNumber = findViewById(R.id.route_en_number);
        routeEnNumber.setText(TTSUtils.getEnLineName(lineName));
        routeEnNumber.setTextColor(0xFF00FF00);
        routeEnNumber.setGravity(0);
        routeEnNumber.setScrollSpeed(150f);
        routeEnNumber.setTypeface(dottedSongti);
        textWidth = (int) routeEnNumber.getTextWidth();
        if (textWidth > 0 && textWidth < maxWidth) {
            routeEnNumber.getLayoutParams().width = textWidth + 20;
        }
        LinearLayout noticeBar = findViewById(R.id.notice_bar);
        navHasNotification = findViewById(R.id.nav_has_notification);
        navHasNotification.setText("0");
        navHasNotification.setVisibility(View.GONE);
        noticeText = findViewById(R.id.notice_text);
        noticeBar.setVisibility(View.GONE);
        endStationNameView = findViewById(R.id.end_station_name);
        endStationNameView.setGravity(2);
        endStationNameView.setText(endStation);
        endStationNameView.setTypeface(dottedSongti);
        endStationNameView.setScrollSpeed(180f);
        // 终点站英文名：通过语音包获取，站名为空时返回 null，兜底显示空串
        endStationEnNameView = findViewById(R.id.end_station_en_name);
        if (endStationEnNameView != null) {
            endStationEnNameView.setGravity(2);
            endStationEnNameView.setTypeface(dottedSongti);
            String enName = VoicePackManager.getInstance(this).getStationEnglish(endStation);
            endStationEnNameView.setText(enName != null ? enName : "");
        }
        tips = findViewById(R.id.tips);
        tips.setTypeface(dottedSongti);
        tips.setGravity(1);
        enTips = findViewById(R.id.en_tips);
        enTips.setTypeface(dottedSongti);
        enTips.setGravity(1);
        startTipsAnimation();
        nextStationInfo = findViewById(R.id.next_station_info);
        nextStationInfo.setTypeface(dottedSongti);
        nextStationInfo.setTextColor(0xFFFF0000);
        nextStationInfo.setTextSize(30f);
        nextStationInfo.setText("欢迎乘坐 " + lineName + " 公交车" + "    " + "Welcome aboard the " + TTSUtils.getEnLineName(lineName));
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
        // mode_text 仍使用点阵字体（数字不等宽），通过固定宽度避免内容变化时整体跳动
        networkModeText.setTypeface(dottedSongti);
        // ⭐ gpsSpeedText 已迁移到 NavigationMainFragment，字体设置在 fragment.onViewCreated() 中完成

        // 语音包状态条
        llVoicepackStatus = findViewById(R.id.ll_voicepack_status);
        tvVoicepackStatus = findViewById(R.id.tv_voicepack_status);
        btnVoicepackDownload = findViewById(R.id.btn_voicepack_download);
        btnVoicepackDetail = findViewById(R.id.btn_voicepack_detail);
        pbVoicepackLine = findViewById(R.id.pb_voicepack_line);
        if (btnVoicepackDownload != null) {
            btnVoicepackDownload.setOnClickListener(v -> {
                if (voicepackDownloadingLine) return;
                confirmAndDownloadLineVoicepack();
            });
        }
        if (btnVoicepackDetail != null) {
            btnVoicepackDetail.setOnClickListener(v -> showVoicepackDetailDialog());
        }

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

        // 6. 绑定左侧"拍POV"图标点击
        View navIconPov = findViewById(R.id.nav_icon_pov);
        if (navIconPov != null) {
            navIconPov.setOnClickListener(v -> {
                Intent intent = new Intent(this, CameraActivity.class);
                intent.putExtra("line_id", lineID);
                intent.putExtra("line_name", lineName);
                intent.putExtra("start_station", startStation);
                intent.putExtra("end_station", endStation);
                intent.putExtra("direction", currentDirection);
                startActivity(intent);
            });
        }

        // 7. 绑定左侧"更多应用"图标点击
        View navIconMoreView = findViewById(R.id.nav_icon_more);
        if (navIconMoreView != null) {
            navIconMoreImg = navIconMoreView.findViewById(R.id.nav_icon_more_img);
            navIconMoreView.setOnClickListener(v -> toggleMorePage());
        }
        View hideSwitch = findViewById(R.id.hide_switch);
        TextView hideSwitchText = findViewById(R.id.hide_switch_text);
        View navIconContainer = findViewById(R.id.nav_icon_container);
        View navContentContainer = findViewById(R.id.nav_content_container);
        View navIconBar = findViewById(R.id.nav_icon_bar);
        // 注意：activity_navigation 是通过 <include android:id="@+id/navigation_section"> 嵌入的，
        // include 会覆盖根节点 id，因此根布局实际 id 是 navigation_section 而非 navigation_root
        View navRoot = findViewById(R.id.navigation_section);
        // 隐藏/显示：收起左侧菜单栏与右侧内容，root 收缩为只包住“显示”按钮（钉在顶部）
        hideSwitch.setOnClickListener(v -> {
            ConstraintLayout.LayoutParams barLp =
                    (ConstraintLayout.LayoutParams) navIconBar.getLayoutParams();
            ViewGroup.LayoutParams rootLp = navRoot.getLayoutParams();
            if (!isNavHidden) {
                // 收起：先快照当前（运行时动态赋值的）真实高度，再隐藏菜单与地图内容
                navRootSavedHeight = navRoot.getLayoutParams().height;
                navIconContainer.setVisibility(View.GONE);
                navContentContainer.setVisibility(View.GONE);
                // 侧栏高度收缩为只包住“显示”按钮，并钉在顶部（去掉底部约束 + wrap_content）
                barLp.height = ConstraintLayout.LayoutParams.WRAP_CONTENT;
                barLp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
                navIconBar.setLayoutParams(barLp);
                // root 同时收缩，使整体高度变小（不再铺满全屏）
                rootLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                navRoot.setLayoutParams(rootLp);
                if (hideSwitchText != null) hideSwitchText.setText("显示");
                isNavHidden = true;
            } else {
                // 恢复：菜单图标 + 地图内容重新显示
                navIconContainer.setVisibility(View.VISIBLE);
                navContentContainer.setVisibility(View.VISIBLE);
                barLp.height = 0;
                barLp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                navIconBar.setLayoutParams(barLp);
                // root 恢复为隐藏前快照的真实运行高度（不要用 MATCH_PARENT，以免被 LinearLayout 压成 0）
                rootLp.height = navRootSavedHeight;
                navRoot.setLayoutParams(rootLp);
                if (hideSwitchText != null) hideSwitchText.setText("隐藏");
                isNavHidden = false;
            }
        });
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
        // ⭐ 换向后同步更新 lineID，否则启动 CameraActivity 时 putExtra("line_id", lineID)
        //    传的还是旧方向 id，POV 页面 selectCurrentDirection() 会用 lineId 匹配回旧方向
        lineID = getCurrentDirectionId();
        trackedVehiclePlate = null;
        // ⭐ 换向后重置 fragment 显示状态
        lastFragmentDisplayStationOrder = -2;
        lastFragmentDisplayIsArrived = false;
        // ⭐ 换向后停止地图跟随（下一次 refresh 会基于新方向重新设）
        if (navigationMainFragment != null && navigationMainFragment.getNavigation() != null) {
            navigationMainFragment.getNavigation().clearFollowedVehicle();
        }
        Log.e(TAG + "-BusInfo-", "切换方向到: " + (currentDirection == 1 ? "上行" : "下行"));
        updateStartEndStations();
        showDirection();

        nextStationInfo.setText("欢迎乘坐 " + lineName + " 公交车" + "    " + "Welcome aboard the No." + formatLineNameForEnglish(lineName) +  " bus.");

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
                if (endStationNameView != null) {
                    endStationNameView.setText(lineDirection.endStation);
                }
                if (endStationEnNameView != null) {
                    String enName = VoicePackManager.getInstance(this)
                            .getStationEnglish(lineDirection.endStation);
                    endStationEnNameView.setText(enName != null ? enName : "");
                }
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
                checkLineVoicepackStatus();

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

    // ==================== 语音包缺失检查与下载 ====================

    /**
     * 检查当前线路缺失语音包数量并更新状态条。
     * 在站点列表加载完成后调用。classifyMissing 涉及磁盘 md5 校验，放后台线程。
     * 已就绪时整个状态条隐藏，不显示"已就绪"。
     */
    private void checkLineVoicepackStatus() {
        if (llVoicepackStatus == null || tvVoicepackStatus == null) return;
        if (realTimeManager == null || realTimeManager.getStationList() == null) return;

        List<String> names = new ArrayList<>();
        for (BusApiClient.BusLineStation s : realTimeManager.getStationList()) {
            if (s != null && s.stationName != null && !s.stationName.isEmpty()) {
                names.add(s.stationName);
            }
        }
        voicepackStationNames = names;

        // 显示状态条：检查中
        llVoicepackStatus.setVisibility(View.VISIBLE);
        if (btnVoicepackDownload != null) btnVoicepackDownload.setVisibility(View.GONE);
        if (btnVoicepackDetail != null) btnVoicepackDetail.setVisibility(View.GONE);
        if (pbVoicepackLine != null) pbVoicepackLine.setVisibility(View.GONE);
        tvVoicepackStatus.setText("🔊 检查语音包中...");
        tvVoicepackStatus.setOnClickListener(null);

        // 注册统一状态监听（幂等）+ 主动触发拉取。状态机的重试/失败判定在事件回调中驱动刷新，
        // 覆盖"源遍历中不判失败、成功后即时统计、重试耗尽才判失败"。
        VoicePackManager vpm = VoicePackManager.getInstance(this);
        ensureVoicepackConfigListener(vpm);
        vpm.ensureConfigLoading();
    }

    /**
     * 注册配置状态监听（幂等）。统一入口：所有状态（LOADING/READY/FAILED）都在此刷新，
     * 由 VoicePackManager 状态机驱动，本页面不再自行轮询/重试。
     */
    private void ensureVoicepackConfigListener(VoicePackManager vpm) {
        if (voicepackConfigListener != null) return;
        voicepackConfigListener = state -> {
            // 回调在后台线程（executor/scheduler/注册线程），切回主线程
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                switch (state) {
                    case LOADING:
                        // 源遍历中/重试等待中：绝不显示失败，一直保持"加载中"
                        if (tvVoicepackStatus != null) {
                            tvVoicepackStatus.setText("🔊 语音包配置加载中...");
                        }
                        break;
                    case READY:
                        // 配置就绪：反注册一次性监听，走正常统计
                        unregisterVoicepackConfigListener(vpm);
                        renderLineVoicepackReady(vpm);
                        break;
                    case FAILED:
                        // 所有源遍历完且重试已耗尽：显示失败
                        if (tvVoicepackStatus != null) {
                            tvVoicepackStatus.setText("⚠️ 语音包配置加载失败");
                        }
                        break;
                }
            });
        };
        vpm.addConfigStateListener(voicepackConfigListener);
    }

    /** 反注册配置状态监听 */
    private void unregisterVoicepackConfigListener(VoicePackManager vpm) {
        if (voicepackConfigListener != null) {
            vpm.removeConfigStateListener(voicepackConfigListener);
            voicepackConfigListener = null;
        }
    }

    /** 配置就绪后渲染线路语音包统计（后台线程做磁盘 md5 校验） */
    private void renderLineVoicepackReady(VoicePackManager vpm) {
        final List<String> names = voicepackStationNames;
        if (names == null || names.isEmpty()) {
            if (tvVoicepackStatus != null) {
                tvVoicepackStatus.setText("🔊 线路暂无站点");
            }
            return;
        }
        new Thread(() -> {
            VoicePackManager.MissingStat stat = vpm.classifyMissing(names);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (stat == null) {
                    if (tvVoicepackStatus != null) {
                        tvVoicepackStatus.setText("⚠️ 语音包配置未就绪");
                    }
                    return;
                }
                lastMissingStat = stat;
                int missing = stat.notInRemote.size() + stat.notDownloaded.size() + stat.needUpdate.size();
                lastMissingCount = missing;
                // 检查自动清理结果（仅在发生过清理且本页面未展示过时提示）
                long[] cleanupResult = vpm.getLastCleanupResult();
                int cleanupCount = (int) cleanupResult[0];
                long cleanupTime = cleanupResult[1];
                boolean hasNewCleanup = cleanupCount > 0 && cleanupTime > lastDisplayedCleanupTime;
                if (hasNewCleanup) {
                    lastDisplayedCleanupTime = cleanupTime;
                }
                if (missing == 0 && !hasNewCleanup) {
                    // 已就绪且无清理通知：整个状态条隐藏
                    llVoicepackStatus.setVisibility(View.GONE);
                } else {
                    StringBuilder sb = new StringBuilder();
                    if (missing > 0) {
                        sb.append("⚠️ 缺少 ").append(missing).append(" 个语音包\n");
                        sb.append("  · 未下载：").append(stat.notDownloaded.size()).append("\n");
                        sb.append("  · 有更新：").append(stat.needUpdate.size()).append("\n");
                        sb.append("  · 远程不存在：").append(stat.notInRemote.size());
                    }
                    if (hasNewCleanup) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append("🧹 已自动清理 ").append(cleanupCount).append(" 个过时文件");
                    }
                    tvVoicepackStatus.setText(sb.toString());
                    tvVoicepackStatus.setOnClickListener(null);
                    // 仅在缺少语音包时显示下载/详情按钮
                    int btnVisibility = missing > 0 ? View.VISIBLE : View.GONE;
                    if (btnVoicepackDetail != null) btnVoicepackDetail.setVisibility(btnVisibility);
                    if (btnVoicepackDownload != null) btnVoicepackDownload.setVisibility(btnVisibility);
                }
            });
        }).start();
    }

    /**
     * 弹出语音包详情对话框，列出三分类下的具体站名。
     * 用 AlertDialog.setMessage 拼接文本，自带滚动，不创建新 layout 文件。
     */
    private void showVoicepackDetailDialog() {
        if (lastMissingStat == null) {
            Toast.makeText(this, "暂无详情", Toast.LENGTH_SHORT).show();
            return;
        }
        VoicePackManager.MissingStat stat = lastMissingStat;
        int total = stat.notInRemote.size() + stat.notDownloaded.size() + stat.needUpdate.size();

        StringBuilder sb = new StringBuilder();
        sb.append("本线路共缺少 ").append(total).append(" 个语音包。\n\n");

        sb.append("📥 未下载 (").append(stat.notDownloaded.size()).append(")\n");
        sb.append(stat.notDownloaded.isEmpty() ? "无" : joinStationNames(stat.notDownloaded));

        sb.append("\n\n🔄 有更新 (").append(stat.needUpdate.size()).append(")\n");
        sb.append(stat.needUpdate.isEmpty() ? "无" : joinStationNames(stat.needUpdate));

        sb.append("\n\n❓ 远程不存在 (").append(stat.notInRemote.size()).append(")\n");
        sb.append(stat.notInRemote.isEmpty() ? "无" : joinStationNames(stat.notInRemote));

        // 追加自动清理信息（若发生过清理则展示）
        VoicePackManager vpm = VoicePackManager.getInstance(this);
        long[] cleanupResult = vpm.getLastCleanupResult();
        int cleanupCount = (int) cleanupResult[0];
        if (cleanupCount > 0) {
            sb.append("\n\n🧹 已清理过时文件 (").append(cleanupCount).append(")\n");
            sb.append("配置更新后自动删除的本地冗余语音包");
        }

        sb.append("\n\n说明：\n")
                .append("· 未下载：远程有但本地没有，可点击下载补齐\n")
                .append("· 有更新：本地已下载但 md5 不匹配，可点击下载更新\n")
                .append("· 远程不存在：配置文件中无此站，需更新配置");

        new AlertDialog.Builder(this)
                .setTitle("🔊 语音包详情")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 用顿号拼接站名列表 */
    private String joinStationNames(List<String> names) {
        if (names == null || names.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    private void confirmAndDownloadLineVoicepack() {
        if (voicepackStationNames.isEmpty()) {
            Toast.makeText(this, "未获取到站点信息", Toast.LENGTH_SHORT).show();
            return;
        }
        int missing = lastMissingCount;
        String msg = missing > 0
                ? "是否在线下载缺少的 " + missing + " 个语音包？"
                : "是否下载本线路语音包？";
        new AlertDialog.Builder(this)
                .setTitle("下载语音包")
                .setMessage(msg)
                .setPositiveButton("下载", (d, w) -> startDownloadLineVoicepack())
                .setNegativeButton("取消", null)
                .show();
    }

    private void startDownloadLineVoicepack() {
        if (tvVoicepackStatus == null || btnVoicepackDownload == null || pbVoicepackLine == null) return;
        voicepackDownloadingLine = true;
        btnVoicepackDownload.setVisibility(View.GONE);
        pbVoicepackLine.setVisibility(View.VISIBLE);
        pbVoicepackLine.setProgress(0);
        tvVoicepackStatus.setText("下载中... 0%");

        VoicePackManager.getInstance(this).downloadBatchAsync(voicepackStationNames, new VoicePackManager.ProgressCallback() {
            @Override
            public void onProgress(int done, int total) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    int p = total > 0 ? done * 100 / total : 0;
                    pbVoicepackLine.setProgress(p);
                    tvVoicepackStatus.setText("下载中... " + p + "%");
                });
            }

            @Override
            public void onComplete(boolean success) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    voicepackDownloadingLine = false;
                    pbVoicepackLine.setVisibility(View.GONE);
                    Toast.makeText(BusLineDetailActivity.this,
                            success ? "下载完成" : "下载结束（部分可能失败）",
                            Toast.LENGTH_SHORT).show();
                    // 重新检查状态
                    checkLineVoicepackStatus();
                });
            }
        });
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
        // ⭐ 重置"已到达/下一站"显示状态记忆，让新选的站能立刻显示对应的车
        lastFragmentDisplayStationOrder = -2;
        lastFragmentDisplayIsArrived = false;
        // ⭐ 换选了站：先清掉旧的跟随，下一次 refresh 会基于新 nearestVehicle 重新设置
        if (navigationMainFragment != null && navigationMainFragment.getNavigation() != null) {
            navigationMainFragment.getNavigation().clearFollowedVehicle();
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
            networkModeText.setText("失败 " + String.format(Locale.getDefault(), "%02d", refreshCountdownSec));
        } else {
            networkModeText.setText("网络 " + String.format(Locale.getDefault(), "%02d", refreshCountdownSec));
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

        // ⭐ 地图跟随：让地图跟 nearestVehicle 的 SmoothMoveMarker 走（不向目标坐标移动）
        if (navigationMainFragment != null && navigationMainFragment.getNavigation() != null) {
            if (nearestVehicle != null && nearestVehicle.plateNumber != null && !nearestVehicle.plateNumber.isEmpty()) {
                navigationMainFragment.getNavigation().setFollowedVehicle(nearestVehicle.plateNumber);
            } else {
                // 所有车都已越过选站 → 停止跟随
                navigationMainFragment.getNavigation().clearFollowedVehicle();
            }
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

        // ⭐ fragment 端"下一站/已到达"显示（与 next_station_info 同源，但带 isArrived 分支）
        // 独立状态记忆，确保 isArrived 切换（如车刚到站）也能立即反映
        if (nearestVehicle != null && (lastFragmentDisplayStationOrder != nearestVehicle.currentStationOrder
                || lastFragmentDisplayIsArrived != nearestVehicle.isArrived)) {
            List<BusApiClient.BusLineStation> stations = realTimeManager.getStationList();
            String displayName = null;
            boolean isArrived = nearestVehicle.isArrived;
            boolean isTerminal = false;
            if (isArrived) {
                // 已到站：显示"已到达 [当前站]"
                int arrivedIdx = nearestVehicle.currentStationOrder - 1;
                if (arrivedIdx >= 0 && arrivedIdx < stations.size()) {
                    displayName = stations.get(arrivedIdx).stationName;
                }
            } else {
                // 未到站：显示"下一站: [next]"
                int nextIdx = nearestVehicle.currentStationOrder;  // currentStationOrder 是 1-indexed,数组 0-indexed
                if (nextIdx >= 0 && nextIdx < stations.size()) {
                    displayName = stations.get(nextIdx).stationName;
                } else {
                    isTerminal = true;
                }
            }
            if (navigationMainFragment != null) {
                navigationMainFragment.setNextStationForNetwork(displayName, isArrived, isTerminal);
            }
            lastFragmentDisplayStationOrder = nearestVehicle.currentStationOrder;
            lastFragmentDisplayIsArrived = isArrived;
        }

    }

    int lastVoiceStationOrder;
    boolean lastVehicleWasArrived = false;
    private String trackedVehiclePlate = null;
    // ⭐ fragment 端"下一站/已到达"显示状态记忆（独立于 lastVoiceStationOrder）
    private int lastFragmentDisplayStationOrder = -2;
    private boolean lastFragmentDisplayIsArrived = false;

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
                networkModeText.setText("GPS " + String.format(Locale.getDefault(), "%02d", GpsWarmingUp.getSatelliteCount())
                        + "/" + String.format(Locale.getDefault(), "%02d", GpsWarmingUp.getTotalSatelliteCount()));
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
        // 反注册语音包配置状态监听，避免 Activity 泄漏
        if (voicepackConfigListener != null) {
            VoicePackManager.getInstance(this).removeConfigStateListener(voicepackConfigListener);
            voicepackConfigListener = null;
        }
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
        // ⭐ 清除静态引用
        currentInstance = null;
    }

    private String getCurrentDirectionId() {
        BusApiClient.BusLineDirection direction = getCurrentDirectionData();
        return direction != null ? direction.id : "";
    }

    // ===== POV模式暂停/恢复业务 =====

    /**
     * ⭐ 进入POV时暂停其他业务
     * 停止线路网络更新、GPS定位更新等，避免与CameraActivity的定位冲突
     */
    public static void pauseForPOV() {
        Log.d(TAG, "进入POV模式，暂停其他业务");
        if (currentInstance == null) {
            Log.w(TAG, "pauseForPOV: 当前Activity实例为空");
            return;
        }

        // ⭐ 停止 BusRealTimeManager 的更新
        if (currentInstance.realTimeManager != null) {
            currentInstance.realTimeManager.stopTracking();
            Log.d(TAG, "POV: 已停止 BusRealTimeManager 更新");
        }

        // ⭐ 停止网络刷新倒计时
        currentInstance.stopNetworkRefreshCountdown();
        Log.d(TAG, "POV: 已停止网络刷新倒计时");

        // ⭐ 不停止 GpsWarmingUp —— 让GPS站判断(handleGpsLocation)继续运行
        // CameraActivity的POV面板需要读取 isInsideStationRadius/gpsCurrentStationIndex
        // GpsWarmingUp 的定位和 BusLineDetailActivity 的 handleGpsLocation 互不冲突
        Log.d(TAG, "POV: 保持GpsWarmingUp运行（供POV面板读取站判断状态）");

        // ⭐ 暂停 AmapNavigationView 的定位
        if (currentInstance.navigationMainFragment != null
                && currentInstance.navigationMainFragment.getNavigation() != null) {
            currentInstance.navigationMainFragment.getNavigation().pauseLocationForPOV();
            Log.d(TAG, "POV: 已暂停 AmapNavigationView 定位");
        }
    }

    /**
     * ⭐ 退出POV时恢复其他业务
     * 恢复线路网络更新、GPS定位更新等
     */
    public static void resumeAfterPOV() {
        Log.d(TAG, "退出POV模式，恢复其他业务");
        if (currentInstance == null) {
            Log.w(TAG, "resumeAfterPOV: 当前Activity实例为空");
            return;
        }

        // ⭐ 恢复 AmapNavigationView 的定位
        if (currentInstance.navigationMainFragment != null
                && currentInstance.navigationMainFragment.getNavigation() != null) {
            currentInstance.navigationMainFragment.getNavigation().resumeLocationAfterPOV();
            Log.d(TAG, "POV: 已恢复 AmapNavigationView 定位");
        }

        // ⭐ 恢复 GPS 定位（GpsWarmingUp）会在 Activity.onResume() 中自动执行
        // 不需要手动恢复，因为 GpsWarmingUp.startWarmingUp() 在 onResume 中会被调用

        // ⭐ 恢复网络刷新倒计时会在 Activity.onResume() 中自动执行
        // startNetworkRefreshCountdown() 在 onResume 中会被调用

        Log.d(TAG, "POV: 业务恢复完成（定位和网络刷新将在 onResume 中自动恢复）");
    }
}