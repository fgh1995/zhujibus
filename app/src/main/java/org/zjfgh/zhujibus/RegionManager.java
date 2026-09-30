package org.zjfgh.zhujibus;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 地区选择状态的持久化与管理。
 *
 * <p>保存：- 用户已确认的公交查询地区（adCode/省/市/区）- 最近一次已提示过的地区 adCode（避免同一地区反复弹窗）-
 * 是否处于「人工搜索」模式（用户取消自动选择后开启）。</p>
 */
public class RegionManager {

    private static final String PREFS_NAME = "region_prefs";
    private static final String KEY_ADCODE = "selected_adcode";
    private static final String KEY_PROVINCE = "selected_province";
    private static final String KEY_CITY = "selected_city";
    private static final String KEY_DISTRICT = "selected_district";
    private static final String KEY_PROMPTED_ADCODE = "prompted_adcode";
    private static final String KEY_MANUAL_MODE = "manual_mode";
    private static final String KEY_FORCE_PROMPT = "force_prompt_next";

    private final SharedPreferences sp;

    public RegionManager(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 当前已确认的公交查询地区；未选择则返回 null */
    public BusRegion getSelectedRegion() {
        String adCode = sp.getString(KEY_ADCODE, "");
        if (adCode == null || adCode.isEmpty()) return null;
        return new BusRegion(
                adCode,
                sp.getString(KEY_PROVINCE, ""),
                sp.getString(KEY_CITY, ""),
                sp.getString(KEY_DISTRICT, "")
        );
    }

    public void setSelectedRegion(BusRegion region) {
        if (region == null) {
            sp.edit().remove(KEY_ADCODE).remove(KEY_PROVINCE).remove(KEY_CITY)
                    .remove(KEY_DISTRICT).apply();
            return;
        }
        sp.edit()
                .putString(KEY_ADCODE, region.adCode)
                .putString(KEY_PROVINCE, region.provinceName)
                .putString(KEY_CITY, region.cityName)
                .putString(KEY_DISTRICT, region.districtName)
                .apply();
    }

    /** 最近一次已向用户提示过的地区 adCode（用于去重，避免对同一地区反复弹窗） */
    public String getPromptedAdCode() {
        return sp.getString(KEY_PROMPTED_ADCODE, "");
    }

    public void setPromptedAdCode(String adCode) {
        sp.edit().putString(KEY_PROMPTED_ADCODE, adCode == null ? "" : adCode).apply();
    }

    /** 是否处于人工搜索模式（用户取消自动选择后开启，开启后不再自动弹窗） */
    public boolean isManualMode() {
        return sp.getBoolean(KEY_MANUAL_MODE, false);
    }

    public void setManualMode(boolean manual) {
        sp.edit().putBoolean(KEY_MANUAL_MODE, manual).apply();
    }

    /** 长按重置后置位：下次定位检测时强制重新弹窗提示（跨重启生效） */
    public boolean isForcePromptNext() {
        return sp.getBoolean(KEY_FORCE_PROMPT, false);
    }

    public void setForcePromptNext(boolean v) {
        sp.edit().putBoolean(KEY_FORCE_PROMPT, v).apply();
    }
}
