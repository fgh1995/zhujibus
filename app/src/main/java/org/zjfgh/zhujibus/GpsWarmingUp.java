package org.zjfgh.zhujibus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

public class GpsWarmingUp {

    public interface SatelliteCountListener {
        void onSatelliteCountChanged(int usedCount, int totalCount);
    }

    private static LocationManager locationManager;
    private static LocationListener locationListener;
    private static Object gnssCallback;
    private static boolean isWarmingUp = false;
    // 由后台 HandlerThread 写入、主线程读取，必须 volatile
    private static volatile Location lastKnownLocation;
    private static volatile long lastLocationTime = 0;
    private static volatile int usedSatelliteCount = 0;
    private static volatile int totalSatelliteCount = 0;
    private static final List<LocationListener> listeners = new ArrayList<>();
    private static final List<SatelliteCountListener> satelliteListeners = new ArrayList<>();
    private static Handler mainHandler;

    // 后台线程：所有 onLocationChanged / onSatelliteStatusChanged 都跑在这里，
    // 避免 GPS 1Hz 刷新时把高耗时计算挤到 UI 线程。
    private static HandlerThread gpsHandlerThread;
    private static volatile Looper gpsLooper;

    @SuppressLint("MissingPermission")
    public static void startWarmingUp(Context context) {
        if (isWarmingUp) {
            return;
        }

        mainHandler = new Handler(Looper.getMainLooper());

        if (locationManager == null) {
            locationManager = (LocationManager) context.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        }

        if (locationListener == null) {
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    lastKnownLocation = location;
                    lastLocationTime = System.currentTimeMillis();
                    notifyListeners(location);
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}

                @Override
                public void onProviderEnabled(String provider) {}

                @Override
                public void onProviderDisabled(String provider) {}
            };
        }

        // 启动后台 HandlerThread，专门用于 GPS 回调，避免主线程被耗时计算阻塞
        if (gpsHandlerThread == null || !gpsHandlerThread.isAlive()) {
            gpsHandlerThread = new HandlerThread("GpsWarmingUp-Handler");
            gpsHandlerThread.start();
            gpsLooper = gpsHandlerThread.getLooper();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (gnssCallback == null) {
                gnssCallback = new android.location.GnssStatus.Callback() {
                    @Override
                    public void onSatelliteStatusChanged(android.location.GnssStatus status) {
                        int used = 0;
                        totalSatelliteCount = status.getSatelliteCount();
                        for (int i = 0; i < totalSatelliteCount; i++) {
                            if (status.usedInFix(i)) {
                                used++;
                            }
                        }
                        usedSatelliteCount = used;
                        android.util.Log.d("GpsWarmingUp", "卫星数量: " + used + ", 总卫星: " + totalSatelliteCount);
                        notifySatelliteListeners(used, totalSatelliteCount);
                    }
                };
            }
            // GnssStatus 回调仍交给主线程（卫星数量 UI 更新频率不高且对实时性不敏感）
            locationManager.registerGnssStatusCallback((android.location.GnssStatus.Callback) gnssCallback, mainHandler);
        }

        // 关键改动：使用后台 Looper 注册，1 秒刷新保持不变
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener, gpsLooper);
        isWarmingUp = true;
    }

    public static void stopWarmingUp() {
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        if (locationManager != null && gnssCallback != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                locationManager.unregisterGnssStatusCallback((android.location.GnssStatus.Callback) gnssCallback);
            }
        }
        if (gpsHandlerThread != null) {
            gpsHandlerThread.quitSafely();
            gpsHandlerThread = null;
        }
        gpsLooper = null;
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
     * 把任务 post 到 GPS 后台线程执行，用于在主线程上需要主动触发 GPS 相关计算时
     * （例如刚切到 GPS 模式后用最近一次 location 立即刷新一次 UI），避免重计算阻塞 UI。
     */
    public static void postToGpsThread(Runnable r) {
        if (gpsHandlerThread == null || !gpsHandlerThread.isAlive() || gpsLooper == null) {
            // GPS 后台线程尚未启动时，直接在调用线程执行
            r.run();
            return;
        }
        new Handler(gpsLooper).post(r);
    }
}