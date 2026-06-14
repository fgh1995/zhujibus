package org.zjfgh.zhujibus;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.LatLng;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 导航主页 Fragment
 * <p>
 * 承载内容：
 *   - 地图显示区（TextureMapView）
 *   - 下方线路信息面板
 *   - 右侧信息栏（时间 + 日期 + 限速 + 实时速度）
 * <p>
 * 设计要点：
 *   - 地图生命周期完全由 Fragment 内部管理
 *   - 提供公开方法供 Activity 更新线路号、方向、下一站、速度等
 *   - 通过 {@link #getNavigation()} 暴露 AmapNavigationView，
 *     供 Activity 转发 onResume / onPause / onSaveInstanceState / onDestroy
 *   - 时间更新独立 Handler，每秒刷新一次
 */
public class NavigationMainFragment extends Fragment {

    private static final String TAG = "NavigationMainFragment";

    // ---- 线路信息（由 Activity 在创建时传入） ----
    private static final String ARG_LINE_NAME = "line_name";
    private static final String ARG_END_STATION = "end_station";

    private String lineName;
    private String endStation;

    // ---- 视图引用 ----
    private TextureMapView tencentMapView;
    private TextView navTimeHM;
    private TextView navTimeSecond;
    private TextView navDateText;
    private HorizontalScrollTextView navRouteNo;
    // 在 NavigationMainFragment 中
    private View.OnClickListener swapOrientationListener;
    private TextView navSwapOrientation;
    private boolean isLoopLine;
    private HorizontalScrollTextView navNextStation;
    private HorizontalScrollTextView navDirection;
    private TextView gpsSpeedText;

    // ---- 地图导航管理 ----
    private AmapNavigationView navigation;

    // ---- 时间更新 ----
    private Handler navigationTimeHandler;
    private Runnable navigationTimeRunnable;
    private Typeface digitalTypeface;

    /** Activity 是否已 resumed：用于在 onGlobalLayout 完成后补一次 onResume */
    private boolean isHostResumed = false;
    // 在 NavigationMainFragment 类中添加字段
    private TextView firstBusTime;   // 首班车
    private TextView lastBusTime;    // 末班车
    private TextView routeSummary;   // 总里程
    private TextView ticket;   // 总里程
    public static NavigationMainFragment newInstance(String lineName, String endStation) {
        NavigationMainFragment f = new NavigationMainFragment();
        Bundle args = new Bundle();
        if (lineName != null) args.putString(ARG_LINE_NAME, lineName);
        if (endStation != null) args.putString(ARG_END_STATION, endStation);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            lineName = args.getString(ARG_LINE_NAME);
            endStation = args.getString(ARG_END_STATION);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_navigation_main, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            // 1. 找到所有视图
            tencentMapView = view.findViewById(R.id.amap_map_view);
            navTimeHM = view.findViewById(R.id.nav_time_hm);
            navTimeSecond = view.findViewById(R.id.nav_time_second);
            navDateText = view.findViewById(R.id.nav_date_text);
            navRouteNo = view.findViewById(R.id.nav_route_no);
            navSwapOrientation = view.findViewById(R.id.nav_swap_orientation);

            navNextStation = view.findViewById(R.id.nav_next_station);
            navDirection = view.findViewById(R.id.nav_direction);
            gpsSpeedText = view.findViewById(R.id.gps_speed_text);
            // 注意：你需要先在 fragment_navigation_main.xml 中为这些 TextView 添加 id
            firstBusTime = view.findViewById(R.id.first_bus_time);
            lastBusTime = view.findViewById(R.id.last_bus_time);
            routeSummary = view.findViewById(R.id.route_summary);
            ticket = view.findViewById(R.id.ticket);
            // 2. 加载数码字体（与 Activity 保持一致）
            try {
                digitalTypeface = Typeface.createFromAsset(requireContext().getAssets(), "fonts/DS-DIGIB-2.ttf");
                if (navTimeHM != null) navTimeHM.setTypeface(digitalTypeface);
                if (navTimeSecond != null) navTimeSecond.setTypeface(digitalTypeface);
                if (gpsSpeedText != null) gpsSpeedText.setTypeface(digitalTypeface);
            } catch (Throwable t) {
                Log.w(TAG, "加载数码字体失败: " + t.getMessage());
            }

            // 3. 初始化文本（载入时就能显示的内容）
            if (navRouteNo != null && lineName != null) {
                navRouteNo.setText(lineName);
            }
            if (navNextStation != null) {
                navNextStation.setText("加载中");
            }
            if (navDirection != null && endStation != null) {
                navDirection.setText("往 " + endStation + "方向");
            }

            // 4. 启动独立时间更新
            navigationTimeHandler = new Handler();
            navigationTimeRunnable = new Runnable() {
                @Override
                public void run() {
                    updateNavigationTime();
                    if (navigationTimeHandler != null) {
                        navigationTimeHandler.postDelayed(this, 1000);
                    }
                }
            };
            navigationTimeHandler.post(navigationTimeRunnable);
            if (swapOrientationListener != null && navSwapOrientation != null) {
                navSwapOrientation.setOnClickListener(swapOrientationListener);
            }
            if (navSwapOrientation != null){
                if (isLoopLine){
                    navSwapOrientation.setVisibility(View.GONE);
                }else{
                    navSwapOrientation.setVisibility(View.VISIBLE);
                }
            }
            // 5. 初始化高德地图（延迟到 layout 完成）
            initMapWhenReady(savedInstanceState);
        } catch (Exception e) {
            Log.e(TAG, "onViewCreated 失败", e);
        }
    }

    /**
     * 等 View 第一次完成 layout 后再启动地图引擎
     * （避免在 0×0 尺寸时启动 GL 线程导致首帧渲染失败）
     */
    private void initMapWhenReady(Bundle savedInstanceState) {
        if (tencentMapView == null) {
            Log.e(TAG, "initMapWhenReady skipped: tencentMapView is null");
            return;
        }
        final Bundle finalSavedState = savedInstanceState;
        tencentMapView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (tencentMapView.getWidth() <= 0 || tencentMapView.getHeight() <= 0) {
                            return;
                        }
                        tencentMapView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        try {
                            navigation = new AmapNavigationView(requireContext(), tencentMapView);
                            Log.d(TAG, "onGlobalLayout -> start onCreate (w=" +
                                    tencentMapView.getWidth() + ", h=" + tencentMapView.getHeight() + ")");
                            navigation.onCreate(finalSavedState);
                            // 边界：onCreate 在 onResume 之后才执行的情况
                            if (isHostResumed && navigation != null) {
                                navigation.onResume();
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "delayed onCreate failed: " + t.getMessage(), t);
                        }
                    }
                });
    }

    // ============================================================
    //  公开 API（供 Activity 调用以更新数据）
    // ============================================================

    /**
     * 获取导航管理器（供 Activity 转发 onResume / onPause / onSaveInstanceState / onDestroy）
     */
    public AmapNavigationView getNavigation() {
        return navigation;
    }

    /** 设置 GPS 模式（true = 罗盘+3D / false = 自由视角） */
    public void setGpsMode(boolean gpsMode) {
        if (navigation != null) navigation.setGpsMode(gpsMode);
    }
    public void setSwapOrientation(View.OnClickListener listener){
        Log.d(TAG, "setSwapOrientation called, navSwapOrientation=" + navSwapOrientation);
        this.swapOrientationListener = listener;
        if (navSwapOrientation != null && listener != null) {
            Log.d(TAG, "Setting click listener on navSwapOrientation");
            navSwapOrientation.setOnClickListener(listener);
        } else {
            Log.d(TAG, "navSwapOrientation is null, listener saved for later");
        }
    }
    public void setLoopLine(boolean isLoopLine){
        if (navSwapOrientation != null){
            this.isLoopLine = isLoopLine;
            if (isLoopLine){
                navSwapOrientation.setVisibility(View.GONE);
            }else{
                navSwapOrientation.setVisibility(View.VISIBLE);
            }
        }
    }
    /** 更新实时速度（单位 km/h） */
    public void updateSpeed(float speedKmh) {
        if (gpsSpeedText != null) {
            gpsSpeedText.setText(String.format(Locale.CHINA, "%.0f", speedKmh));
        }
    }

    /** 速度归零 */
    public void resetSpeed() {
        if (gpsSpeedText != null) {
            gpsSpeedText.setText("0");
        }
    }

    /** 更新下一站文本（如"下一站: 东方路五一路"） */
    public void updateNextStation(String stationName) {
        if (navNextStation != null) {
            navNextStation.setText("下一站: " + stationName);
        }
    }

    /** 更新方向文本（如"往 终点站 方向"） */
    public void updateDirection(String directionEndStation) {
        if (navDirection != null && directionEndStation != null) {
            navDirection.setText("往 " + directionEndStation + "方向");
        }
    }

    /** 更新线路号 */
    public void updateRouteNo(String lineName) {
        if (navRouteNo != null && lineName != null) {
            navRouteNo.setText(lineName);
        }
    }
    /** 进场提示 */
    public void showEnteringHint() {
        if (navNextStation != null) navNextStation.setText("进场");
    }

    /** 位置更新（转发到地图） */
    public void updateMyLocation(double gcjLat, double gcjLng, float bearing) {
        if (navigation != null) {
            navigation.updateMyLocation(gcjLat, gcjLng, bearing);
        }
    }

    /** 绘制路线 */
    public void drawRoute(List<LatLng> mapPoints) {
        if (navigation != null) navigation.drawRoute(mapPoints);
    }

    /** 通知 Fragment：宿主 Activity 已 resume / pause（用于补 onResume） */
    public void notifyHostResumed(boolean resumed) {
        isHostResumed = resumed;
    }

    // ============================================================
    //  内部方法
    // ============================================================

    /** 独立更新导航栏时间（不依赖 Activity 的 timeHandler） */
    private void updateNavigationTime() {
        if (getContext() == null) return;
        try {
            Calendar calendar = Calendar.getInstance();
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            int minute = calendar.get(Calendar.MINUTE);
            int second = calendar.get(Calendar.SECOND);
            int day = calendar.get(Calendar.DAY_OF_MONTH);
            String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
            int weekDay = calendar.get(Calendar.DAY_OF_WEEK) - 1;
            if (weekDay < 0) weekDay = 0;

            if (navTimeHM != null) {
                navTimeHM.setText(String.format(Locale.getDefault(), "%02d:%02d", hour, minute));
            }
            if (navTimeSecond != null) {
                navTimeSecond.setText(String.format(Locale.getDefault(), "%02d", second));
            }
            if (navDateText != null) {
                navDateText.setText(String.format(Locale.getDefault(), "%d月%d日 %s", calendar.get(Calendar.MONTH) + 1,day, weekDays[weekDay]));
            }
        } catch (Throwable t) {
            Log.w(TAG, "updateNavigationTime failed: " + t.getMessage());
        }
    }
    /**
     * 更新首班车时间
     */
    public void updateFirstBusTime(String time) {
        if (firstBusTime != null && time != null) {
            firstBusTime.setText("首：" + time);
        }
    }

    /**
     * 更新末班车时间
     */
    public void updateLastBusTime(String time) {
        if (lastBusTime != null && time != null) {
            lastBusTime.setText("末：" + time);
        }
    }

    /**
     * 更新总里程
     */
    public void updateRouteSummary(String mileage) {
        if (routeSummary != null && mileage != null) {
            routeSummary.setText("总里程：" + mileage + " 公里");
        }
    }
    /**
     * 更新票价
     */
    public void updatePriceText(String priceText) {
        if (ticket != null && priceText != null) {
            ticket.setText("票价：" + priceText + " 元");
        }
    }

    // ============================================================
    //  Fragment 生命周期
    // ============================================================

    @Override
    public void onResume() {
        super.onResume();
        isHostResumed = true;
        if (navigation != null) {
            navigation.onResume();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        isHostResumed = false;
        if (navigation != null) {
            navigation.onPause();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (navigation != null) {
            navigation.onSaveInstanceState(outState);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (navigation != null) {
            navigation.onLowMemory();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 停止时间更新
        if (navigationTimeHandler != null && navigationTimeRunnable != null) {
            navigationTimeHandler.removeCallbacks(navigationTimeRunnable);
        }
        navigationTimeHandler = null;
        navigationTimeRunnable = null;

        // 销毁地图导航管理器
        if (navigation != null) {
            navigation.onDestroy();
            navigation = null;
        }

        // 清空视图引用
        tencentMapView = null;
        navTimeHM = null;
        navTimeSecond = null;
        navDateText = null;
        navRouteNo = null;
        navNextStation = null;
        navDirection = null;
        gpsSpeedText = null;
    }
}
