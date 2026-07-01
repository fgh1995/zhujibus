package org.zjfgh.zhujibus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.BitmapDescriptor;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;

/**
 * 自车位置管理 Overlay 类（参考官方 Demo：CarOverlay.java）
 * <p>
 * 功能：
 * - 绘制车标 Marker（贴地 + 旋转跟随车头）
 * - 绘制方向指示器 Marker
 * - 锁车态控制（地图跟随车移动和旋转）
 */
public class CarOverlay {
    private static final String TAG = "CarOverlay";

    protected boolean mIsLock = true;  // 锁车态标记
    protected float newAngle = 0;      // 当前车头方向
    protected BitmapDescriptor carDescriptor = null;        // 车标图标
    protected BitmapDescriptor fourCornersDescriptor = null; // 方向指示器图标
    protected Marker carMarker;          // 车标 Marker
    protected Marker directionMarker;    // 方向指示器 Marker
    protected AMap mAmap = null;
    protected TextureMapView mapView;

    // 导航视角参数
    private static final float NAVI_ZOOM = 18f;   // 导航缩放级别（适中，不要太贴地）
    private static final float NAVI_TILT = 0f;    // 导航俯视角度（完全俯视，2D视角）

    // ⭐ 图标缩放比例（调整车标和方向指示器的大小）
    private static final float CAR_ICON_SCALE = 0.4f;        // 车标缩小到 50%
    private static final float DIRECTION_ICON_SCALE = 0.4f;  // 方向指示器缩小到 50%

    public CarOverlay(Context context, TextureMapView mapView) {
        this.mapView = mapView;

        // ⭐ 加载车标图标和方向指示器图标（缩放后）
        try {
            // 加载并缩放方向指示器图标
            Bitmap directionBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.navi_direction);
            Bitmap scaledDirectionBitmap = scaleBitmap(directionBitmap, DIRECTION_ICON_SCALE);
            fourCornersDescriptor = BitmapDescriptorFactory.fromBitmap(scaledDirectionBitmap);

            // 加载并缩放车标图标
            Bitmap carBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.caricon);
            Bitmap scaledCarBitmap = scaleBitmap(carBitmap, CAR_ICON_SCALE);
            carDescriptor = BitmapDescriptorFactory.fromBitmap(scaledCarBitmap);

            Log.d(TAG, "[INIT] 车标图标已加载并缩放：原始尺寸=" + carBitmap.getWidth() + "x" + carBitmap.getHeight()
                    + ", 缩放后=" + scaledCarBitmap.getWidth() + "x" + scaledCarBitmap.getHeight());
        } catch (Exception e) {
            Log.e(TAG, "加载车标图标失败: " + e.getMessage(), e);
        }
    }

    /**
     * ⭐ 缩放 Bitmap 到指定比例
     */
    private Bitmap scaleBitmap(Bitmap source, float scale) {
        if (source == null) return null;
        int width = (int) (source.getWidth() * scale);
        int height = (int) (source.getHeight() * scale);
        return Bitmap.createScaledBitmap(source, width, height, true);
    }

    /**
     * 设置自车状态
     *
     * @param lock true 锁车（地图跟随车移动），false 解锁车（用户可拖动地图）
     */
    public void setLock(boolean lock) {
        mIsLock = lock;
        if (carMarker == null || mAmap == null || directionMarker == null) {
            return;
        }

        carMarker.setFlat(true);
        directionMarker.setGeoPoint(carMarker.getGeoPoint());
        carMarker.setGeoPoint(carMarker.getGeoPoint());
        carMarker.setRotateAngle(carMarker.getRotateAngle());

        if (mIsLock) {
            // ⭐ 锁车态：地图跟随车移动和旋转
            CameraPosition cameraPosition = new CameraPosition.Builder()
                    .target(carMarker.getPosition())  // 地图中心 = 车的位置
                    .bearing(newAngle)                // 地图方向 = 车头方向
                    .tilt(NAVI_TILT)                  // 完全俯视（0度）
                    .zoom(NAVI_ZOOM)                  // 适中缩放（15级）
                    .build();
            mAmap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
            Log.d(TAG, "[LOCK] 锁车态已启用：地图跟随车移动和旋转");
        } else {
            Log.d(TAG, "[UNLOCK] 解锁车态：用户可以拖动地图");
        }
    }

    /**
     * 重置车标（清除所有 Marker）
     */
    public void reset() {
        if (carMarker != null) {
            carMarker.remove();
            carMarker = null;
        }
        if (directionMarker != null) {
            directionMarker.remove();
            directionMarker = null;
        }
    }

    /**
     * 绘制自车（每次定位更新时调用）
     *
     * @param aMap    地图对象
     * @param mLatLng 车的位置
     * @param bearing 车头方向（0-360度）
     */
    public void draw(AMap aMap, LatLng mLatLng, float bearing) {
        if (aMap == null || mLatLng == null || carDescriptor == null) {
            return;
        }
        mAmap = aMap;

        try {
            // 创建车标 Marker（首次）
            if (carMarker == null) {
                carMarker = aMap.addMarker(new MarkerOptions()
                        .anchor(0.5f, 0.5f)    // 中心点锚定
                        .setFlat(true)         // ⭐ 贴地（跟随地图旋转）
                        .icon(carDescriptor)
                        .position(mLatLng));
            }

            // 创建方向指示器 Marker（首次）
            if (directionMarker == null) {
                directionMarker = aMap.addMarker(new MarkerOptions()
                        .anchor(0.5f, 0.5f)
                        .setFlat(true)
                        .icon(fourCornersDescriptor)
                        .position(mLatLng));
                directionMarker.setVisible(true);
            }

            // 更新车标位置和方向
            carMarker.setVisible(true);
            newAngle = bearing;
            updateCarPosition(mLatLng, bearing);

        } catch (Throwable e) {
            Log.e(TAG, "绘制车标失败: " + e.getMessage(), e);
        }
    }

    /**
     * 更新车标位置和方向（每次定位更新时调用）
     */
    private void updateCarPosition(LatLng latLng, float bearing) {
        if (carMarker == null || mAmap == null) {
            return;
        }

        // 更新车标位置
        carMarker.setPosition(latLng);
        carMarker.setFlat(true);
        carMarker.setRotateAngle(360 - bearing);  // ⭐ 车标旋转（逆向）

        // 更新方向指示器位置
        if (directionMarker != null) {
            directionMarker.setPosition(latLng);
        }

        // ⭐ 锁车态：地图跟随车移动和旋转
        if (mIsLock) {
            // ⭐ 关键API：changeBearingGeoCenter（同时改变地图方向和中心点）
            CameraPosition cameraPosition = new CameraPosition.Builder()
                    .target(latLng)      // 地图中心 = 车的位置
                    .bearing(bearing)    // 地图方向 = 车头方向
                    .tilt(NAVI_TILT)     // 完全俯视（0度）
                    .zoom(NAVI_ZOOM)     // 适中缩放（15级）
                    .build();
            mAmap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
        }
    }

    /**
     * 获取锁车状态
     */
    public boolean isLock() {
        return mIsLock;
    }

    /**
     * 释放资源
     */
    public void destroy() {
        if (carMarker != null) {
            carMarker.remove();
            carMarker = null;
        }
        if (directionMarker != null) {
            directionMarker.remove();
            directionMarker = null;
        }
        carDescriptor = null;
        fourCornersDescriptor = null;
    }
}