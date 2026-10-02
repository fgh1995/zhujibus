package org.zjfgh.zhujibus;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

public class PermissionUtils {
    public static final int REQUEST_LOCATION_PERMISSION = 1001;
    public static final int REQUEST_STORAGE_PERMISSION = 1002;

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

    /**
     * 适配各安卓版本的「外部存储读取」权限请求。
     * <p>Android 13（API 33）及以上使用 {@link Manifest.permission#READ_MEDIA_AUDIO}；
     * 低版本使用 {@link Manifest.permission#READ_EXTERNAL_STORAGE}（manifest 中已限定 maxSdkVersion=32）。
     * <p>注意：自定义语音包目录实际通过 SAF（ACTION_OPEN_DOCUMENT_TREE）授权访问，
     * 该方式自带持久化访问能力、不依赖此权限；此处请求仅为满足「存储读取授权」诉求，
     * 即使被拒也不影响目录选择（SAF 仍可读写所选目录）。
     */
    public static void ensureStoragePermission(FragmentActivity activity) {
        if (activity == null) return;
        String permission = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, new String[]{permission}, REQUEST_STORAGE_PERMISSION);
        }
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