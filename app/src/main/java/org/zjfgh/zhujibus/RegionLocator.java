package org.zjfgh.zhujibus;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;

/**
 * 基于高德定位 SDK 的地区解析器。
 *
 * <p>与 {@link GpsWarmingUp} 的区别：GpsWarmingUp 只取坐标(NeedAddress=false)；
 * 本类开启地址解析(NeedAddress=true)，从定位结果中取出 省/市/区 以及 adCode，构造 {@link BusRegion}。</p>
 *
 * <p>仅在「首次定位」或「adCode 发生变化」时回调，避免频繁打扰，也就实现了需求里的
 * "城市变化 → 重新弹出定位并提示"。</p>
 */
public class RegionLocator {

    private static final String TAG = "RegionLocator";

    public interface Callback {
        void onRegionResolved(@NonNull BusRegion region);

        void onError(int code, String msg);
    }

    @android.annotation.SuppressLint("MissingPermission")
    private AMapLocationClient client;
    private BusRegion lastRegion;
    private Callback callback;

    @android.annotation.SuppressLint("MissingPermission")
    public void start(@NonNull Context context, @NonNull Callback cb) {
        this.callback = cb;
        try {
            AMapLocationClient.updatePrivacyShow(context, true, true);
            AMapLocationClient.updatePrivacyAgree(context, true);

            client = new AMapLocationClient(context.getApplicationContext());
            AMapLocationClientOption opt = new AMapLocationClientOption();
            opt.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            opt.setNeedAddress(true);   // 关键：需要地址（省/市/区/adCode）
            opt.setOnceLocation(false); // 持续定位以检测城市变化
            opt.setInterval(10_000);    // 10s 一次，足够且不浪费
            opt.setHttpTimeOut(15_000);
            opt.setSensorEnable(false);
            client.setLocationOption(opt);
            client.setLocationListener(mListener);
            client.startLocation();
            Log.d(TAG, "RegionLocator 启动，已开启地址解析");
        } catch (Throwable t) {
            Log.e(TAG, "RegionLocator 启动失败", t);
            if (callback != null) callback.onError(-1, "定位初始化失败：" + t.getMessage());
        }
    }

    private final AMapLocationListener mListener = new AMapLocationListener() {
        @Override
        public void onLocationChanged(AMapLocation loc) {
            if (loc == null) return;
            if (loc.getErrorCode() != AMapLocation.LOCATION_SUCCESS) {
                Log.w(TAG, "定位错误 code=" + loc.getErrorCode() + " msg=" + loc.getErrorInfo());
                return;
            }
            String adCode = loc.getAdCode();
            if (adCode == null || adCode.isEmpty()) {
                Log.w(TAG, "定位成功但未返回 adCode，city=" + loc.getCity());
                return;
            }
            BusRegion region = new BusRegion(
                    adCode,
                    loc.getProvince(),
                    loc.getCity(),
                    loc.getDistrict()
            );
            if (lastRegion == null || !lastRegion.adCode.equals(adCode)) {
                lastRegion = region;
                Log.d(TAG, "解析到地区：" + region.toShortString() + " adCode=" + adCode);
                if (callback != null) callback.onRegionResolved(region);
            }
        }
    };

    public void stop() {
        if (client != null) {
            try {
                client.stopLocation();
                client.onDestroy();
            } catch (Throwable t) {
                Log.e(TAG, "RegionLocator 销毁失败", t);
            }
            client = null;
        }
        callback = null;
    }
}
