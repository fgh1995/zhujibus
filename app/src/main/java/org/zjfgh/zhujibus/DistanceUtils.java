package org.zjfgh.zhujibus;

import java.util.Locale;

public class DistanceUtils {

    /**
     * 将米转换为友好的距离字符串
     *
     * @param distanceInMeters 距离（单位：米）
     * @return 格式化后的字符串，如 "150米" 或 "2.3公里"
     */
    public static String formatDistance(double distanceInMeters) {
        if (distanceInMeters < 1000) {
            // 不足1公里，显示米
            return String.format(Locale.getDefault(), "%.0f米", distanceInMeters);
        } else {
            // 超过1公里，显示公里（保留1位小数）
            return String.format(Locale.getDefault(), "%.1f公里", distanceInMeters / 1000);
        }
    }
}
