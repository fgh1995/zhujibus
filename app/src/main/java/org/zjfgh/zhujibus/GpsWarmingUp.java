package org.zjfgh.zhujibus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

public class GpsWarmingUp {

    private static LocationManager locationManager;
    private static LocationListener locationListener;
    private static boolean isWarmingUp = false;
    private static Location lastKnownLocation;
    private static long lastLocationTime = 0;
    private static final List<LocationListener> listeners = new ArrayList<>();
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

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000, 10, locationListener, Looper.getMainLooper());
        isWarmingUp = true;
    }

    public static void stopWarmingUp() {
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
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

    public static void addListener(LocationListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(LocationListener listener) {
        listeners.remove(listener);
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