package org.zjfgh.zhujibus;

import android.content.Context;
import android.graphics.Color;
import android.location.Location;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.tencent.map.geolocation.TencentLocation;
import com.tencent.map.geolocation.TencentLocationListener;
import com.tencent.map.geolocation.TencentLocationManager;
import com.tencent.map.geolocation.TencentLocationRequest;
import com.tencent.tencentmap.mapsdk.maps.CameraUpdate;
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory;
import com.tencent.tencentmap.mapsdk.maps.LocationSource;
import com.tencent.tencentmap.mapsdk.maps.MapView;
import com.tencent.tencentmap.mapsdk.maps.TencentMap;
import com.tencent.tencentmap.mapsdk.maps.TencentMapInitializer;
import com.tencent.tencentmap.mapsdk.maps.TextureMapView;
import com.tencent.tencentmap.mapsdk.maps.UiSettings;
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptor;
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory;
import com.tencent.tencentmap.mapsdk.maps.model.CameraPosition;
import com.tencent.tencentmap.mapsdk.maps.model.LatLng;
import com.tencent.tencentmap.mapsdk.maps.model.LatLngBounds;
import com.tencent.tencentmap.mapsdk.maps.model.LocationCompass;
import com.tencent.tencentmap.mapsdk.maps.model.LocationNavigationGravityline;
import com.tencent.tencentmap.mapsdk.maps.model.Marker;
import com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions;
import com.tencent.tencentmap.mapsdk.maps.model.MyLocationConfig;
import com.tencent.tencentmap.mapsdk.maps.model.MyLocationStyle;
import com.tencent.tencentmap.mapsdk.maps.model.Polyline;
import com.tencent.tencentmap.mapsdk.maps.model.PolylineOptions;

import java.util.List;

/**
 * 腾讯地图 6.11 导航管理器（3D SDK + 腾讯定位 SDK）
 * <p>
 * 使用腾讯官方 TencentLocationManager 提供定位数据，能正确返回：
 * - 经纬度（GCJ-02）
 * - accuracy（精度）
 * - direction（设备方向角）<b>← 这是触发罗盘旋转的关键数据</b>
 * - bearing（GPS 移动方向）
 * </p>
 * <p>
 * 完全参照官方 TencentMapDemo/LocationPointActivity 实现
 * </p>
 */
public class TencentNavigationView implements LocationSource, TencentLocationListener {
    private static final String TAG = "TencentNavigationView";

    private final Context appContext;
    private TextureMapView mapView;
    private TencentMap tencentMap;
    private UiSettings uiSettings;
    private OnLocationChangedListener locationListener;
    private Polyline routePolyline;
    private boolean isCompassMode = true;

    // 腾讯定位 SDK
    private TencentLocationManager locationManager;
    private TencentLocationRequest locationRequest;

    public TencentNavigationView(Context context, TextureMapView mapView) {
        this.appContext = context.getApplicationContext();
        this.mapView = mapView;
        // 透明背景，配合 clipToOutline 实现圆角剪切
        if (this.mapView != null) {
            this.mapView.setOpaque(false);
        }
        Log.d(TAG, "[INIT] TencentNavigationView constructor called, mapView=" + mapView);
        initSdk();
        Log.d(TAG, "[INIT] initSdk done");
        init();
        Log.d(TAG, "[INIT] init done");
    }

    /**
     * 6.11.0 起，必须在初始化 MapView 之前完成：
     * 1) TencentMapInitializer.setAgreePrivacy - 同意隐私协议
     * 2) TencentMapInitializer.start           - 显式开启 SDK
     * 3) TencentLocationManager.setUserAgreePrivacy - 同意定位隐私
     * 否则地图会完全不显示（白屏）。
     */
    private void initSdk() {
        try {
            TencentMapInitializer.setAgreePrivacy(appContext, true);
        } catch (Throwable t) {
            Log.e(TAG, "TencentMapInitializer.setAgreePrivacy failed: " + t.getMessage());
        }
        try {
            TencentMapInitializer.start(appContext);
        } catch (Throwable t) {
            Log.e(TAG, "TencentMapInitializer.start failed: " + t.getMessage());
        }
        try {
            TencentLocationManager.setUserAgreePrivacy(true);
        } catch (Throwable t) {
            Log.e(TAG, "TencentLocationManager.setUserAgreePrivacy failed: " + t.getMessage());
        }
    }

    private void init() {
        if (mapView == null) {
            return;
        }
        tencentMap = mapView.getMap();
        uiSettings = tencentMap != null ? tencentMap.getUiSettings() : null;

        // 左上角小罗盘（点击回正北）
        if (uiSettings != null) {
            uiSettings.setCompassEnabled(true);
            uiSettings.setZoomControlsEnabled(false);
            uiSettings.setMyLocationButtonEnabled(true);
        }

        // 严格按照官方 LocationLayerActivity/LocationPointActivity 模式：
        // 分步链式 setMyLocationConfig，每一步保留前一步状态
        // 参考官方 TencentMapDemo: location/LocationLayerActivity.java#initLocation
        if (tencentMap != null) {
            try {
                // 第 1 步：只设 LocationSource
                tencentMap.setMyLocationConfig(MyLocationConfig.newBuilder()
                        .setLocationSource(this)
                        .build());
                // 第 2 步：保留第 1 步，加 MyLocationEnabled
                tencentMap.setMyLocationConfig(MyLocationConfig.newBuilder(tencentMap.getMyLocationConfig())
                        .setMyLocationEnabled(true)
                        .build());
                // 第 3 步：保留前两步，加 Style
                tencentMap.setMyLocationConfig(MyLocationConfig.newBuilder(tencentMap.getMyLocationConfig())
                        .setMyLocationStyle(buildMyLocationStyle())
                        .build());
                // 第 4 步：保留前三步，加 LocationClickListener
                tencentMap.setMyLocationConfig(MyLocationConfig.newBuilder(tencentMap.getMyLocationConfig())
                        .setMyLocationClickListener(latLng -> {
                            Log.d(TAG, "MyLocation clicked: " + latLng);
                            return true;
                        })
                        .build());
            } catch (Throwable t) {
                Log.e(TAG, "setMyLocationConfig failed: " + t.getMessage(), t);
            }
        }

        // 初始化腾讯定位 SDK
        initTencentLocation();
        // 主动启动定位（不依赖 SDK 回调，6.11.0 是懒加载的）
        startTencentLocation();
    }

    /**
     * 初始化腾讯定位 SDK
     */
    private void initTencentLocation() {
        try {
            locationManager = TencentLocationManager.getInstance(appContext);
            // 坐标系：GCJ-02（腾讯地图专用）
            locationManager.setCoordinateType(TencentLocationManager.COORDINATE_TYPE_GCJ02);
            // 创建定位请求（3 秒一次，可调）
            locationRequest = TencentLocationRequest.create();
            locationRequest.setInterval(3000);
            // 关键：必须启用方向传感器，tencentLocation.getDirection() 才有值
            // 不启用 direction，罗盘组件不旋转
            locationRequest.setAllowDirection(true);
            Log.d(TAG, "[NAV] initTencentLocation ok, manager=" + locationManager + ", request=" + locationRequest);
        } catch (Throwable t) {
            Log.e(TAG, "initTencentLocation failed: " + t.getMessage(), t);
        }
    }

    /**
     * 构造定位样式（包含 6.11.0 新增的定位标罗盘 + 导航引导线）
     * 参考官方 TencentMapDemo: location/LocationPointActivity.java#setLocMarkerStyle
     */
    private MyLocationStyle buildMyLocationStyle() {
        MyLocationStyle style = new MyLocationStyle();
        // 6.11.0 官方推荐的车头朝上罗盘模式
        // 罗盘模式：整张地图随设备方向旋转，车头（定位点）始终朝上
        // LOCATION_TYPE_MAP_ROTATE_NO_CENTER = 模式 3
        // 这是 6.11.0 真正的"罗盘模式"（百度/高德导航的标准）
        style.myLocationType(MyLocationStyle.LOCATION_TYPE_MAP_ROTATE_NO_CENTER);
        style.strokeColor(0xFF1AAD19);
        style.fillColor(0x5500AAFF);
        style.strokeWidth(2);

        // 6.11.0 新增：定位标罗盘
        // 模式 3 下，因为地图已经在转，定位点本身是固定的，
        // 可以在定位点上加一个小罗盘（指向设备方向的反方向，即地图"北"）
        try {
            BitmapDescriptor centerIcon = BitmapDescriptorFactory.fromAsset(
                    "locationcompass/day_compass_direction_compass@2x.png");
            BitmapDescriptor[] directionIcons = new BitmapDescriptor[] {
                    BitmapDescriptorFactory.fromAsset("locationcompass/day_compass_direction_east@2x.png"),
                    BitmapDescriptorFactory.fromAsset("locationcompass/day_compass_direction_south@2x.png"),
                    BitmapDescriptorFactory.fromAsset("locationcompass/day_compass_direction_west@2x.png"),
                    BitmapDescriptorFactory.fromAsset("locationcompass/day_compass_direction_north@2x.png")
            };
            LocationCompass compass = new LocationCompass(centerIcon, directionIcons);
            style.setLocationCompass(compass);
        } catch (Throwable t) {
            Log.d(TAG, "setLocationCompass skipped: " + t.getMessage());
        }

        // 6.11.0 新增：导航引导线（定位点 → 地图中心）
        // 模式 3 下定位点不居中，引导线特别有用
        try {
            LocationNavigationGravityline line = new LocationNavigationGravityline(
                    15f, Color.argb(100, 255, 0, 0), new LatLng(39.984066, 116.307548));
            style.setLocationNavigationGravityline(line);
        } catch (Throwable t) {
            Log.d(TAG, "setLocationNavigationGravityline skipped: " + t.getMessage());
        }

        return style;
    }

    // ========== LocationSource 接口（被 SDK 调用） ==========

    @Override
    public void activate(OnLocationChangedListener onLocationChangedListener) {
        Log.d(TAG, "[NAV] activate called");
        this.locationListener = onLocationChangedListener;
        // 当 SDK 激活定位源时，启动腾讯定位
        startTencentLocation();
    }

    @Override
    public void deactivate() {
        Log.d(TAG, "[NAV] deactivate called");
        this.locationListener = null;
        stopTencentLocation();
    }

    private boolean locationStarted = false;
    private float lastCameraBearing = -1f;

    private void startTencentLocation() {
        if (locationManager == null || locationRequest == null) {
            Log.w(TAG, "[NAV] startTencentLocation skipped: manager/request null");
            return;
        }
        if (locationStarted) {
            Log.d(TAG, "[NAV] startTencentLocation: already started, skip");
            return;
        }
        try {
            int err = locationManager.requestLocationUpdates(locationRequest, this, Looper.myLooper());
            Log.d(TAG, "[NAV] requestLocationUpdates returned err=" + err);
            switch (err) {
                case 1:
                    Log.e(TAG, "TencentLocation: 设备缺少使用腾讯定位服务需要的基本条件");
                    break;
                case 2:
                    Log.e(TAG, "TencentLocation: manifest 中配置的 key 不正确");
                    break;
                case 3:
                    Log.e(TAG, "TencentLocation: 自动加载libtencentloc.so失败");
                    break;
                default:
                    Log.d(TAG, "TencentLocation: requestLocationUpdates ok, err=" + err);
                    locationStarted = true;
                    break;
            }
        } catch (Throwable t) {
            Log.e(TAG, "requestLocationUpdates failed: " + t.getMessage(), t);
        }
    }

    private void stopTencentLocation() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
                locationStarted = false;
            } catch (Throwable t) {
                Log.e(TAG, "removeUpdates failed: " + t.getMessage());
            }
        }
    }

    // ========== TencentLocationListener 接口（核心：这里获取设备方向） ==========

    @Override
    public void onLocationChanged(TencentLocation tencentLocation, int i, String s) {
        if (i != TencentLocation.ERROR_OK || tencentLocation == null) {
            Log.w(TAG, "onLocationChanged error: code=" + i + ", msg=" + s);
            return;
        }
        if (locationListener == null) {
            return;
        }

        // 构造 Android 标准 Location，喂给腾讯地图 SDK
        Location location = new Location(tencentLocation.getProvider());
        location.setLatitude(tencentLocation.getLatitude());
        location.setLongitude(tencentLocation.getLongitude());
        location.setAccuracy(tencentLocation.getAccuracy());
        location.setTime(tencentLocation.getTime());
        // ⭐ 关键：把腾讯定位的方向角传给 SDK，触发罗盘旋转
        // 优先用 getDirection（来自传感器，包含静止时的设备朝向）
        // getBearing 是 GPS 移动方向，静止时为 0
        float direction = (float) tencentLocation.getDirection();
        float bearing = (float) tencentLocation.getBearing();
        Log.v(TAG, "[NAV] direction=" + direction + ", bearing=" + bearing);
        // 用传感器方向（即使为 0 也要传，让 SDK 知道这是有效值）
        if (!Float.isNaN(direction) && direction >= 0) {
            location.setBearing(direction);
        } else if (!Float.isNaN(bearing) && bearing >= 0) {
            location.setBearing(bearing);
        }
        locationListener.onLocationChanged(location);

        // 6.11.0 罗盘模式加速：direction 变化时主动更新 camera bearing
        // 模式 3 (MAP_ROTATE_NO_CENTER) 静止时不会主动旋转，
        // 这里手动推 camera 触发旋转（不是自己实现罗盘）
        if (tencentMap != null && !Float.isNaN(direction) && direction >= 0) {
            if (Math.abs(direction - lastCameraBearing) > 0.5f) {
                try {
                    tencentMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                            new CameraPosition(
                                    tencentMap.getCameraPosition().target,
                                    tencentMap.getCameraPosition().zoom,
                                    tencentMap.getCameraPosition().tilt,
                                    direction
                            )
                    ));
                    lastCameraBearing = direction;
                    Log.d(TAG, "[NAV] camera bearing updated to " + direction);
                } catch (Throwable t) {
                    Log.e(TAG, "camera bearing update failed: " + t.getMessage());
                }
            }
        }
    }

    @Override
    public void onStatusUpdate(String s, int i, String s1) {
        Log.v(TAG, "Status update: " + s + " code=" + i + " desc=" + s1);
    }

    // ========== 业务方法 ==========

    /**
     * 绘制路线（GCJ-02 坐标）
     */
    public void drawRoute(List<LatLng> points) {
        if (tencentMap == null || points == null || points.isEmpty()) {
            return;
        }
        if (routePolyline != null) {
            routePolyline.remove();
            routePolyline = null;
        }
        PolylineOptions polylineOptions = new PolylineOptions()
                .addAll(points)
                .width(15)
                .color(0xFF00AAFF)
                .borderWidth(2)
                .borderColor(0xFF0088CC);
        routePolyline = tencentMap.addPolyline(polylineOptions);

        try {
            LatLngBounds.Builder builder = new LatLngBounds.Builder();
            for (LatLng p : points) {
                builder.include(p);
            }
            LatLngBounds bounds = builder.build();
            CameraUpdate update = CameraUpdateFactory.newLatLngBounds(bounds, 100);
            tencentMap.animateCamera(update);
        } catch (Exception e) {
            Log.e(TAG, "drawRoute bounds failed: " + e.getMessage());
        }
    }

    /**
     * 外部 GPS 数据推送（备用：网络模式或自定义 GPS）
     * 如果 TencentLocation 不可用，外部可以调用此方法推送位置
     */
    public void updateMyLocation(double gcjLat, double gcjLon, float bearing) {
        if (tencentMap == null || locationListener == null) return;
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
    }

    public boolean isCompassMode() {
        return isCompassMode;
    }

    /**
     * 添加自定义标记
     */
    public Marker addMarker(LatLng position, String title, BitmapDescriptor icon) {
        if (tencentMap == null) return null;
        MarkerOptions options = new MarkerOptions().position(position).title(title);
        if (icon != null) options.icon(icon);
        return tencentMap.addMarker(options);
    }

    /**
     * 移动地图相机
     */
    public void moveCamera(double gcjLat, double gcjLon, float zoom) {
        if (tencentMap == null) return;
        tencentMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(gcjLat, gcjLon), zoom));
    }

    // ========== 生命周期 ==========

    public void onResume() {
        if (mapView != null) mapView.onResume();
    }

    public void onPause() {
        if (mapView != null) mapView.onPause();
    }

    public void onDestroy() {
        stopTencentLocation();
        if (tencentMap != null) {
            try {
                tencentMap.setMyLocationEnabled(false);
            } catch (Throwable ignored) {
            }
        }
        if (mapView != null) mapView.onDestroy();
    }
}
