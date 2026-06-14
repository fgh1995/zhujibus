package org.zjfgh.zhujibus;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.Locale;

public class SpeakFragment extends Fragment {
    private static final String TAG = "SpeakFragment";
    private static final float DEFAULT_ENTER_STATION_RADIUS = 30.0f;
    private static final float DEFAULT_EXIT_STATION_RADIUS = 80.0f;

    // UI 控件
    private TextView gpsLocationInfo;
    private TextView gpsNearestStationInfo;
    private TextView gpsEatInfo;
    private BorderLabel distanceModeInfo;
    private TextView enterProgressText;
    private TextView exitProgressText;
    private LinearLayout gpsLabel;
    private SeekBar enterRadiusSeekBar;
    private SeekBar exitRadiusSeekBar;

    // 数据
    private float enterStationRadius = DEFAULT_ENTER_STATION_RADIUS;
    private float exitStationRadius = DEFAULT_EXIT_STATION_RADIUS;

    public static SpeakFragment newInstance() {
        return new SpeakFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_speek_stttings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            initViews(view);
            setupSeekBars();
            updateSliderText();
        } catch (Exception e) {
            Log.e(TAG, "onViewCreated 失败", e);
        }
    }

    private void initViews(View view) {
        gpsLocationInfo = view.findViewById(R.id.gps_location_info);
        gpsNearestStationInfo = view.findViewById(R.id.gps_nearest_station_info);
        gpsEatInfo = view.findViewById(R.id.gps_eat_info);
        distanceModeInfo = view.findViewById(R.id.distance_mode_info);
        enterProgressText = view.findViewById(R.id.enter_progress_text);
        exitProgressText = view.findViewById(R.id.exit_progress_text);
        gpsLabel = view.findViewById(R.id.gps_label);
        enterRadiusSeekBar = view.findViewById(R.id.enter_radius_seekbar);
        exitRadiusSeekBar = view.findViewById(R.id.exit_radius_seekbar);
    }

    private void setupSeekBars() {
        if (enterRadiusSeekBar != null) {
            enterRadiusSeekBar.setProgress((int) enterStationRadius);
            enterRadiusSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    enterStationRadius = progress;
                    updateSliderText();
                    if (mListener != null) {
                        mListener.onEnterRadiusChanged(progress);
                    }
                    onRadiusChanged();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (exitRadiusSeekBar != null) {
            exitRadiusSeekBar.setProgress((int) exitStationRadius);
            exitRadiusSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    exitStationRadius = progress;
                    updateSliderText();
                    if (mListener != null) {
                        mListener.onExitRadiusChanged(progress);
                    }
                    onRadiusChanged();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }
    }

    private void updateSliderText() {
        if (enterProgressText != null) {
            enterProgressText.setText(String.valueOf((int)enterStationRadius));
        }
        if (exitProgressText != null) {
            exitProgressText.setText(String.valueOf((int)exitStationRadius));
        }
    }

    /**
     * 获取进站半径
     */
    public float getEnterStationRadius() {
        return enterStationRadius;
    }

    /**
     * 获取出站半径
     */
    public float getExitStationRadius() {
        return exitStationRadius;
    }

    /**
     * 设置进站半径
     */
    public void setEnterStationRadius(float radius) {
        enterStationRadius = radius;
        if (enterRadiusSeekBar != null) {
            enterRadiusSeekBar.setProgress((int) radius);
        }
        updateSliderText();
    }

    /**
     * 设置出站半径
     */
    public void setExitStationRadius(float radius) {
        exitStationRadius = radius;
        if (exitRadiusSeekBar != null) {
            exitRadiusSeekBar.setProgress((int) radius);
        }
        updateSliderText();
    }

    /**
     * 更新坐标显示
     */
    public void updateGpsLocation(double lat, double lon, String coordSystemLabel) {
        if (gpsLocationInfo != null) {
            gpsLocationInfo.setText(String.format(Locale.CHINA, "坐标：%.6f, %.6f (%s)", lat, lon, coordSystemLabel));
        }
    }

    /**
     * 更新最近站点信息
     */
    public void updateNearestStation(String stationName, double alongRouteDistance, double directDistance) {
        if (gpsNearestStationInfo != null) {
            gpsNearestStationInfo.setText(String.format(Locale.CHINA, "站点: %s (沿线%s/直线%s)",
                    stationName, formatDistance(alongRouteDistance), formatDistance(directDistance)));
        }
    }

    /**
     * 更新预计信息
     */
    public void updateEstimatedInfo(String eatText) {
        if (gpsEatInfo != null) {
            gpsEatInfo.setText(eatText);
        }
    }

    /**
     * 切换距离模式
     */
    public void setDistanceMode(boolean isStraightLine) {
        currentDistanceMode = isStraightLine ? DistanceMode.STRAIGHT_LINE : DistanceMode.ALONG_ROUTE;
        if (distanceModeInfo != null) {
            String modeText = currentDistanceMode == DistanceMode.STRAIGHT_LINE ? "直线距离" : "沿线距离";
            distanceModeInfo.setText(String.format(Locale.CHINA, "报站判定: %s", modeText));
        }
    }

    /**
     * 获取当前距离模式
     */
    public boolean isStraightLine() {
        return currentDistanceMode == DistanceMode.STRAIGHT_LINE;
    }

    /**
     * 初始化距离模式点击切换
     */
    public void initDistanceModeToggle(OnDistanceModeChangeListener listener) {
        if (distanceModeInfo != null) {
            distanceModeInfo.setOnClickListener(v -> {
                if (currentDistanceMode == DistanceMode.STRAIGHT_LINE) {
                    currentDistanceMode = DistanceMode.ALONG_ROUTE;
                } else {
                    currentDistanceMode = DistanceMode.STRAIGHT_LINE;
                }
                String modeText = currentDistanceMode == DistanceMode.STRAIGHT_LINE ? "直线距离" : "沿线距离";
                distanceModeInfo.setText(String.format(Locale.CHINA, "报站判定: %s", modeText));
                if (listener != null) {
                    listener.onDistanceModeToggled(currentDistanceMode == DistanceMode.STRAIGHT_LINE);
                }
            });
        }
    }

    public interface OnDistanceModeChangeListener {
        void onDistanceModeToggled(boolean isStraightLine);
    }

    public enum DistanceMode {
        STRAIGHT_LINE("直线距离"),
        ALONG_ROUTE("沿线距离");

        private final String displayName;
        DistanceMode(String displayName) {
            this.displayName = displayName;
        }
        public String getDisplayName() {
            return displayName;
        }
    }
    private DistanceMode currentDistanceMode = DistanceMode.STRAIGHT_LINE;

    /**
     * GPS 显示状态
     */
    public void setGpsVisible(boolean visible) {
        if (gpsLabel != null) {
            gpsLabel.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 半径变化回调（供外部监听）
     */
    protected void onRadiusChanged() {
        // 子类可重写此方法
    }

    /**
     * 设置半径变化监听器
     */
    public void setOnRadiusChangedListener(OnRadiusChangedListener listener) {
        this.mListener = listener;
    }

    private OnRadiusChangedListener mListener;

    public interface OnRadiusChangedListener {
        void onEnterRadiusChanged(float radius);
        void onExitRadiusChanged(float radius);
    }

    /**
     * 距离格式化
     */
    private String formatDistance(double distance) {
        if (distance >= 1000) {
            return String.format(Locale.CHINA, "%.1fkm", distance / 1000);
        } else {
            return String.format(Locale.CHINA, "%.0fm", distance);
        }
    }
}