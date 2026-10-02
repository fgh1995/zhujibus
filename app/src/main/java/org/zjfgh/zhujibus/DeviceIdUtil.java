package org.zjfgh.zhujibus;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 设备/安装标识工具。
 *
 * <p>部分 Android 设备（刷机、定制 ROM、部分平板）返回的 {@link Settings.Secure#ANDROID_ID}
 * 为全 0（"0000000000000000"），若直接用作安装 ID，会导致大量设备共用同一标识、互相覆盖。
 * 本工具在 ANDROID_ID 不可用（空 / 异常 / 全 0）时，组合「持久化随机 UUID + 硬件指纹」
 * 生成一个稳定且唯一的标识，保证每台安装都有区分度。</p>
 */
public final class DeviceIdUtil {

    private static final String PREFS_NAME = "device_id_prefs";
    private static final String KEY_UUID = "uuid";

    private DeviceIdUtil() {
    }

    /** 返回稳定的设备标识：64 位十六进制 SHA-256 摘要 */
    public static String getDeviceId(Context context) {
        StringBuilder raw = new StringBuilder();
        String androidId = getRawAndroidId(context);
        if (androidId != null) {
            raw.append("aid:").append(androidId).append("|");
        }
        // 硬件指纹：同机型相同，配合 uuid 可区分不同安装
        raw.append("hw:")
                .append(Build.BRAND).append(",")
                .append(Build.MODEL).append(",")
                .append(Build.BOARD).append(",")
                .append(Build.DEVICE).append(",")
                .append(Build.MANUFACTURER).append(",")
                .append(Build.PRODUCT)
                .append("|");
        // 持久化随机 UUID：每「应用安装」生成一个（应用数据被清除后才会变）
        raw.append("uuid:").append(getPersistentUuid(context));
        return sha256Hex(raw.toString());
    }

    /** 取出并缓存一个持久化 UUID（SharedPreferences） */
    private static String getPersistentUuid(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String uuid = sp.getString(KEY_UUID, null);
            if (uuid == null) {
                uuid = UUID.randomUUID().toString();
                sp.edit().putString(KEY_UUID, uuid).apply();
            }
            return uuid;
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    /** 获取 ANDROID_ID；为空 / 异常 / 全 0 时返回 null */
    private static String getRawAndroidId(Context context) {
        try {
            String id = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id == null || id.isEmpty() || isAllZeros(id)) return null;
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    /** 判断是否为全 0 / 空白（含 "0000000000000000" 这类占位值） */
    private static boolean isAllZeros(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '0' && c != ' ' && c != '-' && c != '\u0000') return false;
        }
        return true;
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
