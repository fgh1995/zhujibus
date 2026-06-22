package org.zjfgh.zhujibus;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;

import java.util.ArrayList;
import java.util.List;

/**
 * GPS 定位管理类（使用高德定位SDK）
 * <p>
 * 统一使用高德定位SDK进行定位，替代系统GPS。
 * 高德定位返回GCJ-02坐标，与高德地图一致，无需转换。
 * </p>
 */
public class GpsWarmingUp {

    private static final String TAG = "GpsWarmingUp";

    public interface SatelliteCountListener {
        void onSatelliteCountChanged(int usedCount, int totalCount);
    }

    // 高德定位 SDK
    private static AMapLocationClient locationClient;
    private static AMapLocationClientOption locationOption;
    private static boolean isWarmingUp = false;

    // 由后台 HandlerThread 写入、主线程读取，必须 volatile
    private static volatile Location lastKnownLocation;
    private static volatile long lastLocationTime = 0;
    private static volatile int usedSatelliteCount = 0;
    private static volatile int totalSatelliteCount = 0;
    private static volatile float lastAccuracy = 0;
    private static volatile float lastSpeed = 0;
    private static volatile float lastBearing = 0;

    private static final List<LocationListener> listeners = new ArrayList<>();
    private static final List<SatelliteCountListener> satelliteListeners = new ArrayList<>();
    private static Handler mainHandler;

    // 后台线程：用于处理定位数据，避免阻塞主线程
    private static HandlerThread gpsHandlerThread;
    private static volatile Looper gpsLooper;
    private static Handler gpsHandler;

    /**
     * 启动高德定位
     */
    public static void startWarmingUp(Context context) {
        if (isWarmingUp) {
            return;
        }

        mainHandler = new Handler(Looper.getMainLooper());

        // 启动后台 HandlerThread
        if (gpsHandlerThread == null || !gpsHandlerThread.isAlive()) {
            gpsHandlerThread = new HandlerThread("GpsWarmingUp-Handler");
            gpsHandlerThread.start();
            gpsLooper = gpsHandlerThread.getLooper();
            gpsHandler = new Handler(gpsLooper);
        }

        // 初始化高德定位隐私协议
        try {
            AMapLocationClient.updatePrivacyShow(context, true, true);
            AMapLocationClient.updatePrivacyAgree(context, true);
        } catch (Throwable t) {
            Log.e(TAG, "AMapLocationClient privacy failed: " + t.getMessage());
        }

        // 初始化高德定位客户端
        try {
            locationClient = new AMapLocationClient(context.getApplicationContext());
            locationOption = new AMapLocationClientOption();

            // 高精度模式：GPS + 网络定位
            locationOption.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);

            // 连续定位，1秒一次（与原系统GPS频率一致）
            locationOption.setInterval(1000);

            // 关闭传感器融合，避免设备方向影响bearing
            locationOption.setSensorEnable(false);

            // 不需要地址信息
            locationOption.setNeedAddress(false);

            // 单次定位关闭
            locationOption.setOnceLocation(false);

            // 允许后台定位
            locationOption.setLocationCacheEnable(true);

            // 获取卫星信息
            locationOption.setGpsFirst(true);

            locationClient.setLocationOption(locationOption);
            locationClient.setLocationListener(new AMapLocationListener() {
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

                    // 在后台线程处理定位数据
                    gpsHandler.post(() -> processLocation(aMapLocation));
                }
            });

            locationClient.startLocation();
            Log.d(TAG, "高德定位启动成功，mode=Hight_Accuracy, interval=1000ms");
            isWarmingUp = true;

        } catch (Throwable t) {
            Log.e(TAG, "startWarmingUp failed: " + t.getMessage(), t);
        }
    }

    /**
     * 处理定位数据（在后台线程执行）
     */
    private static void processLocation(AMapLocation aMapLocation) {
        // 构造 Android 标准 Location
        Location location = new Location(aMapLocation.getProvider());
        location.setLatitude(aMapLocation.getLatitude());
        location.setLongitude(aMapLocation.getLongitude());
        location.setAccuracy(aMapLocation.getAccuracy());
        location.setTime(aMapLocation.getTime());
        location.setSpeed(aMapLocation.getSpeed());
        location.setBearing(aMapLocation.getBearing());

        // 更新缓存
        lastKnownLocation = location;
        lastLocationTime = System.currentTimeMillis();
        lastAccuracy = aMapLocation.getAccuracy();
        lastSpeed = aMapLocation.getSpeed();
        lastBearing = aMapLocation.getBearing();

        // 高德定位SDK不直接提供卫星数量，模拟估算
        // 根据定位精度和定位类型估算卫星数量
        int estimatedSatellites = estimateSatelliteCount(aMapLocation);
        usedSatelliteCount = estimatedSatellites;
        totalSatelliteCount = estimatedSatellites + 2; // 估算总数略多于使用数

        Log.d(TAG, String.format("定位成功: lat=%.6f, lng=%.6f, acc=%.1fm, speed=%.1f, bearing=%.1f, satellites≈%d",
                aMapLocation.getLatitude(),
                aMapLocation.getLongitude(),
                aMapLocation.getAccuracy(),
                aMapLocation.getSpeed(),
                aMapLocation.getBearing(),
                estimatedSatellites));

        // 通知监听器
        notifyListeners(location);
        notifySatelliteListeners(usedSatelliteCount, totalSatelliteCount);
    }

    /**
     * 根据定位精度估算卫星数量
     * 高德定位SDK不直接提供卫星数量，这里根据精度估算
     */
    private static int estimateSatelliteCount(AMapLocation aMapLocation) {
        float accuracy = aMapLocation.getAccuracy();
        String provider = aMapLocation.getProvider();

        // 如果是GPS定位（高精度），根据精度估算卫星数
        if (provider != null && provider.contains("gps")) {
            if (accuracy <= 5f) {
                return 8; // 高精度，卫星多
            } else if (accuracy <= 10f) {
                return 6;
            } else if (accuracy <= 20f) {
                return 4;
            } else {
                return 3; // 低精度，卫星少
            }
        }

        // 网络定位，卫星数为0
        return 0;
    }

    /**
     * 停止高德定位
     */
    public static void stopWarmingUp() {
        if (locationClient != null) {
            locationClient.stopLocation();
            Log.d(TAG, "高德定位已停止");
        }
        if (gpsHandlerThread != null) {
            gpsHandlerThread.quitSafely();
            gpsHandlerThread = null;
        }
        gpsLooper = null;
        gpsHandler = null;
        isWarmingUp = false;
    }

    public static boolean isWarmingUp() {
        return isWarmingUp;
    }

    public static Location getLastKnownLocation() {
        return lastKnownLocation;
    }

    public static long getLastLocationTime() {
        return lastLocationTime;
    }

    public static int getSatelliteCount() {
        return usedSatelliteCount;
    }

    public static int getTotalSatelliteCount() {
        return totalSatelliteCount;
    }

    public static float getLastAccuracy() {
        return lastAccuracy;
    }

    public static float getLastSpeed() {
        return lastSpeed;
    }

    public static float getLastBearing() {
        return lastBearing;
    }

    public static void addListener(LocationListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(LocationListener listener) {
        listeners.remove(listener);
    }

    public static void addSatelliteListener(SatelliteCountListener listener) {
        if (!satelliteListeners.contains(listener)) {
            satelliteListeners.add(listener);
        }
    }

    public static void removeSatelliteListener(SatelliteCountListener listener) {
        satelliteListeners.remove(listener);
    }

    private static void notifySatelliteListeners(int usedCount, int totalCount) {
        for (SatelliteCountListener listener : satelliteListeners) {
            listener.onSatelliteCountChanged(usedCount, totalCount);
        }
    }

    private static void notifyListeners(Location location) {
        for (LocationListener listener : listeners) {
            listener.onLocationChanged(location);
        }
    }

    public static void updateLocation(Location location) {
        lastKnownLocation = location;
        lastLocationTime = System.currentTimeMillis();
        notifyListeners(location);
    }

    /**
     * 把任务 post 到 GPS 后台线程执行
     */
    public static void postToGpsThread(Runnable r) {
        if (gpsHandlerThread == null || !gpsHandlerThread.isAlive() || gpsLooper == null) {
            r.run();
            return;
        }
        gpsHandler.post(r);
    }
}