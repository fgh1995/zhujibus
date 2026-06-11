package org.zjfgh.zhujibus;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdate;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.LocationSource;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.UiSettings;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.MyLocationStyle;
import com.amap.api.maps.model.Polyline;
import com.amap.api.maps.model.PolylineOptions;

import java.util.List;

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
    // 标记：是否已应用首次导航视角（首次收到定位时强制应用一次）
    private boolean perspectiveAppliedOnFirstFix = false;
    // ⭐ 标记：当前是否为 GPS 模式（GPS 模式才应用 3D 导航视角，网络模式保持自由视角）
    private boolean isGpsMode = false;

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
        if (aMap != null) {
            try {
                MyLocationStyle style = new MyLocationStyle();
                // LOCATION_TYPE_MAP_ROTATE = 3 —— 完整的罗盘模式
                style.myLocationType(MyLocationStyle.LOCATION_TYPE_MAP_ROTATE);
                style.strokeColor(0xFF1AAD19);
                style.radiusFillColor(0x5500AAFF);
                style.strokeWidth(2.0f);
                style.showMyLocation(true);
                aMap.setMyLocationStyle(style);

                // 通过 LocationSource 把高德定位 SDK 的数据喂给地图
                // 这样地图就会使用 AMapLocationClient（而非系统 GPS）进行定位
                aMap.setLocationSource(this);
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
     */
    public void drawRoute(List<LatLng> points) {
        if (aMap == null || points == null || points.isEmpty()) {
            return;
        }
        if (routePolyline != null) {
            routePolyline.remove();
            routePolyline = null;
        }
        PolylineOptions polylineOptions = new PolylineOptions()
                .addAll(points)
                .width(15f)
                .color(0xFF00AAFF);
        routePolyline = aMap.addPolyline(polylineOptions);

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
                MyLocationStyle style = new MyLocationStyle();
                style.myLocationType(enabled
                        ? MyLocationStyle.LOCATION_TYPE_MAP_ROTATE  // 罗盘模式
                        : MyLocationStyle.LOCATION_TYPE_FOLLOW);    // 普通跟随模式
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
     * - GPS 模式：开启罗盘模式（地图旋转 + 3D 贴地视角）
     * - 网络模式：关闭罗盘模式，地图保持自由视角（用户可自由缩放/平移）
     */
    public void setGpsMode(boolean gps) {
        this.isGpsMode = gps;
        Log.d(TAG, "[NAV] setGpsMode(" + gps + ")");

        if (aMap == null) return;

        try {
            if (gps) {
                // GPS 模式：罗盘 + 3D 视角
                setCompassMode(true);
                perspectiveAppliedOnFirstFix = false; // 让首次定位时重新应用一次
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
            }
        } catch (Throwable t) {
            Log.e(TAG, "setGpsMode failed: " + t.getMessage(), t);
        }
    }

    public boolean isGpsMode() {
        return isGpsMode;
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

        // 2. 销毁 locationClient（这部分在高德 SDK 中是安全的，无 GL 线程依赖）
        if (locationClient != null) {
            try {
                locationClient.onDestroy();
            } catch (Throwable t) {
                Log.e(TAG, "locationClient.onDestroy failed: " + t.getMessage());
            }
        }

        // 3. 切断所有引用，防止延迟回调踩到销毁中的对象
        aMap = null;
        uiSettings = null;
        locationListener = null;
        mapView = null;
        locationClient = null;

        Log.d(TAG, "[DESTROY] onDestroy() finished (mapView left to GC)");
    }
}
