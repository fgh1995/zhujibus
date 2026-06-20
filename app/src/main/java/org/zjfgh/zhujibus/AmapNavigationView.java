package org.zjfgh.zhujibus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 高德地图 3D 导航管理器（高德地图 SDK + 高德定位 SDK）
 * <p>
 * 使用高德官方 AMapLocationClient 进行定位（替代系统 GPS），能正确返回：
 * - 经纬度（GCJ-02，与高德地图坐标一致，无需转换）
 * - accuracy（精度）
 * - bearing（设备方向角，用于罗盘旋转）
 * - speed（速度）
 * </p>
 * <p>
 * 地图使用 LOCATION_TYPE_MAP_ROTATE 模式，实现完整的"罗盘模式"：
 * - 地图整体跟随设备方向旋转
 * - 定位点（车头）始终朝上
 * </p>
 */
public class AmapNavigationView implements LocationSource, AMapLocationListener {
    private static final String TAG = "AmapNavigationView";

    private final Context appContext;
    private TextureMapView mapView;
    private AMap aMap;
    private UiSettings uiSettings;
    private OnLocationChangedListener locationListener;
    private Polyline routePolyline;
    // ⭐ 路线分层：白色光晕 + 绿色主线
    private Polyline routeGlowPolyline;
    private Polyline routeMainPolyline;
    // ⭐ 沿线方向箭头 marker 列表（参考 CSDN 方案：Marker + setRotation）
    private final List<Marker> arrowMarkers = new ArrayList<>();
    private BitmapDescriptor arrowMarkerIcon;
    // ⭐ 当前地图缩放级别（用于箭头密度自适应）
    private float currentZoom = 14f;
    private boolean isCompassMode = true;

    // 高德定位 SDK
    private AMapLocationClient locationClient;
    private AMapLocationClientOption locationOption;
    private float lastCameraBearing = -1f;

    // 用于计算"运动方向"的历史位置
    private double lastLat = 0;
    private double lastLng = 0;
    private long lastLocTimeMs = 0;
    // 速度阈值：> 1.5 m/s (≈ 5.4 km/h) 用运动方向，≤ 1.5 m/s 用设备方向
    // 调低阈值的考虑：公交车在市区走走停停，速度经常在 5-15 km/h 区间
    private static final float SPEED_THRESHOLD_MPS = 1.5f;
    // ⭐ 标记：是否已应用首次导航视角（首次收到定位时强制应用一次）
    private boolean perspectiveAppliedOnFirstFix = false;
    // ⭐ 标记：当前是否为 GPS 模式（GPS 模式才应用 3D 导航视角，网络模式保持自由视角）
    private boolean isGpsMode = false;
    /** 最近一次 AMapLocation 定位成功（errorCode == 0）的时间戳（毫秒） */
    private volatile long lastGpsSuccessTimeMs = 0L;

    // ---- 公交车辆 marker 管理（仅网络模式） ----
    /** 当前线路方向的所有公交车辆 SmoothMoveMarker（车牌 → marker） */
    private final Map<String, SmoothMoveMarker> busMarkers = new HashMap<>();
    /** 记录每个车辆上一帧位置（用于决定从哪开始平滑移动） */
    private final Map<String, LatLng> lastBusPos = new HashMap<>();
    /** 记录每个车辆上一帧的时间戳（ms，用于算出 SmoothMoveMarker 的时长） */
    private final Map<String, Long> lastBusUpdateMs = new HashMap<>();
    private BitmapDescriptor busIconDescriptor;
    private boolean busIconLoaded = false;

    // ---- 路线点（用于车辆 marker 的 snap-to-road 平滑动画） ----
    private List<LatLng> routePoints = new ArrayList<>();

    /**
     * 罗盘模式下的目标相机参数（贴地 + 3D 透视）
     * 使用 static final 防止在 onLocationChanged 中被重置
     */
    public static final float TARGET_ZOOM = 18f;   // 贴地导航
    public static final float TARGET_TILT = 65f;   // 3D 俯视

    public AmapNavigationView(Context context, TextureMapView mapView) {
        this.appContext = context.getApplicationContext();
        this.mapView = mapView;
        Log.d(TAG, "[INIT] AmapNavigationView constructor called, mapView=" + mapView);
    }

    /**
     * 必须在 Activity.onCreate 中调用 —— 启动地图引擎
     * 高德 TextureMapView 要求先调用 onCreate(savedInstanceState) 才能渲染地图
     */
    public void onCreate(android.os.Bundle savedInstanceState) {
        if (mapView == null) {
            Log.e(TAG, "[INIT] onCreate skipped: mapView is null");
            return;
        }
        // ⭐ 关键修复：必须在 mapView.onCreate() 之前调用隐私协议！
        // 原因：高德 SDK 在 mapView.onCreate() 触发时会**立即检查**隐私状态。
        //       如果隐私协议未设置（首次安装），SDK 会拒绝加载地图瓦片，
        //       表现为"白屏/黑屏，什么都不显示"，日志无 error。
        //       返回重进后，SharedPreferences 中的隐私状态已存在，地图才正常加载。
        //
        // 修复方案：先 initPrivacy() → 再 mapView.onCreate()。
        // 另外显式调用 MapsInitializer.initialize()，确保 SDK 内部模块就绪。
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
        init();
        Log.d(TAG, "[INIT] map + location init done");
    }

    /**
     * 高德地图隐私协议初始化（必须在任何地图/定位 API 之前调用）
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

    private void init() {
        if (mapView == null) {
            return;
        }
        aMap = mapView.getMap();
        uiSettings = aMap != null ? aMap.getUiSettings() : null;

        // ⭐ 预生成路线方向箭头 marker icon（参考 CSDN 方案：Marker + setRotation）
        arrowMarkerIcon = createArrowMarkerIcon();

        // 监听地图加载成功/失败（排查 KEY/SHA1 不匹配的关键日志）
        if (aMap != null) {
            try {
                aMap.setOnMapLoadedListener(() -> {
                    Log.d(TAG, "[MAP] onMapLoaded —— 地图瓦片加载成功，开始应用导航视角");
                    // ⭐ 关键：必须在地图瓦片完全加载好之后再应用视角，
                    // 否则 moveCamera 会被高德内部初始化流程覆盖
                    applyNavigationCameraPerspective();
                });
                aMap.setOnMapClickListener(latLng -> Log.v(TAG, "[MAP] click at " + latLng));

                // ⭐ 监听地图缩放变化 → 自适应调整箭头密度
                //   必须在 GPS 判断外注册，**网络模式也要响应用户缩放**
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

        // UI 设置：左上角小罗盘（点击回正北）、禁用缩放按钮、启用定位按钮
        if (uiSettings != null) {
            uiSettings.setCompassEnabled(true);
            uiSettings.setZoomControlsEnabled(false);
            uiSettings.setMyLocationButtonEnabled(false);
            uiSettings.setRotateGesturesEnabled(true);
            uiSettings.setTiltGesturesEnabled(false);
            uiSettings.setScaleControlsEnabled(false);
        }

        // 定位样式：罗盘模式（地图跟随设备方向旋转，车头朝上）
        // ⭐ 注意：网络模式（默认）下不启用 my-location、不开定位，省电
        //    GPS 模式会在 setGpsMode(true) → setCompassMode(true) 中按需开启
        if (aMap != null && isGpsMode) {
            try {
                // ⭐ 参考 AMap 官方文档（5.0.0+ 标准做法）实现定位蓝点
                // 1) 初始化 MyLocationStyle 并设置类型
                // 2) 设置连续定位间隔
                // 3) 设置定位蓝点 Style
                // 4) setMyLocationEnabled(true) 启动显示
                MyLocationStyle style = new MyLocationStyle();
                // LOCATION_TYPE_LOCATION_ROTATE = 默认模式
                // 连续定位、且将视角移动到地图中心点，定位点依照设备方向旋转，并且会跟随设备移动
                style.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE);
                // 设置连续定位模式下的定位间隔，单位为毫秒（官方推荐 2000ms）
                style.interval(2000);
                // 自定义精度圆样式
                style.strokeColor(0xFF1AAD19);
                style.radiusFillColor(0x5500AAFF);
                style.strokeWidth(2.0f);
                aMap.setMyLocationStyle(style);

                // 通过 LocationSource 把高德定位 SDK 的数据喂给地图
                // 这样地图就会使用 AMapLocationClient（而非系统 GPS）进行定位，
                // 同时能保留我们自定义的智能 bearing（运动方向 vs 设备方向）
                aMap.setLocationSource(this);
                // 设置为true表示启动显示定位蓝点
                aMap.setMyLocationEnabled(true);

                // 罗盘模式下的导航视角（3D 透视 + 较高缩放），让它看起来真的像导航 App
                applyNavigationCameraPerspective();
            } catch (Throwable t) {
                Log.e(TAG, "setMyLocationStyle/setLocationSource failed: " + t.getMessage(), t);
            }
        }

        // 初始化高德定位 SDK（替代系统 GPS）
        initAmapLocation();
    }

    /**
     * 应用导航视角：3D 透视俯视 + 较高缩放 + 中心点偏移
     * 让罗盘模式在视觉上更像真实的车机导航 App
     */
    private void applyNavigationCameraPerspective() {
        if (aMap == null) return;
        // ⭐ 守卫：非 GPS 模式（网络模式）不要应用 3D 视角，保持自由视角
        if (!isGpsMode) {
            Log.d(TAG, "[MAP] applyNavigationCameraPerspective skipped: not in GPS mode");
            return;
        }
        try {
            // 使用全局常量，防止在 onLocationChanged 中被 MAP_ROTATE 模式重置
            com.amap.api.maps.model.CameraPosition cp = new com.amap.api.maps.model.CameraPosition.Builder()
                    .target(new com.amap.api.maps.model.LatLng(0, 0)) // 目标后续会在 animateCamera 时被覆盖
                    .zoom(TARGET_ZOOM)
                    .tilt(TARGET_TILT)
                    .bearing(0f)
                    .build();
            aMap.moveCamera(com.amap.api.maps.CameraUpdateFactory.newCameraPosition(cp));

            // 关闭俯视手势（避免误操作改变视角）
            aMap.getUiSettings().setTiltGesturesEnabled(false);
        } catch (Throwable t) {
            Log.e(TAG, "applyNavigationCameraPerspective failed: " + t.getMessage(), t);
        }
    }

    /**
     * 初始化高德定位 SDK
     * 使用 Hight_Accuracy 模式：优先 GPS，网络定位作为补充
     */
    private void initAmapLocation() {
        try {
            locationClient = new AMapLocationClient(appContext);
            locationOption = new AMapLocationClientOption();

            // 高精度模式：GPS + 网络定位，会返回准确的 bearing
            locationOption.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);

            // 连续定位，3 秒一次（可调）
            locationOption.setInterval(3000);

            // ⭐ 关闭传感器融合，避免设备的指南针/磁力计影响 bearing
            // 原因：横向座椅时设备方向 ≠ 车辆行驶方向，开启传感融合会导致 bearing 偏
            // 关闭后 bearing 完全依赖 GPS 经纬度变化推算（与设备朝向无关）
            locationOption.setSensorEnable(false);

            // 不需要地址信息（减少网络请求开销）
            locationOption.setNeedAddress(false);

            // 单次定位：关闭（我们要连续定位）
            locationOption.setOnceLocation(false);

            // 允许后台定位（车机场景常用）
            locationOption.setLocationCacheEnable(true);

            locationClient.setLocationOption(locationOption);
            locationClient.setLocationListener(this);

            Log.d(TAG, "[NAV] initAmapLocation ok, mode=Hight_Accuracy, interval=3000ms, sensor=true");
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
        // 重新应用 3D 导航视角（防止 deactivate→activate 后被重置为 2D 平面）
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

    // ========== AMapLocationListener 接口（核心：这里获取设备方向/位置） ==========

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
        // 记录最近一次成功定位时间，给信号指示器用
        lastGpsSuccessTimeMs = System.currentTimeMillis();

        // 调试：打印关键信息
        Log.d(TAG, String.format("[NAV] loc: lat=%.6f, lng=%.6f, acc=%.1fm, bearing=%.1f, speed=%.1f, provider=%s",
                aMapLocation.getLatitude(),
                aMapLocation.getLongitude(),
                aMapLocation.getAccuracy(),
                aMapLocation.getBearing(),
                aMapLocation.getSpeed(),
                aMapLocation.getProvider()));

        if (locationListener == null) {
            return;
        }

        // 构造 Android 标准 Location，喂给高德地图 SDK
        Location location = new Location(aMapLocation.getProvider());
        location.setLatitude(aMapLocation.getLatitude());
        location.setLongitude(aMapLocation.getLongitude());
        location.setAccuracy(aMapLocation.getAccuracy());
        location.setTime(aMapLocation.getTime());
        location.setSpeed(aMapLocation.getSpeed());

        // ⭐ 智能选择 bearing：高速时用运动方向（解决横向座椅问题），低速时用设备方向（指南针）
        // 规则：
        //   速度 > 3 m/s (≈ 10.8 km/h) → 用经纬度差计算的运动方向（车头朝哪就是哪）
        //   速度 ≤ 3 m/s               → 用设备方向（指南针/GPS heading）
        //   计算结果为 -1（无效）       → 回退到设备方向
        float speed = aMapLocation.getSpeed();
        float deviceBearing = aMapLocation.getBearing();
        float bearing = deviceBearing;
        if (deviceBearing >= 0 && deviceBearing <= 360) {
            bearing = deviceBearing; // 兜底默认用设备方向
        } else {
            bearing = -1f;
        }

        if (speed > SPEED_THRESHOLD_MPS) {
            // 高速场景：只使用运动方向，不混入设备方向（避免横向座椅/手机随意摆放导致车头指向错误）
            double curLat = aMapLocation.getLatitude();
            double curLng = aMapLocation.getLongitude();
            long curTime = aMapLocation.getTime();

            if (lastLocTimeMs > 0 && lastLat != 0) {
                float movementBearing = computeBearing(lastLat, lastLng, curLat, curLng);
                if (movementBearing >= 0) {
                    bearing = movementBearing;
                    Log.v(TAG, String.format("[NAV] using MOVEMENT bearing=%.1f (speed=%.1f m/s, dev=%.1f)",
                            movementBearing, speed, deviceBearing));
                } else {
                    // 两次位置几乎重合（GPS 抖动）但速度又有意义，先用设备方向
                    Log.v(TAG, String.format("[NAV] loc unchanged, using DEVICE bearing=%.1f (speed=%.1f m/s)",
                            deviceBearing, speed));
                }
            } else {
                // ⭐ 第一次定位：没有历史位置，标记为运动模式但暂用设备方向
                // 下一次定位就能算出运动方向了
                Log.d(TAG, String.format("[NAV] first fix in movement, using DEVICE bearing=%.1f (will switch next loc)",
                        deviceBearing));
            }

            // 更新历史位置（无论是否使用了运动方向，都要更新）
            lastLat = curLat;
            lastLng = curLng;
            lastLocTimeMs = curTime;
        } else {
            // 低速/静止：重置历史位置（避免下次高速时用过期的位置计算）
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
        locationListener.onLocationChanged(location);

        // ⭐ 首次收到定位时强制应用一次 3D 视角（onMapLoaded 时可能还没拿到位置）
        // 这样可以解决"必须点我的位置才生效"的问题
        if (!perspectiveAppliedOnFirstFix && aMap != null) {
            perspectiveAppliedOnFirstFix = true;
            applyNavigationCameraPerspective();
        }

        // 方向变化较大时主动更新 camera bearing，确保罗盘旋转平滑
        // 注意：必须用硬编码的 targetZoom/targetTilt，不能用 current.zoom/current.tilt，
        // 否则 MAP_ROTATE 模式会自动把这两个值重置为 0（2D 平面）
        if (aMap != null && bearing >= 0 && bearing <= 360) {
            if (Math.abs(bearing - lastCameraBearing) > 1.0f) {
                try {
                    CameraPosition current = aMap.getCameraPosition();
                    aMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                            new CameraPosition(
                                    current.target,
                                    TARGET_ZOOM,    // 强制使用设定的 zoom（贴地）
                                    TARGET_TILT,    // 强制使用设定的 tilt（3D 透视）
                                    bearing
                            )
                    ));
                    lastCameraBearing = bearing;
                } catch (Throwable t) {
                    // 静默失败：地图初始化未完成时会抛出，忽略即可
                }
            }
        }
    }

    // ========== 业务方法 ==========

    /**
     * 绘制路线（GCJ-02 坐标，与高德地图原生坐标系一致，无需转换）
     *
     * 两层 + 方向箭头 marker：
     *   1) routeGlowPolyline  —— 半透明白色光晕（最宽）
     *   2) routeMainPolyline  —— 绿色主线（中宽）
     *   3) arrowMarkers       —— 沿线等距放置的白色"›"形 marker
     *                           （参考 CSDN：Marker + setRotation 显式控制方向）
     */
    public void drawRoute(List<LatLng> points) {
        if (aMap == null || points == null || points.isEmpty()) {
            return;
        }
        // 清理旧路线
        if (routeGlowPolyline != null) { routeGlowPolyline.remove(); routeGlowPolyline = null; }
        if (routeMainPolyline != null) { routeMainPolyline.remove(); routeMainPolyline = null; }
        if (routePolyline != null) { routePolyline.remove(); routePolyline = null; }
        clearArrowMarkers();

        // ⭐ 保存路线点副本，供后续车辆 marker 的 snap-to-road 使用
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
                .color(0xFF4CAF50);  // 绿色
        routeMainPolyline = aMap.addPolyline(mainOpt);
        // 兼容旧引用（外部代码可能仍在读 routePolyline）
        routePolyline = routeMainPolyline;

        // 3) 沿线方向箭头 marker
        drawArrowMarkers(points);

        try {
            LatLngBounds.Builder builder = LatLngBounds.builder();
            for (LatLng p : points) {
                builder.include(p);
            }
            LatLngBounds bounds = builder.build();
            CameraUpdate update = CameraUpdateFactory.newLatLngBounds(bounds, 100);
            aMap.animateCamera(update);
        } catch (Exception e) {
            Log.e(TAG, "drawRoute bounds failed: " + e.getMessage());
        }
    }

    /**
     * ⭐ 生成路线方向箭头 marker icon（**尖端朝上/朝北**的"›"形 V 字）
     *
     * 关键：箭头尖端必须朝上（北），这样 setRotateAngle(0) 时箭头指北，
     *       设置正确角度后就能对准路径方向。
     *
     * 形状（32×32）：
     * <pre>
     *             ▲    ← 尖端（Y=8，朝北）
     *            ╱ ╲
     *           ╱   ╲
     *          ╱     ╲
     * </pre>
     */
    private BitmapDescriptor createArrowMarkerIcon() {
        try {
            int w = 32;
            int h = 32;
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFFFFFFFF);  // 白色
            paint.setStrokeWidth(5f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            Path v = new Path();
            v.moveTo(w * 0.22f, h * 0.30f);  // 左下
            v.lineTo(w * 0.50f, h * 0.08f);  // 顶端（北）
            v.lineTo(w * 0.78f, h * 0.30f);  // 右下
            canvas.drawPath(v, paint);

            return BitmapDescriptorFactory.fromBitmap(bitmap);
        } catch (Throwable t) {
            Log.e(TAG, "createArrowMarkerIcon failed: " + t.getMessage(), t);
            return null;
        }
    }

    /**
     * ⭐ 沿路径每 N 米放一个方向箭头 marker
     *
     * 间距随 zoom 自适应，避免缩小时整条路变白：
     * <pre>
     *  zoom  3-12  → 间距 1500m（城市级，16km 路线约 10 个箭头）
     *  zoom 12-13  → 间距 500m
     *  zoom 13-14  → 间距 200m
     *  zoom 14-15  → 间距 100m
     *  zoom 15-16  → 间距 60m
     *  zoom >= 16  → 间距 40m（最密，贴地导航）
     * </pre>
     *
     * 注意：drawRoute 在 onMapLoaded 之前可能调用，
     *       这时 aMap.getCameraPosition() 返回初始 zoom（3-5），
     *       此时不画箭头会出现"完全没箭头"的问题；
     *       所以采用兜底：cp.zoom < 3 时用默认值 14。
     */
    private void drawArrowMarkers(List<LatLng> points) {
        if (aMap == null) {
            Log.w(TAG, "[ARROW] aMap == null, skip");
            return;
        }
        if (points == null || points.size() < 2) {
            Log.w(TAG, "[ARROW] points invalid, skip");
            return;
        }
        if (arrowMarkerIcon == null) {
            Log.w(TAG, "[ARROW] arrowMarkerIcon == null, skip");
            return;
        }

        // 同步 zoom（从 CameraPosition 取最新值）
        try {
            CameraPosition cp = aMap.getCameraPosition();
            if (cp != null && cp.zoom >= 3f) {  // 高德初始 zoom 可能在 3-5
                currentZoom = cp.zoom;
            } else {
                // ⭐ CameraPosition 还没初始化（地图未加载完成），
                //   用合理默认值 14，让箭头能画出来
                if (currentZoom < 3f) currentZoom = 14f;
            }
        } catch (Throwable t) {
            if (currentZoom < 3f) currentZoom = 14f;
        }
        Log.d(TAG, "[ARROW] drawArrowMarkers zoom=" + currentZoom);

        // 2) 计算每段长度 + 总长度
        final double[] segLens = new double[points.size() - 1];
        double totalLen = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            segLens[i] = AMapUtils.calculateLineDistance(points.get(i), points.get(i + 1));
            totalLen += segLens[i];
        }
        if (totalLen <= 1) return;

        // 3) ⭐ 根据 zoom 自适应间距
        double spacing;
        if (currentZoom < 12f)      spacing = 1500.0;   // 城市级 16km ≈ 10 个
        else if (currentZoom < 13f) spacing = 500.0;
        else if (currentZoom < 14f) spacing = 200.0;
        else if (currentZoom < 15f) spacing = 100.0;
        else if (currentZoom < 16f) spacing = 60.0;
        else                        spacing = 40.0;

        // 清理旧箭头（不管后面是否真要画）
        clearArrowMarkers();

        // 4) 沿线插值放置
        double traveled = spacing * 0.5;  // 起点稍微偏一点，避免和站点重叠
        int segIdx = 0;
        double acc = 0;
        while (traveled < totalLen) {
            // 找 traveled 落在哪一段
            while (segIdx < segLens.length - 1 && acc + segLens[segIdx] < traveled) {
                acc += segLens[segIdx];
                segIdx++;
            }
            if (segIdx >= segLens.length) break;

            double segStart = acc;
            double t = (traveled - segStart) / segLens[segIdx];
            if (t < 0) t = 0;
            if (t > 1) t = 1;

            // 5) 沿线插值得到位置
            double lat = points.get(segIdx).latitude
                    + (points.get(segIdx + 1).latitude - points.get(segIdx).latitude) * t;
            double lng = points.get(segIdx).longitude
                    + (points.get(segIdx + 1).longitude - points.get(segIdx).longitude) * t;

            // 6) 该段方向角（高德 setRotateAngle 是逆时针，computeAzimuth 是顺时钟，需取反）
            float angle = (float) computeAzimuth(points.get(segIdx), points.get(segIdx + 1));
            // setRotateAngle 是"从正北开始逆时针"，azimuth 是"从正北开始顺时针"
            float rotateAngle = (360f - angle) % 360f;

            // ⭐ 注意：高德 9.x 是 setFlat / setRotateAngle（不是 flat / setRotation）
            Marker marker = aMap.addMarker(new MarkerOptions()
                    .position(new LatLng(lat, lng))
                    .icon(arrowMarkerIcon)
                    .anchor(0.5f, 0.5f)
                    .setFlat(true)                              // 贴地显示
                    .setInfoWindowOffset(0, 0)
                    .zIndex(1f));
            if (marker != null) {
                marker.setRotateAngle(rotateAngle);            // 9.x 用 setRotateAngle
            }
            arrowMarkers.add(marker);

            traveled += spacing;
        }
        Log.d(TAG, "[ARROW] zoom=" + currentZoom + " spacing=" + spacing + "m count=" + arrowMarkers.size());
    }

    /**
     * 清理所有方向箭头 marker
     */
    private void clearArrowMarkers() {
        for (Marker m : arrowMarkers) {
            try { m.remove(); } catch (Throwable ignore) {}
        }
        arrowMarkers.clear();
    }

    /**
     * 计算两点的方位角（正北=0度，**顺时针**递增）
     * —— 地理学标准公式
     * 注意：高德 Marker.setRotateAngle 是"逆时针"角度，需要用 (360 - azimuth) 转换
     */
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
        locationListener.onLocationChanged(location);
    }

    /**
     * 设置/取消罗盘模式
     */
    public void setCompassMode(boolean enabled) {
        this.isCompassMode = enabled;
        if (uiSettings != null) {
            uiSettings.setCompassEnabled(enabled);
        }
        if (aMap != null) {
            try {
                // ⭐ 参考 AMap 官方文档（5.0.0+ 标准做法）实现定位蓝点
                MyLocationStyle style = new MyLocationStyle();
                style.myLocationType(enabled
                        ? MyLocationStyle.LOCATION_TYPE_MAP_ROTATE  // 罗盘模式
                        : MyLocationStyle.LOCATION_TYPE_FOLLOW);    // 普通跟随模式
                style.interval(2000); // 官方推荐 2 秒一次定位
                style.strokeColor(0xFF1AAD19);
                style.radiusFillColor(0x5500AAFF);
                style.strokeWidth(2.0f);
                aMap.setMyLocationStyle(style);

                // ⭐ 关键：罗盘模式开启时，必须重新启用我的位置 + 重新设置定位源
                // 否则高德地图内部不会重新调用 LocationSource.activate()，
                // 之前 setMyLocationEnabled(false) 把 locationListener 置为 null 后无法恢复
                if (enabled) {
                    aMap.setLocationSource(this);
                    aMap.setMyLocationEnabled(true);
                }
            } catch (Throwable t) {
                Log.e(TAG, "setCompassMode update style failed: " + t.getMessage());
            }
        }
    }

    public boolean isCompassMode() {
        return isCompassMode;
    }

    /**
     * 设置当前是 GPS 模式还是网络模式
     * <ul>
     *   <li>GPS 模式：开启罗盘模式（地图旋转 + 3D 贴地视角），启动 AMap 定位</li>
     *   <li>网络模式：关闭罗盘模式，地图保持自由视角，停止 AMap 定位省电</li>
     * </ul>
     */
    public void setGpsMode(boolean gps) {
        this.isGpsMode = gps;
        Log.d(TAG, "[NAV] setGpsMode(" + gps + ")");

        if (aMap == null) return;

        try {
            if (gps) {
                // GPS 模式：先清掉所有公交车辆 marker（用户自己就是其中一辆）
                clearBusMarkers();
                // GPS 模式：罗盘 + 3D 视角
                setCompassMode(true);
                perspectiveAppliedOnFirstFix = false; // 让首次定位时重新应用一次
                // 启动 AMap 定位（GPS 模式需要实时位置）
                startAmapLocation();
            } else {
                // ⭐ 网络模式：关闭罗盘模式，地图回到自由视角
                // 关闭 setMyLocationEnabled 后，用户可以自由缩放/平移，地图不再被强制居中
                aMap.setMyLocationEnabled(false);
                setCompassMode(false);
                // 恢复为俯视 2D 平面，让网络模式看起来正常
                aMap.moveCamera(CameraUpdateFactory.newCameraPosition(
                        new CameraPosition(
                                aMap.getCameraPosition().target,
                                aMap.getCameraPosition().zoom,
                                0f,  // tilt = 0，恢复平面视角
                                0f   // bearing = 0，正北朝上
                        )
                ));
                // ⭐ 网络模式停止 AMap 定位（省电 + GPS 信号图标立即失效）
                stopAmapLocation();
                // 重置最近成功定位时间，让 GPS 信号图标立刻变 not_signal
                lastGpsSuccessTimeMs = 0L;
            }
        } catch (Throwable t) {
            Log.e(TAG, "setGpsMode failed: " + t.getMessage(), t);
        }
    }

    public boolean isGpsMode() {
        return isGpsMode;
    }

    /**
     * 最近一次 AMapLocation 定位成功的时间戳（毫秒），0 表示从未成功过。
     * 给 GPS 信号指示器（{@link SignalIndicatorManager}）使用。
     */
    public long getLastGpsSuccessTimeMs() {
        return lastGpsSuccessTimeMs;
    }

    /**
     * 计算两点间的方位角（0=正北，顺时针 0-360）
     * 用于根据位置变化推算"车头朝向"
     */
    private static float computeBearing(double lat1, double lng1, double lat2, double lng2) {
        // 如果距离过近（< 1米），方位角无意义
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
     * 方位角加权平均（处理 0/360 边界跨越问题）
     * @param a 方位角 A
     * @param b 方位角 B
     * @param weightA A 的权重 (0~1)
     */
    private static float weightedAverageBearing(float a, float b, float weightA) {
        // 转为单位向量后再加权
        double radA = Math.toRadians(a);
        double radB = Math.toRadians(b);
        double x = weightA * Math.sin(radA) + (1 - weightA) * Math.sin(radB);
        double y = weightA * Math.cos(radA) + (1 - weightA) * Math.cos(radB);
        double avg = Math.toDegrees(Math.atan2(x, y));
        return (float) ((avg + 360.0) % 360.0);
    }

    /**
     * 移动地图相机到指定位置
     */
    public void moveCamera(double gcjLat, double gcjLon, float zoom) {
        if (aMap == null) return;
        aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(gcjLat, gcjLon), zoom));
    }

    // ========== 公交车辆 marker（仅网络模式生效） ==========

    /** 公交车辆 marker 目标显示尺寸（dp） */
    private static final int BUS_MARKER_SIZE_DP = 24;
    /** 角度常量：图标"车头"位于图标右侧，因此高德 setRotateAngle 需要偏移 90° */
    private static final float HEAD_BEARING_OFFSET = 90f;
    /**
     * 公交车辆在地图上的视觉移动速度（米/秒），≈ 30 km/h。
     * <p>
     * 不论 API 推送频率多少，都用 distance / SPEED 算出动画时长，
     * 保证车辆在屏幕上看起来一直以固定速度前进。
     */
    private static final double BUS_VISUAL_SPEED_MPS = 8.0;
    /** 动画时长下限（秒）。距离太小时至少要这么多秒，避免瞬移感。 */
    private static final int MIN_ANIM_DURATION_SEC = 1;
    /**
     * 动画时长上限（秒）。远距离跳变（如 GPS 大幅漂移）也不会超过这个上限，
     * 实际速度会临时高于 BUS_VISUAL_SPEED_MPS，但视觉上还是连续的。
     */
    private static final int MAX_ANIM_DURATION_SEC = 30;
    /** 距离 < 此值（米）时直接 setPosition，不开动画 */
    private static final double MIN_MOVE_DISTANCE_M = 1.0;

    /**
     * 加载公交车辆 icon（icon_map_bus.png）
     * <p>
     * 关键点：
     *  - 用 inScaled=false + inDensity 强制读原始像素，避免被系统按 density 缩放
     *  - 按原图长宽比缩放到目标尺寸上限，避免变形
     *  - 强制 ARGB_8888 + 双线性过滤，避免边缘锯齿
     */
    private void loadBusIcon() {
        if (busIconLoaded) return;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;             // 禁止系统按 density 自动缩放
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inDensity = 0;                // 关闭 density 桶
            opts.inTargetDensity = 1;
            opts.inScreenDensity = 1;
            Bitmap raw = BitmapFactory.decodeResource(
                    appContext.getResources(), R.drawable.icon_map_bus, opts);
            if (raw == null) {
                Log.e(TAG, "[BUS] loadBusIcon: decodeResource returned null");
                return;
            }

            int origW = raw.getWidth();
            int origH = raw.getHeight();
            Log.d(TAG, "[BUS] icon original size: " + origW + "x" + origH);

            float density = appContext.getResources().getDisplayMetrics().density;
            int targetMaxPx = Math.round(BUS_MARKER_SIZE_DP * density);
            // 等比缩放到 targetMaxPx 长边（不画正方形画布，避免短边被裁）
            Bitmap scaled = scaleBitmapKeepAspect(raw, targetMaxPx);
            if (scaled != raw) raw.recycle();
            busIconDescriptor = BitmapDescriptorFactory.fromBitmap(scaled);
            busIconLoaded = true;
            Log.d(TAG, "[BUS] loadBusIcon ok, final size=" + scaled.getWidth() + "x" + scaled.getHeight()
                    + "px (target=" + targetMaxPx + "px, density=" + density + ")");
        } catch (Throwable t) {
            Log.e(TAG, "[BUS] loadBusIcon failed: " + t.getMessage(), t);
        }
    }

    /**
     * 按原图比例等比缩放，长边 = maxPx
     * <p>
     * 关键点：不画正方形画布、不加透明 padding。AMap marker 会用位图的实际尺寸显示，
     * 保持原图长宽比，避免原图被"裁掉"短边部分。
     */
    private static Bitmap scaleBitmapKeepAspect(Bitmap raw, int maxPx) {
        int origW = raw.getWidth();
        int origH = raw.getHeight();
        if (origW <= 0 || origH <= 0) return raw;
        if (origW == maxPx && origH == maxPx) return raw;
        // 长边等比缩放到 maxPx，短边按比例
        float scale = (float) maxPx / Math.max(origW, origH);
        int scaledW = Math.max(1, Math.round(origW * scale));
        int scaledH = Math.max(1, Math.round(origH * scale));
        return Bitmap.createScaledBitmap(raw, scaledW, scaledH, true);
    }

    /**
     * 在地图上绘制当前方向的所有公交车辆实时位置。
     * <p>
     * 使用官方 {@link SmoothMoveMarker}（在 AMap 9.3.0 上为兼容方案）做平滑移动：
     *  - GPS 模式不绘制，保留用户自身位置
     *  - 新车首次出现：直接 setPosition 放置（避免从屏幕外飞入）
     *  - 已存在车辆：
     *      1) 将坐标吸附到最近路线线段（snap-to-road）→ 走"路线"而不是直线
     *      2) 固定视觉速度：duration = distance / BUS_VISUAL_SPEED_MPS
     *         不论 API 推送频率多少，车辆在屏幕上看起来匀速前进。
     *      3) 关键：轨迹起点是 marker 当前的动画位置（getPosition）而不是上一帧的
     *         服务器坐标，这样前一个动画在任意时刻被新动画接管都不会"瞬移回旧位置"。
     *      4) 通过 getMarker().setRotateAngle() 在动画过程中持续设置旋转，
     *         车头沿路线段方向（含弯道跟随）。
     *  - 已离场车辆自动 destroy
     */
    public void updateBusMarkers(List<BusApiClient.BusPosition> positions) {
        if (aMap == null) return;

        if (isGpsMode) {
            clearBusMarkers();
            return;
        }

        loadBusIcon();
        if (busIconDescriptor == null) {
            Log.w(TAG, "[BUS] updateBusMarkers skipped: icon not loaded");
            return;
        }

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

            // 1) snap-to-road，得到吸附点和最近段方向
            LatLng rawPos = new LatLng(pos.lat, pos.lng);
            Object[] snap = snapToRouteAndSegmentBearing(rawPos);
            LatLng snappedPos = (LatLng) snap[0];
            float segBearingDeg = (Float) snap[1];

            // 2) 计算最终旋转角（段方向 vs 运动方向，偏差>90°翻转）
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

            // 3) 取出（或创建）SmoothMoveMarker
            SmoothMoveMarker smm = busMarkers.get(plate);
            if (smm == null) {
                // 首次出现：直接放置，不动画（避免从屏幕外飞入）
                try {
                    smm = new SmoothMoveMarker(aMap);
                    smm.setDescriptor(busIconDescriptor);
                    smm.setPosition(snappedPos);
                    setSmmTitle(smm, plate, pos.isArrived);
                    busMarkers.put(plate, smm);
                    applyRotateToSmm(smm, rotateDeg);
                } catch (Throwable t) {
                    Log.e(TAG, "[BUS] create SmoothMoveMarker failed for " + plate + ": " + t.getMessage());
                }
                if (smm != null) {
                    lastBusPos.put(plate, snappedPos);
                    lastBusUpdateMs.put(plate, nowMs);
                }
                continue;
            }

            // 已存在：用 setMoveListener 实时刷新旋转角
            attachRotationListener(smm, plate);

            // 4) 关键：取 marker 当前动画中的实际位置（可能还没到上一个目标点），
            //    以此为新轨迹的起点，避免"动画未完 → 跳回旧起点"造成视觉跳变。
            LatLng animatedPos = null;
            try {
                animatedPos = smm.getPosition();
            } catch (Throwable t) { /* ignore */ }
            if (animatedPos == null) animatedPos = prevPos;

            // 5) 固定速度策略：duration = distance / SPEED
            //    大跳变被 MAX_ANIM_DURATION_SEC 截断；距离 < 1m 直接 setPosition。
            double distMeters = computeDistanceMeters(animatedPos, snappedPos);
            if (distMeters < MIN_MOVE_DISTANCE_M) {
                try { smm.setPosition(snappedPos); } catch (Throwable ignore) {}
            } else {
                int durationSec = (int) Math.round(distMeters / BUS_VISUAL_SPEED_MPS);
                if (durationSec < MIN_ANIM_DURATION_SEC) durationSec = MIN_ANIM_DURATION_SEC;
                if (durationSec > MAX_ANIM_DURATION_SEC) durationSec = MAX_ANIM_DURATION_SEC;

                // 6) 轨迹 = [动画当前所在位置, 新目标]，而不是 [prevPos, snappedPos]，
                //    这样前一动画在任意时刻被新动画接管都不会"瞬移回旧位置"。
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
        }

        // 移除已离场车辆
        Iterator<Map.Entry<String, SmoothMoveMarker>> it = busMarkers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SmoothMoveMarker> e = it.next();
            if (!currentPlates.contains(e.getKey())) {
                try {
                    e.getValue().destroy();
                } catch (Throwable t) { /* ignore */ }
                it.remove();
                lastBusPos.remove(e.getKey());
                lastBusUpdateMs.remove(e.getKey());
            }
        }
    }

    /** 给 SmoothMoveMarker 设置 title（info window 用） */
    private void setSmmTitle(SmoothMoveMarker smm, String plate, boolean isArrived) {
        try {
            Marker m = smm.getMarker();
            if (m != null) {
                m.setTitle(isArrived ? "公交 " + plate + "（已到站）" : "公交 " + plate);
            }
        } catch (Throwable ignore) {}
    }

    /** 给 SmoothMoveMarker 底层 Marker 设旋转角 */
    private void applyRotateToSmm(SmoothMoveMarker smm, float rotateDeg) {
        try {
            Marker m = smm.getMarker();
            if (m != null) m.setRotateAngle(rotateDeg);
        } catch (Throwable ignore) {}
    }

    /**
     * 给 SmoothMoveMarker 装上移动回调，在动画过程中持续按当前实际位置刷新旋转角，
     * 这样即使车在弯道段（段方向不断变化），车头也能跟得上。
     */
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
                    } catch (Throwable ignore) {}
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "[BUS] setMoveListener failed for " + plate);
        }
    }

    /**
     * 清空地图上所有公交车辆 marker，并清空辅助状态
     */
    public void clearBusMarkers() {
        for (SmoothMoveMarker smm : busMarkers.values()) {
            if (smm != null) {
                try { smm.destroy(); } catch (Throwable t) { /* ignore */ }
            }
        }
        busMarkers.clear();
        lastBusPos.clear();
        lastBusUpdateMs.clear();
    }

    // ---- 几何工具 ----

    /**
     * 将坐标吸附到最近路线线段上，返回【吸附后的坐标】和【最近线段的方向角】
     * <p>
     * 返回值是一个长度为 2 的数组：index 0 = 吸附点，index 1 = 最近段的方向角（度）。
     * 路线未绘制（&lt;2 个点）时返回 [原坐标, 0]。
     */
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
        // 用最近段的方向作为 marker 旋转依据
        LatLng segP1 = routePoints.get(bestIdx);
        LatLng segP2 = routePoints.get(bestIdx + 1);
        float segBearing = computeBearing(segP1, segP2);
        return new Object[]{best, segBearing};
    }

    /**
     * 将点 p 投影到线段 a→b 上，结果限制在 a、b 之间
     */
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

    /**
     * 两坐标之间的距离（米）
     */
    private static double computeDistanceMeters(LatLng a, LatLng b) {
        float[] results = new float[1];
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results);
        return results[0];
    }

    /**
     * 计算 a→b 的方位角（0=北，90=东，180=南，270=西）
     */
    private static float computeBearing(LatLng a, LatLng b) {
        double lat1 = Math.toRadians(a.latitude);
        double lat2 = Math.toRadians(b.latitude);
        double dLon = Math.toRadians(b.longitude - a.longitude);
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        double brng = Math.toDegrees(Math.atan2(y, x));
        return (float) ((brng + 360.0) % 360.0);
    }

    /**
     * 把角度规整到 [0, 360) 区间
     */
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

    public void onSaveInstanceState(android.os.Bundle outState) {
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    public void onLowMemory() {
        if (mapView != null) mapView.onLowMemory();
    }

    public void onDestroy() {
        // ⭐ 关键修复：不要主动调用 mapView.onDestroy()！
        //
        // 高德 9.3.0 SDK 的 GLMapEngine_nativeDestroy 在 GL 线程上存在竞争条件：
        //   - 主线程调用 mapView.onDestroy() → 触发 nativeDestroy 销毁 GL 上下文
        //   - 此时 GL 线程（GLThread 45xxx）正在渲染当前帧
        //   - GL 线程访问已销毁的 GL 上下文 → SIGABRT (Pointer tag truncated)
        //
        // 多次尝试（延迟 100ms、调整调用顺序）均无法完全规避，
        // 因为高德 SDK 内部 GL 线程退出是异步的，无法通过主线程操作保证时序。
        //
        // 最终方案：只释放 locationClient 等可安全销毁的资源，
        // mapView 引用置空后由 GC 在 Activity 销毁时连带回收：
        //   Activity 销毁 → Window 销毁 → Surface 销毁 → GL 线程自然退出
        //   → mapView 引用释放 → GC 回收（高德 native 资源随之释放）
        //
        // 优点：彻底避免 SIGABRT
        // 代价：轻微内存延迟释放（可接受，因为 Activity 本身就快被销毁）

        // 1. 停止定位（最优先：避免 onLocationChanged 继续回调）
        stopAmapLocation();

        // 2. 销毁公交车辆 marker 与 icon 资源
        clearBusMarkers();
        if (busIconDescriptor != null) {
            try { busIconDescriptor.recycle(); } catch (Throwable t) { /* ignore */ }
            busIconDescriptor = null;
        }
        busIconLoaded = false;

        // ⭐ 释放路线方向箭头 marker
        clearArrowMarkers();
        if (arrowMarkerIcon != null) {
            try { arrowMarkerIcon.recycle(); } catch (Throwable t) { /* ignore */ }
            arrowMarkerIcon = null;
        }

        // 3. 销毁 locationClient（这部分在高德 SDK 中是安全的，无 GL 线程依赖）
        if (locationClient != null) {
            try {
                locationClient.onDestroy();
            } catch (Throwable t) {
                Log.e(TAG, "locationClient.onDestroy failed: " + t.getMessage());
            }
        }

        // 4. 切断所有引用，防止延迟回调踩到销毁中的对象
        aMap = null;
        uiSettings = null;
        locationListener = null;
        mapView = null;
        locationClient = null;

        Log.d(TAG, "[DESTROY] onDestroy() finished (mapView left to GC)");
    }
}
