package org.zjfgh.zhujibus;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 统一权限申请工具，覆盖 Android 7 (API 24) → 最新 Android 15 (API 36)，
 * 兼容华为 EMUI / 小米 MIUI / OPPO ColorOS / vivo OriginOS / 三星 One UI 等定制 ROM。
 *
 * <p>核心要点：
 * <ul>
 *   <li>普通权限：Manifest 声明即用，无需运行时申请</li>
 *   <li>危险权限（API 23+）：必须运行时申请</li>
 *   <li>Android 10+ Scoped Storage：写入 MediaStore 不需要 WRITE_EXTERNAL_STORAGE</li>
 *   <li>Android 13+ 媒体权限：READ_MEDIA_VIDEO / READ_MEDIA_IMAGES 替代旧存储权限</li>
 *   <li>Android 13+ POST_NOTIFICATIONS：通知权限独立申请</li>
 *   <li>国产 ROM：shouldShowRequestPermissionRationale 不可靠，用 SharedPreferences 计数判断永久拒绝</li>
 *   <li>永久拒绝：引导用户到系统设置页（已适配厂商定制入口）</li>
 * </ul>
 */
public class PermissionUtils {

    private static final String TAG = "PermissionUtils";
    private static final String PREFS_NAME = "permission_state";
    private static final String KEY_PREFIX_DENY_COUNT = "deny_count_";
    private static final int PERMANENT_DENY_THRESHOLD = 2;

    public static final int REQUEST_LOCATION_PERMISSION = 1001;
    public static final int REQUEST_CAMERA_AUDIO_PERMISSION = 1002;
    public static final int REQUEST_MEDIA_VIDEO_PERMISSION = 1003;
    public static final int REQUEST_NOTIFICATION_PERMISSION = 1004;
    public static final int REQUEST_GENERIC_PERMISSION = 1099;

    public interface PermissionCallback {
        void onPermissionGranted();
        void onPermissionDenied();
    }

    public interface MultiPermissionCallback {
        void onAllGranted();
        void onPartialGrant(@NonNull Set<String> granted, @NonNull Set<String> denied);
    }

    @Nullable
    private static PermissionCallback pendingLocationCallback;
    @Nullable
    private static Context pendingLocationContext;
    @Nullable
    private static MultiPermissionCallback pendingMultiCallback;
    @Nullable
    private static Context pendingMultiContext;

    // =================================================================================
    // 权限组常量
    // =================================================================================

    /** 相机 + 麦克风（视频录制必备） */
    public static String[] cameraAndAudio() {
        return new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
        };
    }

    /** 精确定位 + 粗略定位（Android 12+ 必须同时申请） */
    public static String[] location() {
        return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
    }

    /**
     * 保存/读取视频到相册所需权限。
     * - Android 13+：READ_MEDIA_VIDEO（读取），写入自己创建的 MediaStore 记录不需要权限
     * - Android 10-12：READ_EXTERNAL_STORAGE（maxSdk32）
     * - Android 9-：WRITE_EXTERNAL_STORAGE（写入），READ_EXTERNAL_STORAGE（读取）
     */
    public static String[] mediaVideo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.READ_MEDIA_VIDEO};
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        }
        return new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
    }

    /** 通知权限（Android 13+） */
    @Nullable
    public static String[] notifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.POST_NOTIFICATIONS};
        }
        return null;
    }

    // =================================================================================
    // 基础检查
    // =================================================================================

    public static boolean hasLocationPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasPermissions(Context context, @NonNull String... permissions) {
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // =================================================================================
    // 检查权限 + 未授权自动 Toast（按需调用）
    // 返回 true 表示已授权，false 表示未授权（已显示 Toast）
    // =================================================================================

    /** 相机权限检查 + Toast */
    public static boolean checkCameraWithToast(@Nullable Activity activity) {
        if (activity == null) return false;
        if (hasPermissions(activity, Manifest.permission.CAMERA)) return true;
        Toast.makeText(activity, "未授予相机权限，无法使用此功能", Toast.LENGTH_SHORT).show();
        return false;
    }

    /** 麦克风/录音权限检查 + Toast */
    public static boolean checkRecordAudioWithToast(@Nullable Activity activity) {
        if (activity == null) return false;
        if (hasPermissions(activity, Manifest.permission.RECORD_AUDIO)) return true;
        Toast.makeText(activity, "未授予麦克风权限，无法录音", Toast.LENGTH_SHORT).show();
        return false;
    }

    /** 位置权限检查 + Toast */
    public static boolean checkLocationWithToast(@Nullable Activity activity) {
        if (activity == null) return false;
        if (hasLocationPermission(activity)) return true;
        Toast.makeText(activity, "未授予位置权限，无法获取当前位置", Toast.LENGTH_SHORT).show();
        return false;
    }

    /**
     * 写入外部存储权限检查 + Toast。
     * Android 10+ 走 MediaStore 不需要此权限，自动返回 true。
     * Android 9- 需要 WRITE_EXTERNAL_STORAGE。
     */
    public static boolean checkWriteStorageWithToast(@Nullable Activity activity) {
        if (activity == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;  // Scoped Storage
        if (hasPermissions(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)) return true;
        Toast.makeText(activity, "未授予存储权限，无法保存文件到相册", Toast.LENGTH_SHORT).show();
        return false;
    }

    /** 读相册视频权限检查 + Toast（用于分享/选择视频场景） */
    public static boolean checkReadMediaVideoWithToast(@Nullable Activity activity) {
        if (activity == null) return false;
        String perm;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perm = Manifest.permission.READ_MEDIA_VIDEO;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perm = Manifest.permission.READ_EXTERNAL_STORAGE;
        } else {
            perm = Manifest.permission.READ_EXTERNAL_STORAGE;
        }
        if (hasPermissions(activity, perm)) return true;
        Toast.makeText(activity, "未授予相册视频读取权限", Toast.LENGTH_SHORT).show();
        return false;
    }

    /** 通知权限检查 + Toast（Android 13+ 才有意义） */
    public static boolean checkNotificationWithToast(@Nullable Activity activity) {
        if (activity == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        if (hasPermissions(activity, Manifest.permission.POST_NOTIFICATIONS)) return true;
        Toast.makeText(activity, "未授予通知权限，无法显示通知", Toast.LENGTH_SHORT).show();
        return false;
    }

    /** 蓝牙扫描权限检查 + Toast（Android 12+ 才有意义） */
    public static boolean checkBluetoothWithToast(@Nullable Activity activity) {
        if (activity == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        if (hasPermissions(activity,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT)) return true;
        Toast.makeText(activity, "未授予蓝牙权限，无法连接设备", Toast.LENGTH_SHORT).show();
        return false;
    }

    /**
     * 录制视频所需的所有权限（CAMERA + RECORD_AUDIO）检查 + Toast。
     * 用于在录制按钮点击前快速校验。
     */
    public static boolean checkRecordingWithToast(@Nullable Activity activity) {
        if (activity == null) return false;
        boolean cam = hasPermissions(activity, Manifest.permission.CAMERA);
        boolean mic = hasPermissions(activity, Manifest.permission.RECORD_AUDIO);
        if (cam && mic) return true;
        StringBuilder msg = new StringBuilder("录制需要");
        if (!cam) msg.append("相机");
        if (!cam && !mic) msg.append("和");
        if (!mic) msg.append("麦克风");
        msg.append("权限");
        Toast.makeText(activity, msg.toString(), Toast.LENGTH_SHORT).show();
        return false;
    }

    // =================================================================================
    // 位置权限（旧 API 兼容 - MainActivity 使用）
    // =================================================================================

    @SuppressLint("MissingPermission")
    public static void requestLocationPermission(AppCompatActivity activity, PermissionCallback callback) {
        requestLocationPermission(activity, callback, false);
    }

    /**
     * 请求位置权限
     * @param forceShowRationale 是否强制先弹解释对话框
     */
    @SuppressLint("MissingPermission")
    public static void requestLocationPermission(AppCompatActivity activity,
                                                 @Nullable PermissionCallback callback,
                                                 boolean forceShowRationale) {
        if (hasLocationPermission(activity)) {
            if (callback != null) callback.onPermissionGranted();
            return;
        }
        if (forceShowRationale) {
            showRationaleDialog(activity, "需要位置权限以提供公交定位和导航服务", () -> {
                pendingLocationCallback = callback;
                pendingLocationContext = activity;
                ActivityCompat.requestPermissions(activity, location(), REQUEST_LOCATION_PERMISSION);
            });
            return;
        }
        pendingLocationCallback = callback;
        pendingLocationContext = activity;
        ActivityCompat.requestPermissions(activity, location(), REQUEST_LOCATION_PERMISSION);
    }

    // =================================================================================
    // 通用权限申请（新 API）
    // =================================================================================

    /**
     * 请求一组权限。已授权的会被过滤掉，仅申请未授权的。
     */
    public static void requestPermissions(@NonNull Activity activity,
                                          @NonNull String[] permissions,
                                          int requestCode,
                                          @NonNull MultiPermissionCallback callback) {
        List<String> toRequest = new ArrayList<>();
        Set<String> alreadyGranted = new HashSet<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(activity, p) == PackageManager.PERMISSION_GRANTED) {
                alreadyGranted.add(p);
            } else {
                toRequest.add(p);
            }
        }
        if (toRequest.isEmpty()) {
            callback.onAllGranted();
            return;
        }
        pendingMultiCallback = callback;
        pendingMultiContext = activity;
        try {
            ActivityCompat.requestPermissions(activity,
                    toRequest.toArray(new String[0]),
                    requestCode);
        } catch (Throwable t) {
            Log.e(TAG, "requestPermissions failed: " + t.getMessage(), t);
            pendingMultiCallback = null;
            pendingMultiContext = null;
            callback.onPartialGrant(alreadyGranted, new HashSet<>(toRequest));
        }
    }

    // =================================================================================
    // 回调处理
    // =================================================================================

    public static void onRequestPermissionsResult(int requestCode,
                                                  @NonNull String[] permissions,
                                                  @NonNull int[] grantResults) {
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            handleLocationResult(permissions, grantResults);
            return;
        }
        handleGenericResult(requestCode, permissions, grantResults);
    }

    private static void handleLocationResult(@NonNull String[] permissions, @NonNull int[] grantResults) {
        boolean granted = false;
        Context ctx = pendingLocationContext;
        pendingLocationContext = null;
        for (int i = 0; i < grantResults.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                if (ctx != null) recordGranted(ctx, permissions[i]);
            } else {
                if (ctx != null) recordDenied(ctx, permissions[i]);
            }
        }
        if (granted) {
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

    private static void handleGenericResult(int requestCode,
                                            @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        if (pendingMultiCallback == null) return;
        MultiPermissionCallback cb = pendingMultiCallback;
        Context ctx = pendingMultiContext;
        pendingMultiCallback = null;
        pendingMultiContext = null;

        Set<String> granted = new HashSet<>();
        Set<String> denied = new HashSet<>();
        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                granted.add(permissions[i]);
                if (ctx != null) recordGranted(ctx, permissions[i]);
            } else {
                denied.add(permissions[i]);
                if (ctx != null) recordDenied(ctx, permissions[i]);
            }
        }
        if (denied.isEmpty()) {
            cb.onAllGranted();
        } else {
            cb.onPartialGrant(granted, denied);
        }
    }

    private static void clearCallbackState() {
        pendingLocationCallback = null;
        pendingLocationContext = null;
        pendingMultiCallback = null;
        pendingMultiContext = null;
    }

    public static void reset() {
        clearCallbackState();
    }

    // =================================================================================
    // 永久拒绝检测（兼容国产 ROM）
    // =================================================================================

    /**
     * 判断权限是否被"永久拒绝"。
     * Android 官方逻辑：denied + !shouldShowRequestPermissionRationale = 永久拒绝。
     * 但国产 ROM 不可靠，所以用 SharedPreferences 计数辅助：连续拒绝 >=2 次视为永久拒绝。
     */
    public static boolean isPermanentlyDenied(@NonNull Activity activity, @NonNull String permission) {
        if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        boolean officialRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission);
        int denyCount = getDenyCount(activity, permission);
        // 官方：rationale=false + 已拒绝 = 永久拒绝
        // 国产：rationale 不可靠，连续拒绝 >=2 次 = 永久拒绝
        return !officialRationale || denyCount >= PERMANENT_DENY_THRESHOLD;
    }

    private static void recordGranted(Context context, String permission) {
        // 授予后重置计数
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_PREFIX_DENY_COUNT + permission).apply();
    }

    private static void recordDenied(Context context, String permission) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = KEY_PREFIX_DENY_COUNT + permission;
        int count = sp.getInt(key, 0);
        sp.edit().putInt(key, count + 1).apply();
    }

    private static int getDenyCount(Context context, String permission) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_PREFIX_DENY_COUNT + permission, 0);
    }

    // =================================================================================
    // 跳转到应用设置页（兼容国产 ROM）
    // =================================================================================

    /**
     * 跳转到应用权限设置页，按厂商优先级尝试多个 Intent，最后回退到系统标准 Intent。
     */
    public static void openAppSettings(@NonNull Context context) {
        List<Intent> intents = buildAppSettingsIntents(context);
        for (Intent intent : intents) {
            try {
                if (intent.resolveActivity(context.getPackageManager()) != null) {
                    if (context instanceof Activity) {
                        ((Activity) context).startActivity(intent);
                    } else {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    }
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "openAppSettings intent failed: " + intent + ", " + t.getMessage());
            }
        }
        Toast.makeText(context, "无法打开权限设置页，请手动到系统设置中开启", Toast.LENGTH_LONG).show();
    }

    private static List<Intent> buildAppSettingsIntents(Context context) {
        List<Intent> intents = new ArrayList<>();
        String pkg = context.getPackageName();

        // 1. 通用标准 Intent（所有 Android 版本可用）
        Intent standard = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        standard.setData(Uri.fromParts("package", pkg, null));
        standard.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intents.add(standard);

        // 2. 小米 MIUI
        if (isMIUI()) {
            try {
                Intent miui = new Intent("miui.intent.action.APP_PERM_EDITOR");
                miui.putExtra("package_name", pkg);
                miui.setClassName("com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity");
                intents.add(0, miui);
            } catch (Throwable ignored) {}
        }

        // 3. 华为 EMUI / HarmonyOS
        if (isHuawei()) {
            try {
                Intent huawei = new Intent("huawei.intent.action.APP_PERM_EDITOR");
                huawei.setComponent(new ComponentName("com.huawei.systemmanager",
                        "com.huawei.permissionmanager.ui.MainActivity"));
                intents.add(0, huawei);
            } catch (Throwable ignored) {}
        }

        // 4. OPPO ColorOS
        if (isOppo()) {
            try {
                Intent oppo = new Intent();
                oppo.setComponent(new ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.PermissionManagerActivity"));
                intents.add(0, oppo);
            } catch (Throwable ignored) {}
            try {
                Intent oppo2 = new Intent("com.oppo.safe");
                oppo2.putExtra("packageName", pkg);
                intents.add(0, oppo2);
            } catch (Throwable ignored) {}
        }

        // 5. vivo OriginOS / Funtouch
        if (isVivo()) {
            try {
                Intent vivo = new Intent("com.vivo.permissionmanager");
                vivo.setComponent(new ComponentName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.PurviewTabActivity"));
                intents.add(0, vivo);
            } catch (Throwable ignored) {}
        }

        return intents;
    }

    // =================================================================================
    // 厂商识别
    // =================================================================================

    private static boolean isMIUI() {
        return "Xiaomi".equalsIgnoreCase(Build.MANUFACTURER)
                || "Redmi".equalsIgnoreCase(Build.MANUFACTURER)
                || "POCO".equalsIgnoreCase(Build.MANUFACTURER);
    }

    private static boolean isHuawei() {
        String mf = Build.MANUFACTURER;
        return "HUAWEI".equalsIgnoreCase(mf) || "HONOR".equalsIgnoreCase(mf);
    }

    private static boolean isOppo() {
        String mf = Build.MANUFACTURER;
        return "OPPO".equalsIgnoreCase(mf) || "Realme".equalsIgnoreCase(mf) || "OnePlus".equalsIgnoreCase(mf);
    }

    private static boolean isVivo() {
        return "vivo".equalsIgnoreCase(Build.MANUFACTURER) || "iQOO".equalsIgnoreCase(Build.MANUFACTURER);
    }

    // =================================================================================
    // 用户引导对话框
    // =================================================================================

    /**
     * 显示权限解释对话框
     */
    public static void showRationaleDialog(@NonNull Activity activity,
                                           @NonNull String message,
                                           @NonNull Runnable onConfirm) {
        new android.app.AlertDialog.Builder(activity)
                .setTitle("权限申请说明")
                .setMessage(message)
                .setPositiveButton("去开启", (d, w) -> onConfirm.run())
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 显示"权限被永久拒绝"对话框，引导用户去设置
     */
    public static void showPermanentDeniedDialog(@NonNull Activity activity, @NonNull String message) {
        new android.app.AlertDialog.Builder(activity)
                .setTitle("需要手动开启权限")
                .setMessage(message + "\n\n请点击下方按钮前往应用设置页面开启。")
                .setPositiveButton("去设置", (d, w) -> openAppSettings(activity))
                .setNegativeButton("取消", null)
                .setCancelable(false)
                .show();
    }

    /**
     * 一站式：请求权限 + 自动处理拒绝/永久拒绝
     *
     * @return true 已全部授权，false 进入申请流程
     */
    public static boolean ensurePermissions(@NonNull Activity activity,
                                            @NonNull String[] permissions,
                                            int requestCode,
                                            @NonNull String rationaleMessage,
                                            @NonNull Runnable onAllGranted) {
        if (hasPermissions(activity, permissions)) {
            onAllGranted.run();
            return true;
        }
        // 检查是否有永久拒绝
        List<String> permanentlyDenied = new ArrayList<>();
        for (String p : permissions) {
            if (isPermanentlyDenied(activity, p)) {
                permanentlyDenied.add(p);
            }
        }
        if (!permanentlyDenied.isEmpty()) {
            showPermanentDeniedDialog(activity, rationaleMessage);
            return false;
        }
        requestPermissions(activity, permissions, requestCode, new MultiPermissionCallback() {
            @Override
            public void onAllGranted() {
                onAllGranted.run();
            }
            @Override
            public void onPartialGrant(@NonNull Set<String> granted, @NonNull Set<String> denied) {
                // 申请后再次检查是否有永久拒绝
                List<String> stillPermanentlyDenied = new ArrayList<>();
                for (String p : denied) {
                    if (isPermanentlyDenied(activity, p)) {
                        stillPermanentlyDenied.add(p);
                    }
                }
                if (!stillPermanentlyDenied.isEmpty()) {
                    showPermanentDeniedDialog(activity, rationaleMessage);
                } else {
                    Toast.makeText(activity, "部分权限被拒绝，相关功能可能不可用", Toast.LENGTH_LONG).show();
                }
            }
        });
        return false;
    }
}
