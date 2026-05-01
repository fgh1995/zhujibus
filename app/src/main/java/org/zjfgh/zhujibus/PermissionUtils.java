package org.zjfgh.zhujibus;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class PermissionUtils {
    public static final int REQUEST_LOCATION_PERMISSION = 1001;

    private static PermissionCallback pendingLocationCallback;

    public interface PermissionCallback {
        void onPermissionGranted();
        void onPermissionDenied();
    }

    public static boolean hasLocationPermission(Context context) {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public static void requestLocationPermission(AppCompatActivity activity, PermissionCallback callback) {
        if (hasLocationPermission(activity)) {
            if (callback != null) {
                callback.onPermissionGranted();
            }
            return;
        }
        pendingLocationCallback = callback;
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                REQUEST_LOCATION_PERMISSION);
    }

    public static void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingLocationCallback != null) {
                    pendingLocationCallback.onPermissionGranted();
                    pendingLocationCallback = null;
                }
            } else {
                if (pendingLocationCallback != null) {
                    pendingLocationCallback.onPermissionDenied();
                    pendingLocationCallback = null;
                }
            }
        }
    }
}