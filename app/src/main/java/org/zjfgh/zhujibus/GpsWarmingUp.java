package org.zjfgh.zhujibus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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
    private static Location lastKnownLocation;
    private static long lastLocationTime = 0;
    private static int usedSatelliteCount = 0;
    private static int totalSatelliteCount = 0;
    private static final List<LocationListener> listeners = new ArrayList<>();
    private static final List<SatelliteCountListener> satelliteListeners = new ArrayList<>();
    private static Handler mainHandler;

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
            locationManager.registerGnssStatusCallback((android.location.GnssStatus.Callback) gnssCallback, mainHandler);
        }

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener, Looper.getMainLooper());
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
}