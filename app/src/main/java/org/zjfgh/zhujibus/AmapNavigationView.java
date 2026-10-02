package org.zjfgh.zhujibus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.AMap;
import com.amap.api.maps.AMapUtils;
import com.amap.api.maps.CameraUpdate;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.LocationSource;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.UiSettings;
import com.amap.api.maps.model.BitmapDescriptor;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.MyLocationStyle;
import com.amap.api.maps.model.Polyline;
import com.amap.api.maps.model.PolylineOptions;
import com.amap.api.maps.utils.overlay.SmoothMoveMarker;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.AMapNaviListener;
import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.AMapNaviViewOptions;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapLaneInfo;
import com.amap.api.navi.model.AMapModelCross;
import com.amap.api.navi.model.AMapNaviCameraInfo;
import com.amap.api.navi.model.AMapNaviCross;
import com.amap.api.navi.model.AMapNaviLocation;
import com.amap.api.navi.model.AMapNaviPath;
import com.amap.api.navi.model.AMapNaviRouteNotifyData;
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo;
import com.amap.api.navi.model.AMapServiceAreaInfo;
import com.amap.api.navi.model.AimLessModeCongestionInfo;
import com.amap.api.navi.model.AimLessModeStat;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.api.navi.model.NaviPoi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 高德地图 3D 导航管理器（高德导航 SDK + 高德定位 SDK）
 * <p>
 * 使用高德官方 AMapNaviView + AMapNavi 进行导航（替代纯地图 SDK），支持：
 * - GPS 模式：使用导航 SDK 的算路+导航引导功能
 * - 网络模式：自定义公交显示逻辑（marker、路线绘制等）
 * - 自定义路线绘制：通过 setAutoDrawRoute(false) 关闭内置路线样式
 * </p>
 * <p>
 * 定位使用 AMapLocationClient，能正确返回：
 * - 经纬度（GCJ-02，与高德地图坐标一致，无需转换）
 * - accuracy（精度）
 * - bearing（设备方向角，用于罗盘旋转）
 * - speed（速度）
 * </p>
 */
public class AmapNavigationView implements LocationSource, AMapLocationListener, AMapNaviListener {
    private static final String TAG = "AmapNavigationView";

    /**
     * 速度变化回调接口（用于网络模式下显示车辆移动速度）
     */
    public interface OnSpeedChangeListener {
        void onSpeedChanged(float speedKmh);
    }

    public interface OnLocationUpdateListener {
        void onLocationUpdated(Location location);
    }

    private static final long POV_LOCATION_CALLBACK_INTERVAL_MS = 1000L;

    private OnSpeedChangeListener speedChangeListener;
    private OnLocationUpdateListener locationUpdateListener;
    private Location lastPovCallbackLocation;
    private long lastPovCallbackTime;

    public void setSpeedChangeListener(OnSpeedChangeListener listener) {
        this.speedChangeListener = listener;
    }

    public void setLocationUpdateListener(OnLocationUpdateListener listener) {
        this.locationUpdateListener = listener;
    }

    private final Context appContext;
    private TextureMapView mapView;  // ⭐ 改回 TextureMapView（官方自定义方式）
    private AMap aMap;
    private UiSettings uiSettings;
    private OnLocationChangedListener locationListener;
    // 主线程 Handler，用于在后台线程调用时切换到主线程
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Polyline routePolyline;
    // ⭐ 路线分层：白色光晕 + 绿色主线
    private Polyline routeGlowPolyline;
    private Polyline routeMainPolyline;
    // ⭐ 沿线方向箭头 marker 列表（参考 CSDN 方案：Marker + setRotation）
    private final List<Marker> arrowMarkers = new ArrayList<>();
    private BitmapDescriptor arrowMarkerIcon;
    // ⭐ 站点对比叠加：官方点（红）/ 高德覆盖点（蓝）marker，以及两者偏移连线
    private final List<Marker> stationMarkers = new ArrayList<>();
    private final List<Polyline> stationOffsetLines = new ArrayList<>();
    // ⭐ 当前地图缩放级别（用于箭头密度自适应）
    private float currentZoom = 14f;
    private boolean isCompassMode = true;

    // 高德定位 SDK
    private AMapLocationClient locationClient;
    private AMapLocationClientOption locationOption;

    // ⭐ 导航 SDK 核心类
    private AMapNavi aMapNavi;

    // ⭐ 自车位置管理 Overlay（官方方式：自定义车标）
    private CarOverlay carOverlay;

    // 用于计算"运动方向"的历史位置
    private double lastLat = 0;
    private double lastLng = 0;
    private long lastLocTimeMs = 0;

    // ⭐ 最新定位坐标（不受速度影响，供POV面板显示）
    private double currentLocLat = 0;
    private double currentLocLng = 0;

    /**
     * ⭐ 获取最新GPS坐标（供POV面板显示）
     * @return [lat, lng] 或 null（无定位时）
     */
    public double[] getLastLocation() {
        if (currentLocLat != 0 && currentLocLng != 0) {
            return new double[]{currentLocLat, currentLocLng};
        }
        return null;
    }
    // 速度阈值：> 1.5 m/s (≈ 5.4 km/h) 用运动方向，≤ 1.5 m/s 用设备方向
    private static final float SPEED_THRESHOLD_MPS = 1.5f;
    // ⭐ 标记：是否已应用首次导航视角（首次收到定位时强制应用一次）
    private boolean perspectiveAppliedOnFirstFix = false;
    // ⭐ 标记：当前是否为 GPS 模式（GPS 模式才应用 3D 导航视角，网络模式保持自由视角）
    private boolean isGpsMode = false;
    // ⭐ 标记：GPS导航SDK是否已启动（避免重复启动）
    private boolean gpsNavigationStarted = false;
    // ⭐ 标记：地图锁车态（true=跟随车移动，false=用户可拖动）
    private boolean isCarLocked = true;
    // ⭐ Handler：用于触摸后延迟锁车
    private final Handler carLockHandler = new Handler(Looper.getMainLooper());
    private static final int CAR_LOCK_DELAY_MS = 3000;  // 3秒后自动锁车
    /** 最近一次 AMapLocation 定位成功（errorCode == 0）的时间戳（毫秒） */
    private volatile long lastGpsSuccessTimeMs = 0L;
    /** 网络模式下自适应 zoom（drawRoute 后 500ms 记录，切回网络模式时恢复） */
    private float lastNetworkAdaptiveZoom = -1f;

    // ⭐ 标记：进入POV模式前的模式（true=GPS模式, false=网络模式）
    private boolean modeBeforePovIsGps = false;

    // ---- 公交车辆 marker 管理（仅网络模式） ----
    /** 当前线路方向的所有公交车辆 SmoothMoveMarker（车牌 → marker） */
    private final Map<String, SmoothMoveMarker> busMarkers = new HashMap<>();
    /** 记录每个车辆上一帧位置（用于决定从哪开始平滑移动） */
    private final Map<String, LatLng> lastBusPos = new HashMap<>();
    /** 记录每个车辆上一帧的时间戳（ms，用于算出 SmoothMoveMarker 的时长） */
    private final Map<String, Long> lastBusUpdateMs = new HashMap<>();
    /** 记录每个车辆上一次有效移动的位置（用于速度计算，忽略静止时间） */
    private final Map<String, LatLng> lastMovingPos = new HashMap<>();
    /** 记录每个车辆上一次有效移动的时间戳（ms，用于速度计算） */
    private final Map<String, Long> lastMovingTimeMs = new HashMap<>();
    /** 记录每个车辆最近一次静止结束的时间（用于计算实际移动时间） */
    private final Map<String, Long> lastStationaryEndMs = new HashMap<>();
    /** 静止检测阈值：距离变化小于此值视为静止（米） */
    private static final double STATIONARY_THRESHOLD_M = 5.0;

    // ---- 速度估算滑动窗口 ----
    /** 速度估算窗口：记录一次移动样本 */
    private static class SpeedSample {
        final double distanceM;    // 本次移动距离（米）
        final long movingTimeMs;   // 本次有效移动时间（毫秒，已排除静止时间）
        final long timestampMs;    // 记录时间戳

        SpeedSample(double distanceM, long movingTimeMs, long timestampMs) {
            this.distanceM = distanceM;
            this.movingTimeMs = movingTimeMs;
            this.timestampMs = timestampMs;
        }
    }
    /** 每个车辆的速度估算窗口（车牌 → 样本列表） */
    private final Map<String, List<SpeedSample>> speedWindows = new HashMap<>();
    /** 窗口时长：只统计最近 60 秒内的移动 */
    private static final long SPEED_WINDOW_MS = 60000L;
    /** 最小有效时间：累计有效时间少于此值时不计算速度（毫秒） */
    private static final long MIN_MOVING_TIME_MS = 3000L;
    /** 目标站点位置（用于计算最近车辆的速度） */
    private LatLng targetStationPos = null;
    /** 目标站点索引（用于找到最近车辆，和 eta 逻辑一致） */
    private int targetStationIndex = -1;

    // ⭐ 地图跟随车辆模式：选中站点后，地图跟 SmoothMoveMarker.getPosition() 走，不动 zoom/tilt/bearing
    private boolean isFollowBusMode = false;
    private String followedVehiclePlate = null;
    private BitmapDescriptor busIconDescriptor;
    private boolean busIconLoaded = false;

    // ---- 路线点（用于车辆 marker 的 snap-to-road 平滑动画） ----
    private List<LatLng> routePoints = new ArrayList<>();

    // ⭐ 地图加载完成标志与就绪回调（避免在 onMapLoaded 前 addPolyline 不生效，导致路线绘制延迟）
    private boolean isMapLoaded = false;
    private Runnable onMapReadyCallback;

    /**
     * 罗盘模式下的目标相机参数（完全俯视 + 适中缩放）
     * 使用 static final 防止在 onLocationChanged 中被重置
     */
    public static final float TARGET_ZOOM = 15f;   // 适中缩放（不要太贴地）
    public static final float TARGET_TILT = 0f;    // 完全俯视（2D视角）
    /** GPS 模式相对导航缩放再放大一级（+1 缩放层级，zoom 越大越贴近地面） */
    private static final float GPS_ZOOM_BONUS = 1f;
    /** GPS 模式导航视角参数（经典 3D 跟随视角）
     *  ⚠️ TARGET_TILT=0 为完全俯视，等同网络模式，必须有倾斜角才有“导航模式”透视效果 */
    private static final float GPS_NAV_ZOOM = 15f;   // 适中缩放
    private static final float GPS_NAV_TILT = 50f;   // 3D 透视倾斜（约 50°）
    private float navigationZoom = TARGET_ZOOM;
    private float navigationTilt = TARGET_TILT;

    public AmapNavigationView(Context context, TextureMapView mapView) {
        this.appContext = context.getApplicationContext();
        this.mapView = mapView;  // ⭐ 使用 TextureMapView（官方自定义方式）
        Log.d(TAG, "[INIT] AmapNavigationView constructor called, mapView=" + mapView);
    }

    public void setNavigationCamera(float zoom, float tilt) {
        navigationZoom = zoom > 0 ? zoom : TARGET_ZOOM;
        navigationTilt = tilt;
        if (isGpsMode && aMap != null) {
            applyNavigationCameraPerspective();
        }
    }

    /**
     * ⭐ 外部（统一定位源 GpsWarmingUp）驱动车标与相机跟随。
     * 用于 POV 小地图：真实 GPS 与模拟位置都经 GpsWarmingUp 下发，而 POV 的 AmapNavigationView
     * 实例自身不跑模拟（doSimStep 在线路详情页实例上），因此由 CameraActivity 把位置转发到此处，
     * 让 POV 小地图也能跟随——包括模拟位置。
     */
    public void drawCarFromExternal(Location location) {
        if (location == null || carOverlay == null || aMap == null) return;
        LatLng pos = new LatLng(location.getLatitude(), location.getLongitude());
        float bearing = location.getBearing();
        if (bearing < 0 || bearing > 360) bearing = 0f;
        carOverlay.draw(aMap, pos, bearing);
    }

    /** POV 小地图：开启外部车标跟随时保留其当前缩放/倾角，避免被强制改视角 */
    public void enableExternalCarFollow() {
        if (carOverlay != null) carOverlay.setPreserveCamera(true);
    }

    /**
     * 必须在 Activity.onCreate 中调用 —— 启动地图引擎
     * 高德 TextureMapView 要求先调用 onCreate(savedInstanceState) 才能渲染地图
     */
    public void onCreate(Bundle savedInstanceState) {
        if (mapView == null) {
            Log.e(TAG, "[INIT] onCreate skipped: mapView is null");
            return;
        }
        // ⭐ 关键修复：必须在 mapView.onCreate() 之前调用隐私协议！
        try {
            MapsInitializer.initialize(appContext);
        } catch (Throwable t) {
            Log.w(TAG, "[INIT] MapsInitializer.initialize failed: " + t.getMessage());
        }
        initPrivacy();
        Log.d(TAG, "[INIT] privacy done");

        try {
            mapView.onCreate(savedInstanceState);
            Log.d(TAG, "[INIT] mapView.onCreate() succeeded");
        } catch (Throwable t) {
            Log.e(TAG, "[INIT] mapView.onCreate() failed: " + t.getMessage(), t);
            return;
        }

        // ⭐ 初始化导航核心类
        initAMapNavi();

        init();
        Log.d(TAG, "[INIT] map + location init done");
    }

    /**
     * ⭐ 设置地图加载完成回调（供外部在地图真正就绪后绘制路线）
     * 若地图已加载完成，回调立即执行；否则在 onMapLoaded 时执行。
     * 避免在 onMapLoaded 前调用 aMap.addPolyline 不生效，导致路线绘制延迟。
     */
    public void setOnMapReadyCallback(Runnable r) {
        this.onMapReadyCallback = r;
        if (isMapLoaded && r != null) {
            r.run();
        }
    }

    /**
     * 高德地图隐私协议初始化（必须在任何地图/定位/导航 API 之前调用）
     */
    private void initPrivacy() {
        try {
            // 地图 SDK 隐私协议
            MapsInitializer.updatePrivacyShow(appContext, true, true);
            MapsInitializer.updatePrivacyAgree(appContext, true);
        } catch (Throwable t) {
            Log.e(TAG, "MapsInitializer privacy failed: " + t.getMessage());
        }
        try {
            // 定位 SDK 隐私协议
            AMapLocationClient.updatePrivacyShow(appContext, true, true);
            AMapLocationClient.updatePrivacyAgree(appContext, true);
        } catch (Throwable t) {
            Log.e(TAG, "AMapLocationClient privacy failed: " + t.getMessage());
        }
    }

    /**
     * 初始化导航 SDK 核心类 AMapNavi
     */
    private void initAMapNavi() {
        try {
            aMapNavi = AMapNavi.getInstance(appContext);
            if (aMapNavi != null) {
                aMapNavi.addAMapNaviListener(this);
                Log.d(TAG, "[NAV] AMapNavi initialized successfully");
            }
        } catch (Throwable t) {
            Log.e(TAG, "initAMapNavi failed: " + t.getMessage(), t);
        }
    }

    private void init() {
        if (mapView == null) {
            return;
        }
        // ⭐ 通过 TextureMapView.getMap() 获取 AMap 对象进行地图操作
        aMap = mapView.getMap();
        uiSettings = aMap != null ? aMap.getUiSettings() : null;

        // ⭐ 预生成路线方向箭头 marker icon（参考 CSDN 方案：Marker + setRotation）
        arrowMarkerIcon = createArrowMarkerIcon();

        // ⭐ 初始化自车位置管理 Overlay（官方方式）
        carOverlay = new CarOverlay(appContext, mapView);

        // 监听地图加载成功/失败（排查 KEY/SHA1 不匹配的关键日志）
        if (aMap != null) {
            try {
                aMap.setOnMapLoadedListener(() -> {
                    Log.d(TAG, "[MAP] onMapLoaded —— 地图瓦片加载成功，开始应用导航视角");
                    isMapLoaded = true;
                    // ⭐ GPS 模式可能在地图就绪前（aMap==null）就被 setGpsMode(true) 设置过，
                    //    那时 3D 参数/罗盘/锁车逻辑被提前 return 跳过，这里先补设再应用一次
                    if (isGpsMode) {
                        navigationZoom = GPS_NAV_ZOOM;
                        navigationTilt = GPS_NAV_TILT;
                        setCompassMode(true);
                        isCarLocked = true;
                    }
                    applyNavigationCameraPerspective();
                    if (onMapReadyCallback != null) {
                        onMapReadyCallback.run();
                    }
                });
                aMap.setOnMapClickListener(latLng -> Log.v(TAG, "[MAP] click at " + latLng));

                // ⭐ 监听地图触摸事件：解锁车 → 3秒后自动锁车
                aMap.setOnMapTouchListener(motionEvent -> {
                    if (isGpsMode && isCarLocked) {
                        unlockCar();  // 用户触摸时解锁车
                        // 3秒后自动锁车
                        carLockHandler.removeCallbacks(this::lockCar);
                        carLockHandler.postDelayed(this::lockCar, CAR_LOCK_DELAY_MS);
                    }
                });

                // ⭐ GPS模式：不使用 MyLocationStyle，使用 CarOverlay 绘制车标
                // 官方方式：完全自定义车标，禁用内置定位图层
                aMap.setMyLocationEnabled(false);  // 禁用内置定位图层

                // ⭐ 监听地图缩放变化 → 自适应调整箭头密度
                aMap.setOnCameraChangeListener(new AMap.OnCameraChangeListener() {
                    @Override
                    public void onCameraChange(CameraPosition cameraPosition) { /* drag 过程中 */ }

                    @Override
                    public void onCameraChangeFinish(CameraPosition cameraPosition) {
                        float z = cameraPosition.zoom;
                        if (z < 3f) return;
                        if (Math.abs(z - currentZoom) > 0.3f) {
                            Log.d(TAG, "[ZOOM] OnCameraChangeFinish " + currentZoom + " -> " + z);
                            currentZoom = z;
                            if (routePoints != null && !routePoints.isEmpty()) {
                                drawArrowMarkers(routePoints);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "[MAP] set listener failed: " + t.getMessage());
            }
        }

        // UI 设置：左上角小罗盘（点击回正北）、禁用缩放按钮、禁用定位按钮
        if (uiSettings != null) {
            uiSettings.setCompassEnabled(true);
            uiSettings.setZoomControlsEnabled(false);
            uiSettings.setMyLocationButtonEnabled(false);
            uiSettings.setRotateGesturesEnabled(true);
            uiSettings.setTiltGesturesEnabled(false);
            uiSettings.setScaleControlsEnabled(false);
        }

        // ⭐ GPS模式：不使用 MyLocationStyle，使用 CarOverlay 绘制车标（官方方式）
        // 初始化时禁用内置定位图层
        if (aMap != null) {
            aMap.setMyLocationEnabled(false);  // 禁用内置定位图层
        }

        // 初始化高德定位 SDK（替代系统 GPS）
        initAmapLocation();
    }

    /**
     * 应用导航视角：3D 透视俯视 + 较高缩放 + 禁止手势
     * ⭐ 注意：只在导航启动时调用一次，后续通过定位回调更新地图位置
     */
    private void applyNavigationCameraPerspective() {
        if (aMap == null) return;
        if (!isGpsMode) {
            Log.d(TAG, "[MAP] applyNavigationCameraPerspective skipped: not in GPS mode");
            return;
        }
        // ⭐ GPS 模式：在导航缩放基础上再放大一级（zoom + GPS_ZOOM_BONUS），视野更贴近车辆
        float gpsZoom = navigationZoom + GPS_ZOOM_BONUS;
        try {
            // ⭐ 导航视角：只设置 zoom、tilt，不设置 target 和 bearing
            //    target 和 bearing 会通过定位回调自动更新（跟随车移动和旋转）
            CameraPosition currentPos = aMap.getCameraPosition();
            CameraPosition cp = new CameraPosition.Builder()
                    .target(currentPos.target)  // 保持当前位置
                    .zoom(gpsZoom)              // 可由不同页面单独配置，GPS 模式额外 +1 级
                    .tilt(navigationTilt)       // 可由不同页面单独配置
                    .bearing(currentPos.bearing) // 保持当前方向
                    .build();
            aMap.moveCamera(CameraUpdateFactory.newCameraPosition(cp));

            // ⭐ 禁止用户手势改变视角（锁车态）
            aMap.getUiSettings().setTiltGesturesEnabled(false);      // 禁止倾斜手势
            aMap.getUiSettings().setRotateGesturesEnabled(false);    // 禁止旋转手势
            // ⚠️ 不禁止缩放手势，让用户可以调整视野范围
            
            Log.d(TAG, "[MAP] 导航视角已应用：zoom=" + gpsZoom + ", tilt=" + navigationTilt + ", 锁车态已启用");
        } catch (Throwable t) {
            Log.e(TAG, "applyNavigationCameraPerspective failed: " + t.getMessage(), t);
        }
    }

    /**
     * ⭐ 锁车：地图跟随车移动和旋转
     */
    private void lockCar() {
        isCarLocked = true;
        Log.d(TAG, "[NAV] 锁车态已启用：地图跟随车移动和旋转");
        if (carOverlay != null) {
            carOverlay.setLock(true);  // ⭐ 调用 CarOverlay 的锁车方法
        }
        if (aMap != null) {
            // 禁止用户手势（锁车态）
            aMap.getUiSettings().setTiltGesturesEnabled(false);
            aMap.getUiSettings().setRotateGesturesEnabled(false);
        }
    }

    /**
     * ⭐ 解锁车：用户可以拖动地图
     */
    private void unlockCar() {
        isCarLocked = false;
        Log.d(TAG, "[NAV] 解锁车态：用户可以拖动地图");
        if (carOverlay != null) {
            carOverlay.setLock(false);  // ⭐ 调用 CarOverlay 的解锁车方法
        }
        if (aMap != null) {
            // 允许用户手势（但保持3D视角）
            aMap.getUiSettings().setTiltGesturesEnabled(true);      // 允许倾斜手势
            aMap.getUiSettings().setRotateGesturesEnabled(true);    // 允许旋转手势
            aMap.getUiSettings().setZoomGesturesEnabled(true);      // 允许缩放手势
            aMap.getUiSettings().setScrollGesturesEnabled(true);    // 允许拖动手势
        }
    }

    /**
     * 初始化高德定位 SDK
     */
    private void initAmapLocation() {
        try {
            locationClient = new AMapLocationClient(appContext);
            locationOption = new AMapLocationClientOption();

            locationOption.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            locationOption.setInterval(3000);
            locationOption.setSensorEnable(false);
            locationOption.setNeedAddress(false);
            locationOption.setOnceLocation(false);
            locationOption.setLocationCacheEnable(true);

            locationClient.setLocationOption(locationOption);
            locationClient.setLocationListener(this);

            // ⭐ 启动后立即开始定位（不延迟到第一次进入 GPS 模式）
            // 避免 GPS↔网络模式反复切换时反复冷启动 AMapLocationClient，导致第二次进入 GPS 模式
            // 需要 1-3 秒才能拿到首个 fix 的"卡顿感"
            try {
                locationClient.startLocation();
                Log.d(TAG, "[NAV] initAmapLocation + startLocation ok, mode=Hight_Accuracy, interval=3000ms");
            } catch (Throwable t) {
                Log.e(TAG, "initAmapLocation.startLocation failed: " + t.getMessage(), t);
            }
        } catch (Throwable t) {
            Log.e(TAG, "initAmapLocation failed: " + t.getMessage(), t);
        }
    }

    // ========== LocationSource 接口（被高德地图 SDK 调用） ==========

    @Override
    public void activate(OnLocationChangedListener onLocationChangedListener) {
        Log.d(TAG, "[NAV] activate called —— 地图请求定位源");
        this.locationListener = onLocationChangedListener;
        startAmapLocation();
        if (isCompassMode) {
            applyNavigationCameraPerspective();
        }
    }

    @Override
    public void deactivate() {
        Log.d(TAG, "[NAV] deactivate called —— 地图不再需要定位源");
        this.locationListener = null;
        stopAmapLocation();
    }

    private void startAmapLocation() {
        if (locationClient == null) {
            Log.w(TAG, "[NAV] startAmapLocation skipped: client null");
            return;
        }
        try {
            locationClient.startLocation();
            Log.d(TAG, "[NAV] AMapLocationClient.startLocation() called");
        } catch (Throwable t) {
            Log.e(TAG, "startLocation failed: " + t.getMessage(), t);
        }
    }

    private void stopAmapLocation() {
        if (locationClient != null) {
            try {
                locationClient.stopLocation();
                Log.d(TAG, "[NAV] AMapLocationClient.stopLocation() called");
            } catch (Throwable t) {
                Log.e(TAG, "stopLocation failed: " + t.getMessage());
            }
        }
    }

    // ========== POV模式暂停/恢复定位 ==========

    /**
     * ⭐ 进入POV模式
     * 1. 记录当前模式。
     * 2. 强制切到GPS模式（以确保能持续获取最新坐标供POV面板显示）。
     * 3. 注意：不再 stopAmapLocation()，因为POV面板需要实时坐标。
     */
    public void pauseLocationForPOV() {
        this.modeBeforePovIsGps = this.isGpsMode;
        Log.d(TAG, "[POV] 进入POV，保存原始模式: " + (modeBeforePovIsGps ? "GPS" : "网络"));

        if (!isGpsMode) {
            setGpsMode(true);
            Log.d(TAG, "[POV] 进入POV，强制切换到GPS模式以获取更新");
        }
    }

    /**
     * ⭐ 退出POV模式
     * 恢复到进入POV之前的原始状态（网络模式或GPS模式）
     */
    public void resumeLocationAfterPOV() {
        Log.d(TAG, "[POV] 退出POV，准备恢复原始模式: " + (modeBeforePovIsGps ? "GPS" : "网络"));
        setGpsMode(modeBeforePovIsGps);
        Log.d(TAG, "[POV] 模式已恢复");
    }

    // ========== AMapLocationListener 接口 ==========

    @Override
    public void onLocationChanged(AMapLocation aMapLocation) {
        if (aMapLocation == null) {
            Log.w(TAG, "onLocationChanged: location is null");
            return;
        }
        if (aMapLocation.getErrorCode() != AMapLocation.LOCATION_SUCCESS) {
            Log.w(TAG, "onLocationChanged error: code=" + aMapLocation.getErrorCode()
                    + ", msg=" + aMapLocation.getErrorInfo());
            return;
        }
        // ⭐ 模拟进行中（全局标志）：忽略本实例真实 GPS 回调（车标/相机/坐标/速度/外部监听均改由模拟位置驱动）
        if (simActiveGlobally) {
            return;
        }
        lastGpsSuccessTimeMs = System.currentTimeMillis();

        // ⭐ 始终更新最新定位坐标（不受速度影响，供POV面板显示）
        currentLocLat = aMapLocation.getLatitude();
        currentLocLng = aMapLocation.getLongitude();

        Log.d(TAG, String.format("[NAV] loc: lat=%.6f, lng=%.6f, acc=%.1fm, bearing=%.1f, speed=%.1f, provider=%s",
                aMapLocation.getLatitude(),
                aMapLocation.getLongitude(),
                aMapLocation.getAccuracy(),
                aMapLocation.getBearing(),
                aMapLocation.getSpeed(),
                aMapLocation.getProvider()));

        // ⭐ 仅当 AMap SDK 注入了 LocationSource 的 listener 时才转发（只有 setMyLocationEnabled(true) 才会注入）
        // 注意：即使 locationListener == null，**下面启动导航+应用视角的逻辑也必须继续执行**
        if (locationListener != null) {
            Location location = new Location(aMapLocation.getProvider());
            location.setLatitude(aMapLocation.getLatitude());
            location.setLongitude(aMapLocation.getLongitude());
            location.setAccuracy(aMapLocation.getAccuracy());
            location.setTime(aMapLocation.getTime());
            location.setSpeed(aMapLocation.getSpeed());

            float speed = aMapLocation.getSpeed();
            float deviceBearing = aMapLocation.getBearing();
            float bearing = deviceBearing;
            if (deviceBearing >= 0 && deviceBearing <= 360) {
                bearing = deviceBearing;
            } else {
                bearing = -1f;
            }

            if (speed > SPEED_THRESHOLD_MPS) {
                double curLat = aMapLocation.getLatitude();
                double curLng = aMapLocation.getLongitude();
                long curTime = aMapLocation.getTime();

                if (lastLocTimeMs > 0 && lastLat != 0) {
                    float movementBearing = computeBearing(lastLat, lastLng, curLat, curLng);
                    if (movementBearing >= 0) {
                        bearing = movementBearing;
                        Log.v(TAG, String.format("[NAV] using MOVEMENT bearing=%.1f (speed=%.1f m/s, dev=%.1f)",
                                movementBearing, speed, deviceBearing));
                    }
                }

                lastLat = curLat;
                lastLng = curLng;
                lastLocTimeMs = curTime;
            } else {
                if (lastLat != 0) {
                    Log.v(TAG, String.format("[NAV] low speed (%.1f m/s), reset history, using DEVICE bearing=%.1f",
                            speed, deviceBearing));
                }
                lastLat = 0;
                lastLng = 0;
                lastLocTimeMs = 0;
            }

            if (bearing >= 0 && bearing <= 360) {
                location.setBearing(bearing);
            }
            notifyLocationUpdated(location);
            locationListener.onLocationChanged(location);
        } else {
            Location location = new Location(aMapLocation.getProvider());
            location.setLatitude(aMapLocation.getLatitude());
            location.setLongitude(aMapLocation.getLongitude());
            location.setAccuracy(aMapLocation.getAccuracy());
            location.setTime(aMapLocation.getTime());
            location.setSpeed(aMapLocation.getSpeed());
            if (aMapLocation.getBearing() >= 0 && aMapLocation.getBearing() <= 360) {
                location.setBearing(aMapLocation.getBearing());
            }
            notifyLocationUpdated(location);
        }

        // ⭐ GPS模式首次定位成功：启动导航SDK的算路+导航
        if (isGpsMode && !gpsNavigationStarted && targetStationPos != null && aMapNavi != null) {
            gpsNavigationStarted = true;
            Log.d(TAG, "[NAV] GPS首次定位成功，启动导航SDK算路到目标站点");
            startGpsNavigation(
                    aMapLocation.getLatitude(),
                    aMapLocation.getLongitude(),
                    targetStationPos.latitude,
                    targetStationPos.longitude
            );
        }

        // ⭐ GPS 模式且导航SDK未启动（例如非诸暨线路未选目标站点）时，
        //    直接用真实GPS绘制车标并跟随（锁车态）；导航SDK启动后改由其回调绘制，避免重复。
        //    模拟期间由 doSimStep 绘制，这里跳过，避免真实GPS干扰车标。
        if (isGpsMode && !gpsNavigationStarted && !simulating && carOverlay != null && aMap != null) {
            LatLng carPos = new LatLng(aMapLocation.getLatitude(), aMapLocation.getLongitude());
            float bearing = aMapLocation.getBearing();
            if (bearing < 0 || bearing > 360) bearing = 0f;
            carOverlay.draw(aMap, carPos, bearing);
        }

        // ⭐ 注意：导航SDK启动后，不再使用 AMapLocationClient 的定位数据绘制车标
        // 而是使用导航SDK的 onLocationChange(AMapNaviLocation) 回调
        // （applyNavigationCameraPerspective 已在 setGpsMode(true) 立即调用，无需在此重复）
    }

    private void notifyLocationUpdated(Location location) {
        if (locationUpdateListener == null || location == null) return;
        long now = System.currentTimeMillis();
        if (lastPovCallbackLocation != null) {
            long elapsed = now - lastPovCallbackTime;
            if (elapsed < POV_LOCATION_CALLBACK_INTERVAL_MS) {
                lastPovCallbackLocation.set(location);
                return;
            }
            lastPovCallbackLocation.set(location);
        } else {
            lastPovCallbackLocation = new Location(location);
        }
        lastPovCallbackTime = now;
        locationUpdateListener.onLocationUpdated(location);
    }

    // ========== AMapNaviListener 接口（导航回调） ==========

    @Override
    public void onInitNaviFailure() {
        Log.e(TAG, "[NAVI] onInitNaviFailure —— 导航初始化失败");
    }

    @Override
    public void onInitNaviSuccess() {
        Log.d(TAG, "[NAVI] onInitNaviSuccess —— 导航初始化成功");
    }

    @Override
    public void onStartNavi(int type) {
        Log.d(TAG, "[NAVI] onStartNavi —— 导航开始，type=" + type);
        // ⭐ 导航启动时，强制应用导航视角（锁车态、3D视角）
        if (aMap != null) {
            applyNavigationCameraPerspective();
            Log.d(TAG, "[NAVI] 导航视角已应用：锁车态 + 3D视角");
        }
    }

    @Override
    public void onTrafficStatusUpdate() {
        Log.v(TAG, "[NAVI] onTrafficStatusUpdate —— 路况更新");
    }

    @Override
    public void onLocationChange(AMapNaviLocation location) {
        // ⭐ 导航SDK的定位回调（官方方式：使用这个回调绘制车标）
        if (location == null || location.getCoord() == null) return;
        // ⭐ 模拟进行中（全局标志）：忽略导航 SDK 的真实 GPS 回调，避免其车标/数据抢占模拟位置
        if (simActiveGlobally) {
            return;
        }
        Location androidLocation = new Location("amap_navi");
        androidLocation.setLatitude(location.getCoord().getLatitude());
        androidLocation.setLongitude(location.getCoord().getLongitude());
        androidLocation.setTime(System.currentTimeMillis());
        if (location.getSpeed() >= 0) {
            androidLocation.setSpeed(location.getSpeed());
        }
        if (location.getBearing() >= 0 && location.getBearing() <= 360) {
            androidLocation.setBearing(location.getBearing());
        }
        notifyLocationUpdated(androidLocation);

        if (isGpsMode && carOverlay != null && aMap != null) {
            LatLng carPos = new LatLng(location.getCoord().getLatitude(), location.getCoord().getLongitude());
            float carBearing = location.getBearing();
            carOverlay.draw(aMap, carPos, carBearing);  // ⭐ 绘制车标 + 更新地图视角（锁车态下）
            //Log.d(TAG, "[NAVI] onLocationChange: lat=" + carPos.latitude + ", lng=" + carPos.longitude + ", bearing=" + carBearing);
        }
    }

    @Override
    public void onGetNavigationText(int type, String text) {
        Log.v(TAG, "[NAVI] onGetNavigationText: type=" + type + ", text=" + text);
    }

    @Override
    public void onNaviInfoUpdate(com.amap.api.navi.model.NaviInfo naviInfo) {
        if (naviInfo != null) {
            Log.v(TAG, "[NAVI] onNaviInfoUpdate: 当前路段距离=" + naviInfo.getCurStepRetainDistance());
        }
    }

    @Override
    public void onCalculateRouteSuccess(int[] ints) {
        Log.d(TAG, "[NAVI] onCalculateRouteSuccess —— 算路成功，路线数量=" + (ints != null ? ints.length : 0));
        // ⭐ GPS模式下算路成功后，自动开始导航
        if (isGpsMode && aMapNavi != null) {
            aMapNavi.startNavi(NaviType.GPS);
            Log.d(TAG, "[NAVI] GPS导航已启动");
        }
    }

    @Override
    public void onCalculateRouteFailure(int i) {
        Log.e(TAG, "[NAVI] onCalculateRouteFailure —— 算路失败，错误码=" + i);
    }



    @Override
    public void onReCalculateRouteForYaw() {
        Log.d(TAG, "[NAVI] onReCalculateRouteForYaw —— 偏航重新算路");
    }

    @Override
    public void onReCalculateRouteForTrafficJam() {
        Log.d(TAG, "[NAVI] onReCalculateRouteForTrafficJam —— 拥堵重新算路");
    }

    @Override
    public void onArrivedWayPoint(int i) {

    }

    @Override
    public void onGpsOpenStatus(boolean b) {

    }



    @Override
    public void updateCameraInfo(com.amap.api.navi.model.AMapNaviCameraInfo[] aMapNaviCameraInfos) {
    }

    @Override
    public void updateIntervalCameraInfo(AMapNaviCameraInfo aMapNaviCameraInfo, AMapNaviCameraInfo aMapNaviCameraInfo1, int i) {

    }

    @Override
    public void onServiceAreaUpdate(AMapServiceAreaInfo[] aMapServiceAreaInfos) {

    }

    @Override
    public void showCross(AMapNaviCross aMapNaviCross) {

    }

    @Override
    public void hideCross() {

    }

    @Override
    public void showModeCross(AMapModelCross aMapModelCross) {

    }

    @Override
    public void hideModeCross() {

    }

    @Override
    public void onGetNavigationText(String text) {
        Log.v(TAG, "[NAVI] onGetNavigationText: " + text);
    }

    @Override
    public void onEndEmulatorNavi() {
        Log.d(TAG, "[NAVI] onEndEmulatorNavi —— 模拟导航结束");
    }

    @Override
    public void onArriveDestination() {
        Log.d(TAG, "[NAVI] onArriveDestination —— 到达目的地");
    }

    @Override
    public void notifyParallelRoad(int i) {
        Log.v(TAG, "[NAVI] notifyParallelRoad: " + i);
    }

    @Override
    public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo[] aMapNaviTrafficFacilityInfos) {

    }

    @Override
    public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo aMapNaviTrafficFacilityInfo) {

    }


    @Override
    public void updateAimlessModeStatistics(AimLessModeStat statistics) {
        Log.v(TAG, "[NAVI] updateAimlessModeStatistics");
    }

    @Override
    public void updateAimlessModeCongestionInfo(AimLessModeCongestionInfo congestionInfo) {
        Log.v(TAG, "[NAVI] updateAimlessModeCongestionInfo");
    }

    @Override
    public void onPlayRing(int i) {

    }

    @Override
    public void onCalculateRouteSuccess(AMapCalcRouteResult aMapCalcRouteResult) {
        Log.d(TAG, "[NAVI] onCalculateRouteSuccess —— 算路成功");

        // ⭐ GPS模式：不绘制导航SDK的路线，使用公交车线路（routePoints）
        //    导航SDK只用于导航引导（语音、方向提示），路线显示用公交实际路线
        if (aMapNavi != null && aMap != null) {
            // ⭐ 不调用 drawNaviRoute，保留已有的公交路线（routePoints）
            //    公交路线在 NavigationMainFragment.drawRoute() 中已绘制

            // ⭐ 启动GPS导航（仅用于导航引导，不绘制路线）
            aMapNavi.startNavi(NaviType.GPS);
            Log.d(TAG, "[NAVI] GPS导航已启动（使用公交车线路，不绘制导航路线）");
        }
    }

    /**
     * ⭐ 绘制导航SDK的路线（官方方式）
     */
    private void drawNaviRoute(AMapNaviPath naviPath) {
        if (aMap == null || naviPath == null) return;

        try {
            // ⭐ 获取路线坐标点（正确方法：getCoordList）
            List<NaviLatLng> naviLatLngList = naviPath.getCoordList();
            if (naviLatLngList == null || naviLatLngList.isEmpty()) {
                Log.w(TAG, "[NAVI] drawNaviRoute: 路线坐标点为空");
                return;
            }

            // 转换为 LatLng 列表
            List<LatLng> points = new ArrayList<>();
            for (NaviLatLng naviLatLng : naviLatLngList) {
                points.add(new LatLng(naviLatLng.getLatitude(), naviLatLng.getLongitude()));
            }

            // 绘制自定义路线（白色光晕 + 绿色主线）
            drawRoute(points);

            Log.d(TAG, "[NAVI] drawNaviRoute: 路线已绘制，点数=" + points.size());
        } catch (Throwable t) {
            Log.e(TAG, "[NAVI] drawNaviRoute failed: " + t.getMessage(), t);
        }
    }

    @Override
    public void onCalculateRouteFailure(AMapCalcRouteResult aMapCalcRouteResult) {

    }

    @Override
    public void onNaviRouteNotify(AMapNaviRouteNotifyData aMapNaviRouteNotifyData) {

    }

    @Override
    public void onGpsSignalWeak(boolean b) {

    }
    @Override
    public void showLaneInfo(com.amap.api.navi.model.AMapLaneInfo[] aMapLaneInfos, byte[] bytes, byte[] bytes1) {
    }

    @Override
    public void showLaneInfo(AMapLaneInfo aMapLaneInfo) {

    }

    @Override
    public void hideLaneInfo() {

    }



    // ========== 业务方法 ==========

    /**
     * 绘制自定义路线（网络模式下使用）
     * GPS模式下使用导航SDK的内置导航功能，不调用此方法
     */
    public void drawRoute(List<LatLng> points) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> drawRoute(points));
            return;
        }

        if (aMap == null || points == null || points.isEmpty()) {
            return;
        }

        // 清理旧路线（GPS模式和网络模式都需要清空）
        if (routeGlowPolyline != null) { routeGlowPolyline.remove(); routeGlowPolyline = null; }
        if (routeMainPolyline != null) { routeMainPolyline.remove(); routeMainPolyline = null; }
        if (routePolyline != null) { routePolyline.remove(); routePolyline = null; }
        clearArrowMarkers();

        this.routePoints = new ArrayList<>(points);

        // 1) 白色光晕（最宽，半透明）
        PolylineOptions glowOpt = new PolylineOptions()
                .addAll(points)
                .width(26f)
                .color(0x66FFFFFF);
        routeGlowPolyline = aMap.addPolyline(glowOpt);

        // 2) 绿色主线（中宽）
        PolylineOptions mainOpt = new PolylineOptions()
                .addAll(points)
                .width(16f)
                .color(0xFF4CAF50);
        routeMainPolyline = aMap.addPolyline(mainOpt);
        routePolyline = routeMainPolyline;

        // 3) 沿线方向箭头 marker
        drawArrowMarkers(points);

        Log.d(TAG, "[ROUTE] 路线已绘制，路径点数量=" + points.size() + ", GPS模式=" + isGpsMode);

        try {
            LatLngBounds.Builder builder = LatLngBounds.builder();
            for (LatLng p : points) {
                builder.include(p);
            }
            LatLngBounds bounds = builder.build();
            if (isGpsMode) {
                // ⭐ GPS 模式：不缩放至整条路线（否则退回俯视网络视角），
                //    改为以路线中心为目标、保持 3D 导航视角（跟随车辆）
                double centerLat = 0, centerLng = 0;
                for (LatLng p : points) {
                    centerLat += p.latitude;
                    centerLng += p.longitude;
                }
                int n = points.size();
                LatLng routeCenter = new LatLng(centerLat / n, centerLng / n);
                float gpsZoom = navigationZoom + GPS_ZOOM_BONUS;
                aMap.moveCamera(CameraUpdateFactory.newCameraPosition(
                        new CameraPosition(routeCenter, gpsZoom, navigationTilt, 0f)));
                Log.d(TAG, "[ROUTE] GPS模式：相机置于路线中心（3D视角），不fit整条路线");
            } else {
                CameraUpdate update = CameraUpdateFactory.newLatLngBounds(bounds, 100);
                aMap.animateCamera(update);

                // ⭐ 延迟记录自适应后的 zoom（animateCamera 是异步的，等动画完成后再读）
                mainHandler.postDelayed(() -> {
                    try {
                        if (aMap != null && !isGpsMode) {
                            lastNetworkAdaptiveZoom = aMap.getCameraPosition().zoom;
                            Log.d(TAG, "[ZOOM] 记录网络模式自适应 zoom=" + lastNetworkAdaptiveZoom);
                        }
                    } catch (Throwable ignore) {}
                }, 500);  // 500ms 后读取（动画时长通常 300-500ms）
            }
        } catch (Exception e) {
            Log.e(TAG, "drawRoute bounds failed: " + e.getMessage());
        }
    }

    /**
     * ⭐ GPS模式：启动导航SDK的导航功能（算路+导航引导）
     * @param startLat 起点纬度
     * @param startLng 起点经度
     * @param endLat 终点纬度
     * @param endLng 终点经度
     */
    public void startGpsNavigation(double startLat, double startLng, double endLat, double endLng) {
        if (!isGpsMode) {
            Log.w(TAG, "[NAVI] startGpsNavigation skipped: not in GPS mode");
            return;
        }
        if (aMapNavi == null) {
            Log.e(TAG, "[NAVI] startGpsNavigation failed: aMapNavi is null");
            return;
        }

        try {
            // 构造起点和终点
            NaviLatLng startPoint = new NaviLatLng(startLat, startLng);
            NaviLatLng endPoint = new NaviLatLng(endLat, endLng);

            // 计算路线（从起点到终点）
            // 参数：起点列表、终点列表、途经点列表、策略（0=推荐策略）
            java.util.List<NaviLatLng> fromList = java.util.Collections.singletonList(startPoint);
            java.util.List<NaviLatLng> toList = java.util.Collections.singletonList(endPoint);
            boolean success = aMapNavi.calculateDriveRoute(fromList, toList, null, 0);

            Log.d(TAG, "[NAVI] startGpsNavigation: 算路请求已发送，起点=(" + startLat + "," + startLng +
                    "), 终点=(" + endLat + "," + endLng + "), success=" + success);
        } catch (Throwable t) {
            Log.e(TAG, "[NAVI] startGpsNavigation failed: " + t.getMessage(), t);
        }
    }

    /**
     * ⭐ GPS模式：停止导航
     */
    public void stopGpsNavigation() {
        if (aMapNavi != null) {
            try {
                aMapNavi.stopNavi();
                Log.d(TAG, "[NAVI] stopGpsNavigation: 导航已停止");
            } catch (Throwable t) {
                Log.e(TAG, "[NAVI] stopGpsNavigation failed: " + t.getMessage());
            }
        }
    }

    // ==================== GPS 位置模拟（沿公交路线插值，用于模拟报站） ====================
    private ScheduledExecutorService simExecutor;
    private volatile boolean simulating = false;
    // ⭐ 全局“模拟进行中”标志（模拟只可能全局同时一个）：用于让所有 AmapNavigationView 实例
    //   （主图实例 + POV 实例）在模拟期间统一忽略自身的真实 GPS 回调，避免抢占模拟位置/干扰车标与 UI
    private static volatile boolean simActiveGlobally = false;
    private int simSegIndex = 0;
    private double simSegFrac = 0.0;
    private float simSpeedKmh = 30f;
    private int simStartDwellSec = 0;     // 起点站停留时长（秒）
    private int simTerminalDwellSec = 0;  // 终点站停留时长（秒）
    private int simArrivalDwellSec = 0;   // 到站停留间隔（秒）：中途站到站停留时长，0 表示不停留
    private int simDwellRemaining = 0;    // 当前停留剩余秒数（>0 表示原地停留、速度 0）
    private boolean simAtTerminal = false;
    private boolean simStartDwellPending = false; // 循环回到起点后是否触发起点停留
    private List<LatLng> simStationPositions = null; // 模拟所用站点坐标（含起终点，用于匹配路线点）
    private int[] simStationRealIndices = null; // 模拟站点对应的【真实站点列表】下标（与 simStationPositions 等长，用于修正坐标过滤导致的下标错位）
    private int[] simStationRouteIdxAll = null; // 每个站点（含起终点）对应的路线点索引（与站点顺序等长），用于判定"到站"
    private int simStationPtr = 0;         // 已到达/下一个中途站指针（station-order 索引，0=起点，size-1=终点）
    private int simActiveDwellType = 0;    // 当前进行中的停留类型：0=无，1=起点，2=终点，3=中途到站（用于实时刷新剩余时长）

    public boolean isSimulating() {
        return simulating;
    }

    /**
     * 开始沿当前公交路线（routePoints）模拟 GPS 位置变化，循环行驶。
     * 位置通过统一的 notifyLocationUpdated 出口注入，与真实 GPS 走相同下游（车标 + 报站）。
     * @param speedKmh 模拟行驶速度（km/h），<=0 时按 30 处理
     * @param startDwellSec 起点站停留时长（秒），0 表示不停留
     * @param terminalDwellSec 终点站停留时长（秒），0 表示不停留
     * @param arrivalDwellSec 到站停留间隔（秒）：每个中途站到站后停留时长，0 表示不停留
     */
    // 模拟位置以 SIM_TICK_MS 为周期推进（与 scheduleAtFixedRate 一致），故"停留秒数"需换算成 tick 数
    private static final int SIM_TICK_MS = 200;
    private static final int SIM_TICKS_PER_SEC = 1000 / SIM_TICK_MS; // 1 秒 = 5 个 tick

    public void startLocationSimulation(float speedKmh, int startDwellSec, int terminalDwellSec, int arrivalDwellSec) {
        if (simulating) stopLocationSimulation();
        if (routePoints == null || routePoints.size() < 2) {
            Log.w(TAG, "[SIM] 未载入公交路线，无法模拟");
            return;
        }
        simulating = true;
        simActiveGlobally = true;   // ⭐ 全局置位：所有实例据此忽略真实 GPS 回调
        simSpeedKmh = speedKmh > 0 ? speedKmh : 30f;
        simStartDwellSec = Math.max(0, startDwellSec);
        simTerminalDwellSec = Math.max(0, terminalDwellSec);
        simArrivalDwellSec = Math.max(0, arrivalDwellSec);
        simDwellRemaining = 0;
        simAtTerminal = false;
        simStartDwellPending = true; // 开局即在起点停留
        simSegIndex = 0;
        simSegFrac = 0.0;
        // 预计算每个站点对应的路线点索引（与站点顺序等长），用于判定"到站"
        simStationRouteIdxAll = computeStationRouteIndices(simStationPositions);
        simStationPtr = 0;
        simExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gps-sim");
            t.setDaemon(true);
            return t;
        });
        // ⭐ 模拟位置生成 5Hz（200ms），让地图车标/相机获得高频平滑数据；
        //    POV 端文本等其它数据在 CameraActivity.gpsListener 中限频到 1Hz，互不影响。
        //    注：路径推进速度由 simSpeedKmh 决定，与步频无关，提高步频仅增加采样密度。
        simExecutor.scheduleAtFixedRate(this::simTick, 0, 200, TimeUnit.MILLISECONDS);
        Log.d(TAG, "[SIM] 开始模拟位置变化 速度=" + simSpeedKmh + "km/h 起点停留=" + simStartDwellSec
                + "s 终点停留=" + simTerminalDwellSec + "s 到站停留=" + simArrivalDwellSec
                + "s (5Hz)");
    }

    /** 兼容旧调用：无停留时长 */
    public void startLocationSimulation(float speedKmh) {
        startLocationSimulation(speedKmh, 0, 0, 0);
    }

    /**
     * 实时更新模拟参数（模拟进行中调用，无需重启）：
     * 车速立即生效；各停留时长用于后续起点站 / 终点站 / 中途站到站停留。
     */
    public void updateSimParams(float speedKmh, int startDwellSec, int terminalDwellSec, int arrivalDwellSec) {
        if (!simulating) return;
        simSpeedKmh = speedKmh > 0 ? speedKmh : 30f;
        simStartDwellSec = Math.max(0, startDwellSec);
        simTerminalDwellSec = Math.max(0, terminalDwellSec);
        simArrivalDwellSec = Math.max(0, arrivalDwellSec);
        // 若当前正处于某次停留中，按新时长立即刷新剩余 tick，使本次停留实时生效（无需等到下一站）
        if (simDwellRemaining > 0) {
            switch (simActiveDwellType) {
                case 1: simDwellRemaining = simStartDwellSec * SIM_TICKS_PER_SEC; break;
                case 2: simDwellRemaining = simTerminalDwellSec * SIM_TICKS_PER_SEC; break;
                case 3: simDwellRemaining = simArrivalDwellSec * SIM_TICKS_PER_SEC; break;
                default: break;
            }
        }
    }

    /** 设置模拟所用站点坐标（含起终点）及其对应的真实站点列表下标，用于把中途站匹配到路线点以触发到站停留，
     *  并保证"到站/途中"标记回传的是真实站点下标（修正坐标过滤导致的下标错位）。 */
    public void setSimulationStations(List<LatLng> stations, int[] realIndices) {
        this.simStationPositions = (stations != null) ? new ArrayList<>(stations) : null;
        this.simStationRealIndices = (realIndices != null) ? realIndices.clone() : null;
    }

    /** 把每个站点（含起终点）匹配到最近的路线点索引，返回与 stations 等长的数组（下标即站点顺序）。 */
    private int[] computeStationRouteIndices(List<LatLng> stations) {
        if (stations == null || stations.isEmpty() || routePoints == null || routePoints.size() < 2) {
            return new int[0];
        }
        int n = routePoints.size();
        int[] arr = new int[stations.size()];
        for (int i = 0; i < stations.size(); i++) {
            LatLng s = stations.get(i);
            int best = 0;
            double bestDist = Double.MAX_VALUE;
            if (s != null) {
                for (int j = 0; j < n; j++) {
                    double d = computeDistanceMeters(s, routePoints.get(j));
                    if (d < bestDist) {
                        bestDist = d;
                        best = j;
                    }
                }
            }
            arr[i] = best;
        }
        return arr;
    }

    /** 停止模拟位置变化 */
    public void stopLocationSimulation() {
        if (!simulating) return;
        simulating = false;
        simActiveGlobally = false;   // ⭐ 全局复位：恢复各实例对真实 GPS 的响应
        if (simExecutor != null) {
            simExecutor.shutdownNow();
            simExecutor = null;
        }
        Log.d(TAG, "[SIM] 停止模拟位置变化");
    }

    private void simTick() {
        mainHandler.post(this::doSimStep);
    }

    private void doSimStep() {
        if (!simulating || routePoints == null || routePoints.size() < 2) return;
        int n = routePoints.size();

        // 1) 停留计时中：原地不动，速度置 0（用于起点站 / 终点站 / 中途站停留）
        if (simDwellRemaining > 0) {
            simDwellRemaining--;
            postSimLocation(0f, currentDwellStationIndex(), true); // 停留中即"在站内"，标记 isArrived
            if (simDwellRemaining == 0) {
                simActiveDwellType = 0;
                if (simAtTerminal) {
                    // 终点站停留结束 -> 回到起点循环，下一帧触发起点停留
                    simSegIndex = 0;
                    simSegFrac = 0.0;
                    simAtTerminal = false;
                    simStartDwellPending = true;
                    simStationPtr = 0; // 重置中途站停留指针
                }
            }
            return;
        }

        // 2) 起点站停留（每个循环首帧、发车前停留）
        if (simStartDwellPending && !simAtTerminal) {
            simStartDwellPending = false;
            simActiveDwellType = 1;
            simDwellRemaining = simStartDwellSec * SIM_TICKS_PER_SEC;
            postSimLocation(0f, 0, true); // 在起点站内
            return;
        }

        // 3) 推进位置：按本 tick 真实时长（SIM_TICK_MS=200ms，5Hz）内、以 simSpeedKmh 真实 km/h
        //    应行驶的距离行进；这样 1 真实秒内累计正好走 1 秒的路程，车速与设定 km/h 一致（不再是 5 倍）。
        double stepMeters = (simSpeedKmh / 3.6) * (SIM_TICK_MS / 1000.0);
        while (stepMeters > 0 && simSegIndex < n - 1) {
            LatLng p1 = routePoints.get(simSegIndex);
            LatLng p2 = routePoints.get(simSegIndex + 1);
            double segLen = computeDistanceMeters(p1, p2);
            if (segLen <= 0) {
                simSegIndex++;
                simSegFrac = 0;
                continue;
            }
            double remain = segLen * (1 - simSegFrac);
            if (stepMeters >= remain) {
                stepMeters -= remain;
                simSegIndex++;
                simSegFrac = 0;
            } else {
                simSegFrac += stepMeters / segLen;
                stepMeters = 0;
            }
        }

        // 3.5) 中途站到站停留：推进后若越过下一个中途站对应的路线点，原地停留并标记"到站"
        if (simArrivalDwellSec > 0 && simStationRouteIdxAll != null
                && simStationRouteIdxAll.length >= 3) {
            int nextStation = simStationPtr + 1; // station-order 指针（0=起点，size-1=终点）
            // 仅对"中途站"触发停留：跳过起点(0)与终点(size-1)
            if (nextStation > 0 && nextStation < simStationRouteIdxAll.length - 1
                    && simSegIndex >= simStationRouteIdxAll[nextStation]) {
                simActiveDwellType = 3;
                simDwellRemaining = simArrivalDwellSec * SIM_TICKS_PER_SEC;
                simStationPtr = nextStation; // 标记已到达该中途站
                postSimLocation(0f, nextStation, true);
                return;
            }
        }

        // 4) 到达终点站：停留后由步骤 1 循环回起点
        if (simSegIndex >= n - 1) {
            simAtTerminal = true;
            simActiveDwellType = 2;
            simDwellRemaining = simTerminalDwellSec * SIM_TICKS_PER_SEC;
            postSimLocation(simSpeedKmh / 3.6f, lastStationIndex(), true); // 在终点站内
            return;
        }

        // 5) 正常行驶：不在任何站内，标记 isArrived=false
        postSimLocation(simSpeedKmh / 3.6f, -1, false);
    }

    /** 当前正处于停留的站点 order 索引（用于标记"到站"）。 */
    private int currentDwellStationIndex() {
        int last = (simStationPositions != null) ? simStationPositions.size() - 1 : 0;
        if (simAtTerminal) return last;          // 终点站停留
        if (simStationPtr <= 0) return 0;        // 起点站停留（尚未越过任何中途站）
        return simStationPtr;                    // 中途站停留（simStationPtr 已被置为该站 order）
    }

    /** 终点站 order 索引。 */
    private int lastStationIndex() {
        return (simStationPositions != null) ? simStationPositions.size() - 1 : 0;
    }

    /** 按当前 simSegIndex / simSegFrac 合成位置并以给定速度注入（与真实 GPS 同下游）。
     *  @param stationIndex 当前所在站点 order 索引（-1 表示不在任何站）
     *  @param atStation   是否正处于站内（isArrived）：true=在 stationIndex 站，false=行驶中 */
    private void postSimLocation(float speed, int stationIndex, boolean atStation) {
        int n = routePoints.size();
        LatLng p1, p2;
        double lat, lng;
        if (atStation && stationIndex >= 0 && simStationPositions != null
                && stationIndex < simStationPositions.size()) {
            // ⭐ 到站：直接定位到该站坐标，确保下游"到站"判定与站点列表显示精准，
            //    不再依赖路线点相对站点坐标的偏移（这是此前"模拟位置传入错误"的根因）。
            LatLng sp = simStationPositions.get(stationIndex);
            lat = sp.latitude;
            lng = sp.longitude;
            p1 = routePoints.get(Math.min(simSegIndex, n - 1));
            p2 = p1;
        } else {
            p1 = routePoints.get(simSegIndex);
            p2 = routePoints.get(Math.min(simSegIndex + 1, n - 1));
            lat = p1.latitude + (p2.latitude - p1.latitude) * simSegFrac;
            lng = p1.longitude + (p2.longitude - p1.longitude) * simSegFrac;
        }
        float bearing = (p1 != p2) ? computeBearing(p1, p2) : 0f;
        Location loc = new Location("gps_sim");
        loc.setLatitude(lat);
        loc.setLongitude(lng);
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(System.nanoTime());
        loc.setSpeed(speed);
        loc.setBearing(bearing);
        loc.setAccuracy(5f);
        // ⭐ 显式携带"到站标记"，供下游 handleGpsLocation 直接判定 isArrived 并精确定位站点列表车标，
        //    避免路线点偏移 / 坐标过滤导致半径匹配与下标错位。
        //    注：回传的是【真实站点列表】下标：到站时为该站；途中时为"正在驶离"的上一站（simStationPtr），
        //    使列表车标在"途中"仍吸附在离站一侧（与报站口径一致），而不是吸附到几何最近的下一站。
        Bundle simExtra = loc.getExtras();
        if (simExtra == null) {
            simExtra = new Bundle();
            loc.setExtras(simExtra);
        }
        int simListIndex;
        if (atStation && stationIndex >= 0 && simStationRealIndices != null
                && stationIndex < simStationRealIndices.length) {
            simListIndex = simStationRealIndices[stationIndex];
        } else {
            // 途中：使用已驶离的站点（simStationPtr 为模拟顺序下标）对应的真实站点下标
            int ptr = simStationPtr;
            simListIndex = (simStationRealIndices != null && ptr >= 0 && ptr < simStationRealIndices.length)
                    ? simStationRealIndices[ptr] : stationIndex;
        }
        simExtra.putInt("sim_station_index", simListIndex);
        simExtra.putBoolean("sim_arrived", atStation);
        // 关键：报站引擎由 GpsWarmingUp 驱动（gpsActivityListener -> handleGpsLocation），
        // 而非 AMapNavi 的 notifyLocationUpdated（后者在线路详情页并未挂监听，只会动车标）。
        // 因此把合成位置注入 GpsWarmingUp，走与真实 GPS 完全一致的下游（报站判断 + 地图定位）。
        GpsWarmingUp.postToGpsThread(() -> GpsWarmingUp.updateLocation(loc));
        // 同时驱动车标（地图视觉）
        if (isGpsMode && carOverlay != null && aMap != null) {
            carOverlay.draw(aMap, new LatLng(lat, lng), bearing);
        }
    }

    /**
     * 生成路线方向箭头 marker icon
     */
    private BitmapDescriptor createArrowMarkerIcon() {
        try {
            int w = 32;
            int h = 32;
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFFFFFFFF);
            paint.setStrokeWidth(5f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            Path v = new Path();
            v.moveTo(w * 0.22f, h * 0.30f);
            v.lineTo(w * 0.50f, h * 0.08f);
            v.lineTo(w * 0.78f, h * 0.30f);
            canvas.drawPath(v, paint);

            return BitmapDescriptorFactory.fromBitmap(bitmap);
        } catch (Throwable t) {
            Log.e(TAG, "createArrowMarkerIcon failed: " + t.getMessage(), t);
            return null;
        }
    }

    /**
     * 沿路径每 N 米放一个方向箭头 marker
     */
    private void drawArrowMarkers(List<LatLng> points) {
        if (aMap == null) return;
        if (points == null || points.size() < 2) return;
        if (arrowMarkerIcon == null) return;

        try {
            CameraPosition cp = aMap.getCameraPosition();
            if (cp != null && cp.zoom >= 3f) {
                currentZoom = cp.zoom;
            } else {
                if (currentZoom < 3f) currentZoom = 14f;
            }
        } catch (Throwable t) {
            if (currentZoom < 3f) currentZoom = 14f;
        }

        final double[] segLens = new double[points.size() - 1];
        double totalLen = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            segLens[i] = AMapUtils.calculateLineDistance(points.get(i), points.get(i + 1));
            totalLen += segLens[i];
        }
        if (totalLen <= 1) return;

        double spacing;
        if (currentZoom < 12f) spacing = 1500.0;
        else if (currentZoom < 13f) spacing = 500.0;
        else if (currentZoom < 14f) spacing = 200.0;
        else if (currentZoom < 15f) spacing = 100.0;
        else if (currentZoom < 16f) spacing = 60.0;
        else spacing = 40.0;

        clearArrowMarkers();

        double traveled = spacing * 0.5;
        int segIdx = 0;
        double acc = 0;
        while (traveled < totalLen) {
            while (segIdx < segLens.length - 1 && acc + segLens[segIdx] < traveled) {
                acc += segLens[segIdx];
                segIdx++;
            }
            if (segIdx >= segLens.length) break;

            double segStart = acc;
            double t = (traveled - segStart) / segLens[segIdx];
            if (t < 0) t = 0;
            if (t > 1) t = 1;

            double lat = points.get(segIdx).latitude
                    + (points.get(segIdx + 1).latitude - points.get(segIdx).latitude) * t;
            double lng = points.get(segIdx).longitude
                    + (points.get(segIdx + 1).longitude - points.get(segIdx).longitude) * t;

            float angle = (float) computeAzimuth(points.get(segIdx), points.get(segIdx + 1));
            float rotateAngle = (360f - angle) % 360f;

            Marker marker = aMap.addMarker(new MarkerOptions()
                    .position(new LatLng(lat, lng))
                    .icon(arrowMarkerIcon)
                    .anchor(0.5f, 0.5f)
                    .setFlat(true)
                    .zIndex(0.5f));
            if (marker != null) {
                marker.setRotateAngle(rotateAngle);
            }
            arrowMarkers.add(marker);

            traveled += spacing;
        }
    }

    private void clearArrowMarkers() {
        for (Marker m : arrowMarkers) {
            try { m.remove(); } catch (Throwable ignore) {}
        }
        arrowMarkers.clear();
    }

    /**
     * 在地图上叠加站点位置用于对比：
     * officialPoints 画红色官方点；amapPoints 非 null/非空时叠加蓝色高德覆盖点；
     * 同一站点两者平面偏移 > 2m 时画一条橙色连线高亮差异。每次调用先清空旧的。
     *
     * @param names 与 officialPoints 同序的站名（用于 marker title），可空。
     */
    public void drawStationMarkers(List<LatLng> officialPoints, List<String> names, List<LatLng> amapPoints) {
        clearStationMarkers();
        if (aMap == null || officialPoints == null) return;
        boolean hasAmap = amapPoints != null && !amapPoints.isEmpty();
        int n = officialPoints.size();
        for (int i = 0; i < n; i++) {
            LatLng o = officialPoints.get(i);
            if (o == null) continue;
            String name = (names != null && i < names.size()) ? names.get(i) : null;
            Marker om = aMap.addMarker(new MarkerOptions()
                    .position(o)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .title(name != null ? name + "（官方）" : "官方")
                    .anchor(0.5f, 0.5f)
                    .zIndex(2f));
            stationMarkers.add(om);
            if (hasAmap && i < amapPoints.size()) {
                LatLng g = amapPoints.get(i);
                if (g != null) {
                    Marker gm = aMap.addMarker(new MarkerOptions()
                            .position(g)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                            .title(name != null ? name + "（高德）" : "高德")
                            .anchor(0.5f, 0.5f)
                            .zIndex(2f));
                    stationMarkers.add(gm);
                    float dist = AMapUtils.calculateLineDistance(o, g);
                    if (dist > 2f) {
                        Polyline pl = aMap.addPolyline(new PolylineOptions()
                                .add(o, g).color(0xFFFFA500).width(3f).zIndex(1f));
                        stationOffsetLines.add(pl);
                    }
                }
            }
        }
    }

    /** 清除所有站点对比 marker 与偏移连线 */
    public void clearStationMarkers() {
        for (Marker m : stationMarkers) {
            try { m.remove(); } catch (Throwable ignore) {}
        }
        stationMarkers.clear();
        for (Polyline p : stationOffsetLines) {
            try { p.remove(); } catch (Throwable ignore) {}
        }
        stationOffsetLines.clear();
    }

    private double computeAzimuth(LatLng a, LatLng b) {
        double lat1 = Math.toRadians(a.latitude);
        double lat2 = Math.toRadians(b.latitude);
        double dLon = Math.toRadians(b.longitude - a.longitude);
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        double brng = Math.toDegrees(Math.atan2(y, x));
        return (brng + 360.0) % 360.0;
    }

    /**
     * 外部位置数据推送（备用：网络模式或自定义定位）
     */
    public void updateMyLocation(double gcjLat, double gcjLon, float bearing) {
        if (aMap == null || locationListener == null) return;
        Location location = new Location("ExternalGPS");
        location.setLatitude(gcjLat);
        location.setLongitude(gcjLon);
        location.setBearing(bearing);
        location.setAccuracy(5.0f);
        location.setTime(System.currentTimeMillis());

        if (Looper.myLooper() == Looper.getMainLooper()) {
            locationListener.onLocationChanged(location);
        } else {
            mainHandler.post(() -> {
                if (locationListener != null) {
                    locationListener.onLocationChanged(location);
                }
            });
        }
    }

    /**
     * 设置/取消罗盘模式
     * ⭐ 注意：GPS模式下不使用 MyLocationStyle，使用 CarOverlay 绘制车标
     */
    public void setCompassMode(boolean enabled) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> setCompassMode(enabled));
            return;
        }

        this.isCompassMode = enabled;
        if (uiSettings != null) {
            uiSettings.setCompassEnabled(enabled);
        }
    }

    public boolean isCompassMode() {
        return isCompassMode;
    }

    /**
     * 设置当前是 GPS 模式还是网络模式
     * <ul>
     *   <li>GPS 模式：开启罗盘模式，启动 AMap 定位，使用导航SDK的导航功能</li>
     *   <li>网络模式：关闭罗盘模式，地图保持自由视角，停止 AMap 定位，使用自定义路线</li>
     * </ul>
     */
    public void setGpsMode(boolean gps) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> setGpsMode(gps));
            return;
        }

        this.isGpsMode = gps;
        Log.d(TAG, "[NAV] setGpsMode(" + gps + ")");

        if (aMap == null) return;

        try {
            if (gps) {
                // GPS 模式：清掉所有公交车辆 marker，使用导航SDK的导航功能
                clearBusMarkers();
                setCompassMode(true);
                perspectiveAppliedOnFirstFix = false;
                gpsNavigationStarted = false;  // 重置导航启动标记，等待定位后启动
                isCarLocked = true;            // ⭐ 初始状态：锁车态
                carLockHandler.removeCallbacksAndMessages(null);  // 清除所有锁车任务
                // ⭐ 不再调用 startAmapLocation() —— locationClient 已在 initAmapLocation 时常驻启动
                // 模式切换时只切换 isGpsMode 标志位，onLocationChanged 内部据此过滤

                // ⭐ 设置 3D 导航视角参数（tilt>0 才有透视效果，否则等同网络俯视）
                navigationZoom = GPS_NAV_ZOOM;
                navigationTilt = GPS_NAV_TILT;

                // ⭐ 立即应用导航视角（zoom/tilt/锁车态）—— 不等首次定位，让用户立刻看到 3D 锁车视角
                // 首次定位到达后，onLocationChanged 仍然会启动导航SDK的算路+导航
                applyNavigationCameraPerspective();
                perspectiveAppliedOnFirstFix = true;

                // ⭐ GPS模式：如果有目标站点，立即启动导航SDK的算路+导航
                if (targetStationPos != null && aMapNavi != null) {
                    // 等待首次定位成功后再启动导航（在 onLocationChanged 中触发）
                    Log.d(TAG, "[NAV] GPS模式已启用，等待定位数据后启动导航到目标站点: " + targetStationPos);
                }
            } else {
                // ⭐ 网络模式：关闭罗盘模式，停止导航，清除车标，使用自定义路线
                // ⭐ 清理速度窗口，切换模式时避免残留旧数据
                speedWindows.clear();
                aMap.setMyLocationEnabled(false);  // 禁用内置定位图层
                setCompassMode(false);
                stopGpsNavigation();  // 停止导航SDK的导航
                // ⭐ 不再调用 stopAmapLocation() —— locationClient 持续运行以保持热启动
                gpsNavigationStarted = false;  // 重置导航启动标记

                // ⭐ 清除车标（CarOverlay）
                if (carOverlay != null) {
                    carOverlay.reset();  // 清除车标 Marker
                }

                // ⭐ 恢复到网络模式：自适应 zoom + 居中到路线中心（不沿用 GPS 模式的贴地 zoom）
                //    如果还没记录 zoom（首次进入），用当前 zoom
                float restoreZoom = (lastNetworkAdaptiveZoom > 0)
                        ? lastNetworkAdaptiveZoom
                        : aMap.getCameraPosition().zoom;
                try {
                    if (routePoints != null && !routePoints.isEmpty()) {
                        // ⭐ 计算路线中心点（用所有点的经纬度平均值）
                        double centerLat = 0, centerLng = 0;
                        for (LatLng p : routePoints) {
                            centerLat += p.latitude;
                            centerLng += p.longitude;
                        }
                        int n = routePoints.size();
                        LatLng routeCenter = new LatLng(centerLat / n, centerLng / n);

                        // ⭐ 手动设置：center=路线中心，zoom=记录的自适应值，tilt=0，bearing=0
                        aMap.moveCamera(CameraUpdateFactory.newCameraPosition(
                                new CameraPosition(
                                        routeCenter,       // 路线中心
                                        restoreZoom,       // ⭐ 记录的自适应 zoom
                                        0f,                // 完全俯视
                                        0f                 // 正北方向
                                )
                        ));
                        Log.d(TAG, "[ZOOM] 切回网络模式：center=" + routeCenter + ", zoom=" + restoreZoom);
                    } else {
                        // 没有路线数据，只重置 tilt/bearing
                        aMap.moveCamera(CameraUpdateFactory.newCameraPosition(
                                new CameraPosition(
                                        aMap.getCameraPosition().target,
                                        restoreZoom,
                                        0f, 0f
                                )
                        ));
                        Log.d(TAG, "[ZOOM] 切回网络模式：无路线数据，使用 zoom=" + restoreZoom);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "setGpsMode(false) reset camera failed: " + t.getMessage(), t);
                }

                lastGpsSuccessTimeMs = 0L;
            }
        } catch (Throwable t) {
            Log.e(TAG, "setGpsMode failed: " + t.getMessage(), t);
        }
    }

    public boolean isGpsMode() {
        return isGpsMode;
    }

    public void setTargetStation(double lat, double lng, int stationIndex) {
        this.targetStationPos = new LatLng(lat, lng);
        this.targetStationIndex = stationIndex;
        // ⭐ 设置新目标站点时清理旧的速度窗口，避免残留旧车辆的窗口数据
        speedWindows.clear();
        Log.d(TAG, "[NAV] setTargetStation: lat=" + lat + ", lng=" + lng + ", index=" + stationIndex);
    }

    /**
     * ⭐ 设置公交线路的起点站和终点站（GPS导航使用）
     * @param startLat 起点站纬度
     * @param startLng 起点站经度
     * @param endLat 终点站纬度
     * @param endLng 终点站经度
     */
    public void setBusLineStartAndEnd(double startLat, double startLng, double endLat, double endLng) {
        // 起点：公交线路起点站（用于GPS导航的起点，用户当前位置会实时更新）
        // 终点：公交线路终点站（用于GPS导航的终点）
        this.targetStationPos = new LatLng(endLat, endLng);
        this.targetStationIndex = -1;  // 终点站索引
        Log.d(TAG, "[NAV] setBusLineStartAndEnd: 起点(" + startLat + "," + startLng + "), 终点(" + endLat + "," + endLng + ")");
    }

    public void clearTargetStation() {
        this.targetStationPos = null;
        this.targetStationIndex = -1;
        // ⭐ 清理速度窗口，避免下次跟踪时残留旧车辆的窗口数据
        speedWindows.clear();
    }

    /**
     * ⭐ 地图跟随：若 isFollowBusMode 开启，且当前 SMM 对应的就是被跟随的车，
     * 用 aMap.moveCamera(newLatLng(...)) 跟随其动画位置（保持当前 zoom/tilt/bearing，不自动缩放）
     */
    private void maybeFollowBus(SmoothMoveMarker smm, String plate) {
        if (!isFollowBusMode || smm == null || aMap == null) return;
        if (followedVehiclePlate == null || !followedVehiclePlate.equals(plate)) return;
        try {
            LatLng pos = smm.getPosition();
            if (pos != null) {
                aMap.moveCamera(CameraUpdateFactory.newLatLng(pos));
            }
        } catch (Throwable t) {
            Log.w(TAG, "[NAV] follow camera move failed: " + t.getMessage());
        }
    }

    /**
     * 设置地图跟随的车辆。设置后 updateBusMarkers 中会对该车的 SmoothMoveMarker
     * 调 aMap.moveCamera(newLatLng(...))，保持 zoom/tilt/bearing 不变。
     * 若该车的 marker 已在 busMarkers 中，会立即把地图移过去。
     */
    public void setFollowedVehicle(String plate) {
        if (plate == null || plate.isEmpty()) {
            clearFollowedVehicle();
            return;
        }
        boolean changed = !plate.equals(this.followedVehiclePlate);
        this.followedVehiclePlate = plate;
        this.isFollowBusMode = true;
        Log.d(TAG, "[NAV] setFollowedVehicle: " + plate + " (changed=" + changed + ")");

        // 立即把地图移过去，避免等下一次刷新
        if (aMap != null && busMarkers.containsKey(plate)) {
            SmoothMoveMarker smm = busMarkers.get(plate);
            try {
                LatLng pos = smm.getPosition();
                if (pos != null) {
                    aMap.moveCamera(CameraUpdateFactory.newLatLng(pos));
                }
            } catch (Throwable t) {
                Log.w(TAG, "[NAV] follow camera move failed: " + t.getMessage());
            }
        }
    }

    public void clearFollowedVehicle() {
        if (this.followedVehiclePlate != null || this.isFollowBusMode) {
            Log.d(TAG, "[NAV] clearFollowedVehicle");
        }
        this.followedVehiclePlate = null;
        this.isFollowBusMode = false;
    }

    public boolean isFollowBusMode() {
        return isFollowBusMode;
    }

    public long getLastGpsSuccessTimeMs() {
        return lastGpsSuccessTimeMs;
    }

    private static float computeBearing(double lat1, double lng1, double lat2, double lng2) {
        double dLat = lat2 - lat1;
        double dLng = lng2 - lng1;
        if (Math.abs(dLat) < 1e-7 && Math.abs(dLng) < 1e-7) {
            return -1f;
        }
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLambda = Math.toRadians(dLng);

        double y = Math.sin(dLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2)
                - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (float) ((bearing + 360.0) % 360.0);
    }

    /**
     * 移动地图相机到指定位置
     */
    public void moveCamera(double gcjLat, double gcjLon, float zoom) {
        if (aMap == null) return;
        aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(gcjLat, gcjLon), zoom));
    }

    // ========== 公交车辆 marker（仅网络模式生效） ==========

    private static final int BUS_MARKER_SIZE_DP = 24;
    private static final float HEAD_BEARING_OFFSET = 90f;
    private static final double BUS_VISUAL_SPEED_MPS = 8.0;
    private static final int MIN_ANIM_DURATION_SEC = 1;
    private static final int MAX_ANIM_DURATION_SEC = 30;
    private static final double MIN_MOVE_DISTANCE_M = 1.0;
    private static final double TELEPORT_THRESHOLD_M = 200.0;

    private void loadBusIcon() {
        if (busIconLoaded) return;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inDensity = 0;
            opts.inTargetDensity = 1;
            opts.inScreenDensity = 1;
            Bitmap raw = BitmapFactory.decodeResource(
                    appContext.getResources(), R.drawable.icon_map_bus, opts);
            if (raw == null) {
                Log.e(TAG, "[BUS] loadBusIcon: decodeResource returned null");
                return;
            }

            float density = appContext.getResources().getDisplayMetrics().density;
            int targetMaxPx = Math.round(BUS_MARKER_SIZE_DP * density);
            Bitmap scaled = scaleBitmapKeepAspect(raw, targetMaxPx);
            if (scaled != raw) raw.recycle();
            busIconDescriptor = BitmapDescriptorFactory.fromBitmap(scaled);
            busIconLoaded = true;
        } catch (Throwable t) {
            Log.e(TAG, "[BUS] loadBusIcon failed: " + t.getMessage(), t);
        }
    }

    private static Bitmap scaleBitmapKeepAspect(Bitmap raw, int maxPx) {
        int origW = raw.getWidth();
        int origH = raw.getHeight();
        if (origW <= 0 || origH <= 0) return raw;
        if (origW == maxPx && origH == maxPx) return raw;
        float scale = (float) maxPx / Math.max(origW, origH);
        int scaledW = Math.max(1, Math.round(origW * scale));
        int scaledH = Math.max(1, Math.round(origH * scale));
        return Bitmap.createScaledBitmap(raw, scaledW, scaledH, true);
    }

    /**
     * 在地图上绘制当前方向的所有公交车辆实时位置（仅网络模式）
     */
    public void updateBusMarkers(List<BusApiClient.BusPosition> positions) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> updateBusMarkers(positions));
            return;
        }

        if (aMap == null) return;

        // ⭐ GPS 模式不绘制公交车辆 marker（用户自己就是其中一辆）
        if (isGpsMode) {
            clearBusMarkers();
            return;
        }

        loadBusIcon();
        if (busIconDescriptor == null) return;

        if (positions == null || positions.isEmpty()) {
            clearBusMarkers();
            return;
        }

        long nowMs = System.currentTimeMillis();

        Set<String> currentPlates = new HashSet<>();
        for (BusApiClient.BusPosition pos : positions) {
            if (pos == null) continue;
            String plate = pos.plateNumber;
            if (plate == null || plate.isEmpty()) continue;
            if (pos.lat == 0.0 && pos.lng == 0.0) continue;
            if (pos.lat < 3.0 || pos.lat > 54.0 || pos.lng < 73.0 || pos.lng > 135.0) continue;

            currentPlates.add(plate);

            LatLng rawPos = new LatLng(pos.lat, pos.lng);
            Object[] snap = snapToRouteAndSegmentBearing(rawPos);
            LatLng snappedPos = (LatLng) snap[0];
            float segBearingDeg = (Float) snap[1];

            LatLng prevPos = lastBusPos.get(plate);
            float finalBearing = segBearingDeg;
            if (prevPos != null) {
                double distMeters = computeDistanceMeters(prevPos, snappedPos);
                if (distMeters >= 1.0) {
                    float moveBearingDeg = computeBearing(prevPos, snappedPos);
                    float diff = moveBearingDeg - segBearingDeg;
                    while (diff < -180f) diff += 360f;
                    while (diff > 180f) diff -= 360f;
                    if (Math.abs(diff) > 90f) {
                        finalBearing = segBearingDeg + 180f;
                    }
                }
            }
            float rotateDeg = normalizeAngle(finalBearing - HEAD_BEARING_OFFSET);

            SmoothMoveMarker smm = busMarkers.get(plate);
            if (smm == null) {
                try {
                    smm = new SmoothMoveMarker(aMap);
                    smm.setDescriptor(busIconDescriptor);
                    smm.setPosition(snappedPos);
                    setSmmTitle(smm, plate, pos.isArrived);
                    Marker busMarker = smm.getMarker();
                    if (busMarker != null) {
                        busMarker.setZIndex(1f);
                    }
                    busMarkers.put(plate, smm);
                    applyRotateToSmm(smm, rotateDeg);
                    // ⭐ 新 SMM 也挂上 MoveListener（虽然暂无动画，但下一帧数据来时就有动画了）
                    attachRotationListener(smm, plate);
                } catch (Throwable t) {
                    Log.e(TAG, "[BUS] create SmoothMoveMarker failed for " + plate + ": " + t.getMessage());
                }
                if (smm != null) {
                    lastBusPos.put(plate, snappedPos);
                    lastBusUpdateMs.put(plate, nowMs);
                    // ⭐ 新 SMM 创建后立即检查是否需要跟随
                    maybeFollowBus(smm, plate);
                }
                continue;
            }

            attachRotationListener(smm, plate);

            LatLng animatedPos = null;
            try {
                animatedPos = smm.getPosition();
            } catch (Throwable t) { }
            if (animatedPos == null) animatedPos = prevPos;

            double distMeters = computeDistanceMeters(animatedPos, snappedPos);
            if (distMeters < MIN_MOVE_DISTANCE_M) {
                try { smm.setPosition(snappedPos); } catch (Throwable ignore) {}
            } else if (distMeters > TELEPORT_THRESHOLD_M) {
                int durationSec;
                if (distMeters <= 500) durationSec = 3;
                else if (distMeters <= 1000) durationSec = 4;
                else if (distMeters <= 2000) durationSec = 5;
                else durationSec = 6;

                List<LatLng> traj = new ArrayList<>(2);
                traj.add(animatedPos);
                traj.add(snappedPos);

                try {
                    smm.setPoints(traj);
                    smm.setTotalDuration(durationSec);
                    smm.startSmoothMove();
                } catch (Throwable t) {
                    Log.w(TAG, "[BUS] startSmoothMove failed for " + plate + ": " + t.getMessage());
                }
            } else {
                int durationSec = (int) Math.round(distMeters / BUS_VISUAL_SPEED_MPS);
                if (durationSec < MIN_ANIM_DURATION_SEC) durationSec = MIN_ANIM_DURATION_SEC;
                if (durationSec > MAX_ANIM_DURATION_SEC) durationSec = MAX_ANIM_DURATION_SEC;

                List<LatLng> traj = new ArrayList<>(2);
                traj.add(animatedPos);
                traj.add(snappedPos);

                try {
                    smm.setPoints(traj);
                    smm.setTotalDuration(durationSec);
                    smm.startSmoothMove();
                } catch (Throwable t) {
                    Log.w(TAG, "[BUS] startSmoothMove failed for " + plate + ": " + t.getMessage());
                }
            }
            setSmmTitle(smm, plate, pos.isArrived);
            lastBusPos.put(plate, snappedPos);
            lastBusUpdateMs.put(plate, nowMs);
            // ⭐ 已存在 SMM 更新位置/动画后，若是被跟随车，地图 moveCamera 到动画位置
            maybeFollowBus(smm, plate);
        }

        Iterator<Map.Entry<String, SmoothMoveMarker>> it = busMarkers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SmoothMoveMarker> e = it.next();
            if (!currentPlates.contains(e.getKey())) {
                try {
                    e.getValue().destroy();
                } catch (Throwable t) { }
                it.remove();
                lastBusPos.remove(e.getKey());
                lastBusUpdateMs.remove(e.getKey());
            }
        }

        if (!isGpsMode && speedChangeListener != null && !busMarkers.isEmpty()) {
            int nearestVehicleStationIndex = -1;
            String nearestVehiclePlate = null;

            for (BusApiClient.BusPosition pos : positions) {
                if (pos == null || pos.plateNumber == null) continue;
                String plate = pos.plateNumber;
                int vehicleStationIndex = pos.currentStationOrder - 1;
                LatLng currentPos = new LatLng(pos.lat, pos.lng);

                if (targetStationIndex >= 0 && vehicleStationIndex < targetStationIndex) {
                    if (vehicleStationIndex > nearestVehicleStationIndex) {
                        nearestVehicleStationIndex = vehicleStationIndex;
                        nearestVehiclePlate = plate;

                        // ⭐ 滑动窗口速度估算算法
                        LatLng lastPos = lastMovingPos.get(plate);
                        Long lastTime = lastMovingTimeMs.get(plate);
                        Long stationaryEnd = lastStationaryEndMs.get(plate);

                        if (lastPos == null) {
                            // 首次记录：初始化位置和时间
                            lastMovingPos.put(plate, currentPos);
                            lastMovingTimeMs.put(plate, nowMs);
                            lastStationaryEndMs.put(plate, nowMs);
                            // 初始化窗口
                            speedWindows.put(plate, new ArrayList<>());
                            continue;
                        }

                        double distM = computeDistanceMeters(lastPos, currentPos);

                        // 获取或初始化该车辆的窗口
                        List<SpeedSample> window = speedWindows.get(plate);
                        if (window == null) {
                            window = new ArrayList<>();
                            speedWindows.put(plate, window);
                        }

                        if (distM > STATIONARY_THRESHOLD_M) {
                            // ⭐ 检测到移动：计算有效移动时间（去掉静止时间）
                            long movingTimeMs = nowMs - (stationaryEnd != null ? stationaryEnd : lastTime);
                            if (movingTimeMs > 0 && movingTimeMs < 120000) {
                                // 添加到窗口
                                window.add(new SpeedSample(distM, movingTimeMs, nowMs));
                            }

                            // 更新移动位置和时间
                            lastMovingPos.put(plate, currentPos);
                            lastMovingTimeMs.put(plate, nowMs);
                            lastStationaryEndMs.put(plate, nowMs);
                        } else {
                            // ⭐ 静止时不添加新样本，只记录静止结束时间
                            // 窗口中保留之前的移动记录，速度会基于历史数据自然衰减
                            lastStationaryEndMs.put(plate, nowMs);
                        }

                        // ⭐ 清理窗口中超过 60 秒的旧样本
                        long cutoffTime = nowMs - SPEED_WINDOW_MS;
                        window.removeIf(sample -> sample.timestampMs < cutoffTime);
                    }
                }
            }

            // ⭐ 计算最近车辆的窗口速度
            if (targetStationIndex >= 0 && nearestVehiclePlate != null) {
                List<SpeedSample> window = speedWindows.get(nearestVehiclePlate);
                float windowSpeed = calculateWindowSpeed(window);
                speedChangeListener.onSpeedChanged(windowSpeed);
            } else {
                speedChangeListener.onSpeedChanged(-1f);
            }
        }
    }

    /**
     * 计算滑动窗口内的累计速度
     * @param window 速度样本窗口
     * @return 速度（km/h），窗口为空或有效时间不足时返回 0
     */
    private float calculateWindowSpeed(List<SpeedSample> window) {
        if (window == null || window.isEmpty()) {
            return 0f;
        }

        // 累计窗口内所有样本的距离和有效移动时间
        double totalDistanceM = 0;
        long totalMovingTimeMs = 0;

        for (SpeedSample sample : window) {
            totalDistanceM += sample.distanceM;
            totalMovingTimeMs += sample.movingTimeMs;
        }

        // 累计有效时间不足时返回 0（避免瞬时速度误差）
        if (totalMovingTimeMs < MIN_MOVING_TIME_MS) {
            return 0f;
        }

        // 速度 = 累计距离 / 累计有效时间（去掉静止时间）
        // 例如：100米走了10秒，其中静止2秒 → 速度 = 100/8 = 12.5 m/s
        double speedMps = totalDistanceM / (totalMovingTimeMs / 1000.0);
        return (float) (speedMps * 3.6);  // m/s → km/h
    }

    private void setSmmTitle(SmoothMoveMarker smm, String plate, boolean isArrived) {
        try {
            Marker m = smm.getMarker();
            if (m != null) {
                m.setTitle(isArrived ? "公交 " + plate + "（已到站）" : "公交 " + plate);
            }
        } catch (Throwable ignore) {}
    }

    private void applyRotateToSmm(SmoothMoveMarker smm, float rotateDeg) {
        try {
            Marker m = smm.getMarker();
            if (m != null) m.setRotateAngle(rotateDeg);
        } catch (Throwable ignore) {}
    }

    private void attachRotationListener(final SmoothMoveMarker smm, final String plate) {
        try {
            smm.setMoveListener(new SmoothMoveMarker.MoveListener() {
                @Override
                public void move(final double distance) {
                    if (aMap == null) return;
                    try {
                        LatLng cur = smm.getPosition();
                        if (cur == null) return;
                        Object[] snap = snapToRouteAndSegmentBearing(cur);
                        LatLng snappedCur = (LatLng) snap[0];
                        float segBearingDeg = (Float) snap[1];
                        LatLng prevPos = lastBusPos.get(plate);
                        float finalBearing = segBearingDeg;
                        if (prevPos != null) {
                            float moveBearingDeg = computeBearing(prevPos, snappedCur);
                            float diff = moveBearingDeg - segBearingDeg;
                            while (diff < -180f) diff += 360f;
                            while (diff > 180f) diff -= 360f;
                            if (Math.abs(diff) > 90f) finalBearing = segBearingDeg + 180f;
                        }
                        float rotateDeg = normalizeAngle(finalBearing - HEAD_BEARING_OFFSET);
                        Marker m = smm.getMarker();
                        if (m != null) m.setRotateAngle(rotateDeg);

                        // ⭐ 地图跟随：与 rotation 完全同步，在 SMM 动画的每一帧都 moveCamera，
                        //    解决"车在动，地图在后面追"的滞后感
                        if (isFollowBusMode && followedVehiclePlate != null
                                && followedVehiclePlate.equals(plate) && aMap != null) {
                            try {
                                aMap.moveCamera(CameraUpdateFactory.newLatLng(snappedCur));
                            } catch (Throwable ignore2) {}
                        }
                    } catch (Throwable ignore) {}
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "[BUS] setMoveListener failed for " + plate);
        }
    }

    public void clearBusMarkers() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::clearBusMarkers);
            return;
        }

        for (SmoothMoveMarker smm : busMarkers.values()) {
            if (smm != null) {
                try { smm.destroy(); } catch (Throwable t) { }
            }
        }
        busMarkers.clear();
        lastBusPos.clear();
        lastBusUpdateMs.clear();
        lastMovingPos.clear();
        lastMovingTimeMs.clear();
        lastStationaryEndMs.clear();
        speedWindows.clear();  // ⭐ 清理速度窗口
    }

    private Object[] snapToRouteAndSegmentBearing(LatLng pos) {
        if (routePoints == null || routePoints.size() < 2) {
            return new Object[]{pos, 0f};
        }
        LatLng best = pos;
        double minDist = Double.MAX_VALUE;
        int bestIdx = 0;
        for (int i = 0; i < routePoints.size() - 1; i++) {
            LatLng p1 = routePoints.get(i);
            LatLng p2 = routePoints.get(i + 1);
            LatLng projection = projectOntoSegment(pos, p1, p2);
            double d = computeDistanceMeters(pos, projection);
            if (d < minDist) {
                minDist = d;
                best = projection;
                bestIdx = i;
            }
        }
        LatLng segP1 = routePoints.get(bestIdx);
        LatLng segP2 = routePoints.get(bestIdx + 1);
        float segBearing = computeBearing(segP1, segP2);
        return new Object[]{best, segBearing};
    }

    private static LatLng projectOntoSegment(LatLng p, LatLng a, LatLng b) {
        double ax = a.longitude, ay = a.latitude;
        double bx = b.longitude, by = b.latitude;
        double px = p.longitude, py = p.latitude;
        double abx = bx - ax, aby = by - ay;
        double abLen2 = abx * abx + aby * aby;
        if (abLen2 < 1e-12) return new LatLng(ay, ax);
        double t = ((px - ax) * abx + (py - ay) * aby) / abLen2;
        if (t < 0) t = 0;
        else if (t > 1) t = 1;
        return new LatLng(ay + aby * t, ax + abx * t);
    }

    private static double computeDistanceMeters(LatLng a, LatLng b) {
        float[] results = new float[1];
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results);
        return results[0];
    }

    private static float computeBearing(LatLng a, LatLng b) {
        double lat1 = Math.toRadians(a.latitude);
        double lat2 = Math.toRadians(b.latitude);
        double dLon = Math.toRadians(b.longitude - a.longitude);
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        double brng = Math.toDegrees(Math.atan2(y, x));
        return (float) ((brng + 360.0) % 360.0);
    }

    private static float normalizeAngle(float deg) {
        float r = deg % 360f;
        if (r < 0) r += 360f;
        return r;
    }

    // ========== 生命周期 ==========

    public void onResume() {
        if (mapView != null) mapView.onResume();
    }

    public void onPause() {
        if (mapView != null) mapView.onPause();
    }

    public void onSaveInstanceState(Bundle outState) {
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    public void onLowMemory() {

    }

    public void onDestroy() {
        stopAmapLocation();
        // ⭐ 停止模拟位置线程：避免页面销毁后 simExecutor 仍在跑，持续通过 GpsWarmingUp
        //    注入模拟位置，导致下次进入页面直接处于模拟状态（模拟线程泄漏）
        stopLocationSimulation();

        clearBusMarkers();
        if (busIconDescriptor != null) {
            try { busIconDescriptor.recycle(); } catch (Throwable t) { }
            busIconDescriptor = null;
        }
        busIconLoaded = false;

        clearArrowMarkers();
        if (arrowMarkerIcon != null) {
            try { arrowMarkerIcon.recycle(); } catch (Throwable t) { }
            arrowMarkerIcon = null;
        }

        // ⭐ 销毁导航 SDK
        if (aMapNavi != null) {
            try {
                aMapNavi.removeAMapNaviListener(this);
                AMapNavi.destroy();
            } catch (Throwable t) {
                Log.e(TAG, "aMapNavi.destroy failed: " + t.getMessage());
            }
        }

        if (locationClient != null) {
            try {
                locationClient.onDestroy();
            } catch (Throwable t) {
                Log.e(TAG, "locationClient.onDestroy failed: " + t.getMessage());
            }
        }

        // ⭐ 销毁地图引擎（TextureMapView.onDestroy() 是 SDK 生命周期必需调用）
        //   此前这里只销毁了 AMapNavi/定位客户端，从未销毁 mapView，
        //   反复进出“车机面板/地图”页面会不断泄漏 native 地图实例与 GL 渲染上下文；
        //   泄漏到一定程度后，新进入页面的地图引擎无法正常渲染，
        //   表现即：瓦片区域全黑，只剩 SDK 自绘在地图之上的 logo 与指南针。
        if (mapView != null) {
            try {
                mapView.onDestroy();
                Log.d(TAG, "[DESTROY] mapView.onDestroy() ok");
            } catch (Throwable t) {
                Log.e(TAG, "mapView.onDestroy failed: " + t.getMessage());
            }
        }

        aMap = null;
        uiSettings = null;
        locationListener = null;
        locationUpdateListener = null;
        lastPovCallbackLocation = null;
        mapView = null;
        locationClient = null;
        aMapNavi = null;

        Log.d(TAG, "[DESTROY] onDestroy() finished");
    }

    /**
     * ⭐ POV模式专用销毁：清理所有资源，但**不销毁AMapNavi单例**
     * 因为AMapNavi是全局单例，BusLineDetailActivity仍在使用
     * 仅移除自身作为listener，不调用AMapNavi.destroy()
     */
    public void onDestroyWithoutNavi() {
        stopAmapLocation();
        // ⭐ 停止模拟位置线程（同 onDestroy，避免 POV 面板关闭后模拟线程泄漏）
        stopLocationSimulation();

        clearBusMarkers();
        if (busIconDescriptor != null) {
            try { busIconDescriptor.recycle(); } catch (Throwable t) { }
            busIconDescriptor = null;
        }
        busIconLoaded = false;

        clearArrowMarkers();
        if (arrowMarkerIcon != null) {
            try { arrowMarkerIcon.recycle(); } catch (Throwable t) { }
            arrowMarkerIcon = null;
        }

        // ⭐ 只移除listener，不销毁AMapNavi单例
        if (aMapNavi != null) {
            try {
                aMapNavi.removeAMapNaviListener(this);
            } catch (Throwable t) {
                Log.e(TAG, "aMapNavi.removeListener failed: " + t.getMessage());
            }
        }

        if (locationClient != null) {
            try {
                locationClient.onDestroy();
            } catch (Throwable t) {
                Log.e(TAG, "locationClient.onDestroy failed: " + t.getMessage());
            }
        }

        // ⭐ 清除CarOverlay
        if (carOverlay != null) {
            try {
                carOverlay.reset();
            } catch (Throwable t) { }
        }

        aMap = null;
        uiSettings = null;
        locationListener = null;
        locationUpdateListener = null;
        lastPovCallbackLocation = null;
        // ⭐ 不置null mapView，让调用方（CameraActivity）可以继续调用mapView.onDestroy()
        locationClient = null;
        aMapNavi = null;

        Log.d(TAG, "[DESTROY] onDestroyWithoutNavi() finished (AMapNavi singleton preserved)");
    }
}