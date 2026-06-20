package org.zjfgh.zhujibus;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.RequiresPermission;

/**
 * GPS / 网络信号指示器管理
 * <p>
 * 图标约定：
 *   - GPS 成功定位（{@link #GPS_OK}）→ {@code icon_gps_signal}
 *   - GPS 失败 / 等待定位           → {@code icon_gps_not_signal}
 *   - 网络信号 0~5 共 6 档           → {@code icon_network_0 ~ icon_network_5}
 *     * 0 = 无网络
 *     * 1~5 = 强度由弱到强（5 格满）
 */
public final class SignalIndicatorManager {

    private static final String TAG = "SignalIndicator";

    private SignalIndicatorManager() {}

    // ---- GPS 状态 ----
    public static final int GPS_NO_SIGNAL = 0;
    public static final int GPS_OK = 1;

    // ---- 网络信号档位（0~5）----
    public static final int NET_LEVEL_0 = 0;
    public static final int NET_LEVEL_1 = 1;
    public static final int NET_LEVEL_2 = 2;
    public static final int NET_LEVEL_3 = 3;
    public static final int NET_LEVEL_4 = 4;
    public static final int NET_LEVEL_5 = 5;

    /** GPS 状态有效时间窗（毫秒）。超过这个时间没收到成功定位视为"无信号"。 */
    public static final long GPS_OK_TIMEOUT_MS = 5_000L;

    /**
     * 更新 GPS 信号图标
     *
     * @param view       目标 ImageView（id = icon_gps_signal）
     * @param gpsOk      true = 已定位，false = 等待 / 失败
     */
    public static void setGpsSignal(ImageView view, boolean gpsOk) {
        if (view == null) return;
        int res = gpsOk ? R.drawable.icon_gps_signal : R.drawable.icon_gps_not_signal;
        if (view.getTag(R.id.icon_gps_signal) instanceof Integer
                && (Integer) view.getTag(R.id.icon_gps_signal) == res) {
            return; // 已经是这个图标，不重设
        }
        view.setImageResource(res);
        view.setTag(R.id.icon_gps_signal, res);
    }

    /**
     * 根据 {@code lastGpsSuccessMs}（最近一次成功定位的时间戳，毫秒）
     * 自动判断是否仍在"信号好"区间，更新 GPS 图标。
     */
    public static void setGpsSignalByTime(ImageView view, long lastGpsSuccessMs) {
        boolean ok = lastGpsSuccessMs > 0
                && (System.currentTimeMillis() - lastGpsSuccessMs) <= GPS_OK_TIMEOUT_MS;
        setGpsSignal(view, ok);
    }

    /**
     * 更新网络信号图标
     *
     * @param view   目标 ImageView（id = icon_network_signal）
     * @param level  档位 0~5，超出范围会被截断
     */
    public static void setNetworkSignal(ImageView view, int level) {
        if (view == null) return;
        int clamped = Math.max(NET_LEVEL_0, Math.min(NET_LEVEL_5, level));
        int res;
        switch (clamped) {
            case NET_LEVEL_0: res = R.drawable.icon_network_0; break;
            case NET_LEVEL_1: res = R.drawable.icon_network_1; break;
            case NET_LEVEL_2: res = R.drawable.icon_network_2; break;
            case NET_LEVEL_3: res = R.drawable.icon_network_3; break;
            case NET_LEVEL_4: res = R.drawable.icon_network_4; break;
            case NET_LEVEL_5:
            default:          res = R.drawable.icon_network_5; break;
        }
        if (view.getTag(R.id.icon_network_signal) instanceof Integer
                && (Integer) view.getTag(R.id.icon_network_signal) == res) {
            return; // 已经是这个图标，不重设
        }
        view.setImageResource(res);
        view.setTag(R.id.icon_network_signal, res);
    }

    /**
     * 读取当前网络信号档位（0~5）
     * <p>
     * 不同传输类型用不同 API（不能用同一个 {@code NetworkCapabilities.getSignalStrength()}
     * 兜底——很多 ROM 上它对蜂窝返回 0，导致永远显示 1 格）：
     * <ul>
     *   <li>无网络 / 无 INTERNET 能力 → 0</li>
     *   <li>Wi-Fi：{@link WifiManager#getConnectionInfo()}.getRssi() 直接读 dBm</li>
     *   <li>蜂窝：{@link TelephonyManager#getSignalStrength()}.getLevel()（API 23+），
     *       和系统状态栏用的是同一套</li>
     *   <li>其他：退回 {@link NetworkCapabilities#getSignalStrength()}</li>
     * </ul>
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    public static int getNetworkSignalLevel(Context context) {
        if (context == null) return NET_LEVEL_0;
        Context app = context.getApplicationContext();

        ConnectivityManager cm = (ConnectivityManager)
                app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return NET_LEVEL_0;

        Network active = cm.getActiveNetwork();
        if (active == null) return NET_LEVEL_0;

        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        if (caps == null) return NET_LEVEL_0;
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NET_LEVEL_0;

        // ---- Wi-Fi：直接读 RSSI（dBm）----
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            int level = getWifiSignalLevel(app);
            Log.d(TAG, "Wi-Fi signal level: " + level);
            return level;
        }

        // ---- 蜂窝：用系统 SignalStrength.getLevel() ----
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            int level = getCellularSignalLevel(app);
            Log.d(TAG, "Cellular signal level: " + level);
            return level;
        }

        // ---- 其他传输（VPN / 以太网 / 低功耗蓝牙等）----
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int raw = caps.getSignalStrength();
            if (raw == Integer.MIN_VALUE) return NET_LEVEL_3;
            if (raw <= 0) return NET_LEVEL_1;
            if (raw >= 4) return NET_LEVEL_5;
            return raw + 1;
        }
        return NET_LEVEL_3;
    }

    /**
     * Wi-Fi 信号档位（0~5）
     * <p>
     * 直接读 RSSI（dBm），按 Android 系统状态栏的阈值换算：
     * <pre>
     *   ≥ -55 dBm → 5 格（极强）
     *   ≥ -67 dBm → 4 格（强）
     *   ≥ -70 dBm → 3 格（良好）
     *   ≥ -80 dBm → 2 格（一般）
     *   其余       → 1 格（弱）
     *   异常值     → 3 格（fallback）
     * </pre>
     */
    @SuppressLint("MissingPermission")
    private static int getWifiSignalLevel(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return NET_LEVEL_3;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return NET_LEVEL_3;
            int rssi = info.getRssi();           // dBm
            if (rssi < -127 || rssi >= 0) return NET_LEVEL_3;  // 无效
            if (rssi >= -55) return NET_LEVEL_5;
            if (rssi >= -67) return NET_LEVEL_4;
            if (rssi >= -70) return NET_LEVEL_3;
            if (rssi >= -80) return NET_LEVEL_2;
            return NET_LEVEL_1;
        } catch (Throwable t) {
            Log.w(TAG, "getWifiSignalLevel failed: " + t.getMessage());
            return NET_LEVEL_3;
        }
    }

    /**
     * 蜂窝信号档位（0~5）
     * <p>
     * 优先用 {@link TelephonyManager#getSignalStrength()}.getLevel()（API 23+），
     * 这是系统状态栏用的同一套。API < 23 或读取失败时退回 NetworkCapabilities。
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = {Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_NETWORK_STATE})
    private static int getCellularSignalLevel(Context context) {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return NET_LEVEL_3;

            SignalStrength ss = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    ss = tm.getSignalStrength();
                } catch (Throwable t) {
                    Log.w(TAG, "TelephonyManager.getSignalStrength failed: " + t.getMessage());
                }
            }
            if (ss != null) {
                int level = ss.getLevel();        // 0~4
                if (level <= 0) return NET_LEVEL_1;
                if (level >= 4) return NET_LEVEL_5;
                return level + 1;
            }

            // API 23 以下 或 读取失败：退回 NetworkCapabilities
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network active = cm.getActiveNetwork();
                    if (active != null) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                        if (caps != null) {
                            int raw = caps.getSignalStrength();
                            if (raw == Integer.MIN_VALUE) return NET_LEVEL_3;
                            if (raw <= 0) return NET_LEVEL_1;
                            if (raw >= 4) return NET_LEVEL_5;
                            return raw + 1;
                        }
                    }
                }
            }
            return NET_LEVEL_3;
        } catch (Throwable t) {
            Log.w(TAG, "getCellularSignalLevel failed: " + t.getMessage());
            return NET_LEVEL_3;
        }
    }
}
