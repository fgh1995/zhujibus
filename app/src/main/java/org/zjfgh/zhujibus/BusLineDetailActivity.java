package org.zjfgh.zhujibus;

import static com.google.android.material.internal.ViewUtils.dpToPx;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationProvider;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import androidx.core.app.ActivityCompat;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.animation.ValueAnimator;
import android.os.Handler;
import android.text.Html;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.slider.Slider;
import android.widget.Toast;

import androidx.navigation.ui.AppBarConfiguration;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.graphics.Color;

import io.sgr.geometry.utils.GeometryUtils;
import io.sgr.geometry.utils.RouteGeometryUtils;

public class BusLineDetailActivity extends AppCompatActivity implements BusRealTimeManager.RealTimeUpdateListener {
    private String lineID;
    private String lineName;
    private String startStation;
    private String endStation;
    private BusApiClient busApiClient;
    private HorizontalScrollTextView routeNumber;
    private TextView noticeText;
    private LinearLayout swapOrientation;
    private DotMatrixView accessibilityIcon;
    private HorizontalScrollTextView nextStationInfo;
    private HorizontalScrollTextView tips;

    // 当前显示的方向 (1:上行, 2:下行)
    private int currentDirection = 1;
    // 线路是否有双向
    private boolean isTwoWayLine = false;
    // 缓存线路数据
    private BusApiClient.BusLineDetailResponse cachedResponse;
    private BusRealTimeManager realTimeManager;
    private Handler handler = new Handler();
    private RecyclerView stationListRecyclerView;
    private StationAdapter stationAdapter;
    private BusLineView busLineView;
    private ScrollView stationScrollView;
    private List<BusEtaItem> etaItems = new ArrayList<>();
    private BusEtaAdapter busEtaAdapter;
    private BorderLabel scheduleButton;
    private BorderLabel hideBusCardReader;
    private FrameLayout busCardReaderView;
    private TextView swapOrientationLabel;
    private TextView firstBusMarker;
    private TextView lastBusMarker;
    private TextView routeSummary;
    private static final String TAG = "BusLineDetailActivity";
    TextView ticket;
    TextView currentTime;
    private Handler timeHandler = new Handler();
    private int timeDisplayPhase = 0;
    private final Runnable timeScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentTime != null) {
                SimpleDateFormat formatter;
                if (timeDisplayPhase == 0) {
                    formatter = new SimpleDateFormat("yy.MM.dd", Locale.getDefault());
                    currentTime.setText(formatter.format(new Date()));
                    timeDisplayPhase = 1;
                    timeHandler.postDelayed(this, 2000);
                } else {
                    formatter = new SimpleDateFormat("HH.mm.ss", Locale.getDefault());
                    currentTime.setText(formatter.format(new Date()));
                    timeDisplayPhase = (timeDisplayPhase == 1) ? 2 : 0;
                    timeHandler.postDelayed(this, 1000);
                }
            }
        }
    };
    TextView refreshTime;
    TextView errorIndicator;
    TextView networkModeText;
    TextView gpsModeText;
    TextView networkStatusIndicator;
    TextView gpsStatusIndicator;
    TextView gpsLocationInfo;
    TextView gpsNearestStationInfo;
    BorderLabel gpsStatusInfo;
    BorderLabel distanceModeInfo;
    TextView gpsSpeedText;
    TextView gpsCount;
    TextView radiusMinText;
    TextView radiusMaxText;
    LinearLayout gpsLabel;
    private ValueAnimator errorBlinkAnimator;
    private ValueAnimator gpsBlinkAnimator;
    private boolean isGpsSignalNormal = false;
    private String lastErrorMessage = null;
    private String lastErrorDetail = null;
    private Handler gpsTimeHandler = new Handler();
    private Runnable gpsTimeRunnable;
    private static final long GPS_TIME_UPDATE_INTERVAL = 1000L;

    public enum AnnounceMode {
        NETWORK,
        GPS
    }
    private AnnounceMode currentAnnounceMode = AnnounceMode.NETWORK;

    private int lastAnnouncedStationIndex = -1;
    private boolean isInsideStationRadius = false;
    private int lastInsideStationIndex = -1;
    private boolean hasLeftTerminalStation = false;

    private double currentGpsLat = 0;
    private double currentGpsLon = 0;
    private String nearestStationName = "";
    private double nearestStationDistance = -1;
    private double nearestStationLat = 0;
    private double nearestStationLon = 0;
    private double nearestStationDirectDistance = -1;
    private boolean isInsideRadius = false;
    private static final double DEFAULT_STATION_RADIUS = 50.0;
    private double stationRadius = DEFAULT_STATION_RADIUS;
    private Slider radiusSlider;
    private List<io.sgr.geometry.Coordinate> routePoints;

    private double lastLocationLat = 0;
    private double lastLocationLon = 0;
    private long lastLocationTimeForSpeed = 0;
    private int stationaryConsecutiveCount = 0;
    private static final double STATIONARY_DISTANCE_THRESHOLD_M = 1.0;
    private static final int STATIONARY_CONSECUTIVE_UPDATES = 3;
    private int locationUpdateCount = 0;

    public enum DistanceMode {
        ALONG_ROUTE("沿线距离"),
        STRAIGHT_LINE("直线距离");

        private final String displayName;
        DistanceMode(String displayName) {
            this.displayName = displayName;
        }
        public String getDisplayName() {
            return displayName;
        }
    }
    private DistanceMode currentDistanceMode = DistanceMode.STRAIGHT_LINE;

    public enum CoordConvertMode {
        WGS_TO_GCJ("WGS→GCJ-02"),
        GCJ_TO_WGS("GCJ-02→WGS"),
        NO_CONVERT("不转换");

        private final String displayName;
        CoordConvertMode(String displayName) {
            this.displayName = displayName;
        }
        public String getDisplayName() {
            return displayName;
        }
    }
    private CoordConvertMode currentCoordConvertMode = CoordConvertMode.WGS_TO_GCJ;

    private static final int TIPS_INTERVAL = 3000;
    private static final String[] TIPS_TEXT_BASE = {"文明排队   上下有序", "严禁携带危险物品上车"};
    private static final int[] TIPS_COLOR_BASE = {0xFFFFFF00, 0xFF00FFFF};
    private static final int TIPS_COLOR_PURPLE = 0xFFAA00FF;
    private String[] currentTipsText = TIPS_TEXT_BASE;
    private int[] currentTipsColor = TIPS_COLOR_BASE;
    private Handler tipsHandler = new Handler();
    private int tipsAnimationIndex = 0;

    private final Runnable tipsRunnable = new Runnable() {
        @Override
        public void run() {
            tipsAnimationIndex = (tipsAnimationIndex + 1) % currentTipsText.length;
            tips.setText(currentTipsText[tipsAnimationIndex]);
            tips.setTextColor(currentTipsColor[tipsAnimationIndex]);
            tipsHandler.postDelayed(this, TIPS_INTERVAL);
        }
    };

    private void startTipsAnimation() {
        tipsHandler.removeCallbacksAndMessages(null);
        tipsAnimationIndex = 0;
        tips.setText(currentTipsText[0]);
        tips.setTextColor(currentTipsColor[0]);
        tipsHandler.postDelayed(tipsRunnable, TIPS_INTERVAL);
    }

    private String formatDistance(double distance) {
        if (distance >= 1000) {
            return String.format(Locale.CHINA, "%.1fkm", distance / 1000);
        } else {
            return String.format(Locale.CHINA, "%.0fm", distance);
        }
    }

    private void showAnnounceModeDialog() {
        String[] modes = {"网络模式", "GPS模式"};
        int selectedIndex = currentAnnounceMode == AnnounceMode.NETWORK ? 0 : 1;
        new AlertDialog.Builder(this)
                .setTitle("选择报站模式")
                .setSingleChoiceItems(modes, selectedIndex, (dialog, which) -> {
                    AnnounceMode newMode = (which == 0) ? AnnounceMode.NETWORK : AnnounceMode.GPS;
                    if (newMode != currentAnnounceMode) {
                        currentAnnounceMode = newMode;
                        updateAnnounceModeState();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDistanceModeDialog() {
        String[] modes = {DistanceMode.STRAIGHT_LINE.getDisplayName(), DistanceMode.ALONG_ROUTE.getDisplayName()};
        int selectedIndex = currentDistanceMode == DistanceMode.STRAIGHT_LINE ? 0 : 1;
        new AlertDialog.Builder(this)
                .setTitle("选择距离计算方式")
                .setSingleChoiceItems(modes, selectedIndex, (dialog, which) -> {
                    DistanceMode newMode = (which == 0) ? DistanceMode.STRAIGHT_LINE : DistanceMode.ALONG_ROUTE;
                    if (newMode != currentDistanceMode) {
                        currentDistanceMode = newMode;
                        updateDistanceModeState();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCoordConvertModeDialog() {
        String[] modes = {
                CoordConvertMode.WGS_TO_GCJ.getDisplayName(),
                CoordConvertMode.GCJ_TO_WGS.getDisplayName(),
                CoordConvertMode.NO_CONVERT.getDisplayName()
        };
        int selectedIndex;
        switch (currentCoordConvertMode) {
            case GCJ_TO_WGS:
                selectedIndex = 1;
                break;
            case NO_CONVERT:
                selectedIndex = 2;
                break;
            default:
                selectedIndex = 0;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择坐标转换模式")
                .setSingleChoiceItems(modes, selectedIndex, (dialog, which) -> {
                    CoordConvertMode newMode;
                    switch (which) {
                        case 1:
                            newMode = CoordConvertMode.GCJ_TO_WGS;
                            break;
                        case 2:
                            newMode = CoordConvertMode.NO_CONVERT;
                            break;
                        default:
                            newMode = CoordConvertMode.WGS_TO_GCJ;
                    }
                    if (newMode != currentCoordConvertMode) {
                        currentCoordConvertMode = newMode;
                        updateCoordConvertModeState();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateCoordConvertModeState() {
        Log.d(TAG, "坐标转换模式已切换为: " + currentCoordConvertMode.getDisplayName());
        updateCoordConvertModeDisplay();
    }

    private void updateCoordConvertModeDisplay() {
        Log.d(TAG, "坐标转换模式显示: " + currentCoordConvertMode.getDisplayName());
    }

    private void updateDistanceModeState() {
        String modeText = currentDistanceMode == DistanceMode.STRAIGHT_LINE ? "直线" : "沿线";
        Log.d(TAG, "距离模式已切换为: " + modeText);
        updateDistanceModeDisplay();
    }

    private void updateDistanceModeDisplay() {
        if (distanceModeInfo != null) {
            String modeText = currentDistanceMode == DistanceMode.STRAIGHT_LINE ? "直线距离" : "沿线距离";
            distanceModeInfo.setText(String.format(Locale.CHINA, "报站判定: %s", modeText));
        }
    }

    private void updateAnnounceModeState() {
        if (currentAnnounceMode == AnnounceMode.GPS) {
            if (!PermissionUtils.hasLocationPermission(this)) {
                PermissionUtils.requestLocationPermission(this, new PermissionUtils.PermissionCallback() {
                    @Override
                    public void onPermissionGranted() {
                        updateAnnounceModeState();
                    }

                    @Override
                    public void onPermissionDenied() {
                        currentAnnounceMode = AnnounceMode.NETWORK;
                        updateAnnounceModeDisplay();
                        Toast.makeText(BusLineDetailActivity.this, "没有位置权限，GPS模式不可用", Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            TTSUtils.getInstance(this).stopAll();
            lastVoiceStationOrder = -1;
            lastAnnouncedStationIndex = -1;
            isInsideStationRadius = false;
            lastInsideStationIndex = -1;
            hasLeftTerminalStation = false;
            lastLocationTimeForSpeed = 0;
            lastLocationLat = 0;
            lastLocationLon = 0;
            stationaryConsecutiveCount = 0;
            locationUpdateCount = 0;
            if (realTimeManager != null) {
                realTimeManager.stopTracking();
            }
            clearEtaItems();
            if (busLineView != null) {
                busLineView.resetAllStations();
                busLineView.setGpsMode(true);
            }
            GpsWarmingUp.startWarmingUp(this);
            GpsWarmingUp.addListener(gpsActivityListener);
            GpsWarmingUp.addSatelliteListener(satelliteCountListener);
            Location lastLocation = GpsWarmingUp.getLastKnownLocation();
            if (lastLocation != null) {
                handleGpsLocation(lastLocation);
            }
            checkGpsProviderStatus();
            startGpsTimeUpdate();
        } else {
            GpsWarmingUp.removeListener(gpsActivityListener);
            GpsWarmingUp.removeSatelliteListener(satelliteCountListener);
            GpsWarmingUp.stopWarmingUp();
            lastAnnouncedStationIndex = -1;
            isInsideStationRadius = false;
            lastInsideStationIndex = -1;
            hasLeftTerminalStation = false;
            if (realTimeManager != null) {
                realTimeManager.startTracking(getCurrentDirectionId(), this);
            }
            if (busLineView != null) {
                busLineView.setGpsMode(false);
            }
            stopGpsTimeUpdate();
        }
        updateAnnounceModeDisplay();
    }

    private void checkGpsProviderStatus() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean isGpsEnabled = false;
        if (locationManager != null) {
            try {
                LocationProvider gpsProvider = locationManager.getProvider(LocationManager.GPS_PROVIDER);
                if (gpsProvider != null) {
                    List<String> enabledProviders = locationManager.getProviders(true);
                    for (String provider : enabledProviders) {
                        if (LocationManager.GPS_PROVIDER.equals(provider)) {
                            isGpsEnabled = true;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("BusLineDetailActivity", "检查GPS状态失败", e);
            }
        }
        updateGpsStatusIndicator(isGpsEnabled);
    }

    private void updateAnnounceModeDisplay() {
        if (currentAnnounceMode == AnnounceMode.GPS) {
            networkModeText.setTextColor(0xFF555555);
            gpsModeText.setTextColor(0xFFFF0000);
            gpsModeText.setText("GPS");
            updateNetworkStatusIndicator(false);
            if (gpsCount != null) {
                gpsCount.setTextColor(0xFF00FF00);
            }
        } else {
            networkModeText.setTextColor(0xFFFF0000);
            gpsModeText.setTextColor(0xFF555555);
            gpsModeText.setText("GPS");
            isGpsSignalNormal = false;
            updateGpsStatusIndicator(false);
            if (gpsLabel != null) {
                gpsLabel.setVisibility(View.GONE);
            }
            if (gpsCount != null) {
                gpsCount.setText("-- / --");
                gpsCount.setTextColor(0xFF555555);
            }
        }
    }

    private void startGpsTimeUpdate() {
        stopGpsTimeUpdate();
        if (gpsTimeRunnable == null) {
            gpsTimeRunnable = new Runnable() {
                @Override
                public void run() {
                    SimpleDateFormat formatter = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                    refreshTime.setText(formatter.format(new Date()));
                    gpsTimeHandler.postDelayed(this, GPS_TIME_UPDATE_INTERVAL);
                }
            };
        }
        gpsTimeHandler.post(gpsTimeRunnable);
    }

    private void stopGpsTimeUpdate() {
        if (gpsTimeRunnable != null) {
            gpsTimeHandler.removeCallbacks(gpsTimeRunnable);
        }
    }

    private final LocationListener gpsActivityListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            handleGpsLocation(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}
    };

    private final GpsWarmingUp.SatelliteCountListener satelliteCountListener = (usedCount, totalCount) -> {
        runOnUiThread(() -> {
            if (gpsCount != null) {
                gpsCount.setText(usedCount + "/" + totalCount);
            }
        });
    };

    private void handleGpsLocation(Location location) {
        if (currentAnnounceMode != AnnounceMode.GPS) {
            return;
        }
        if (realTimeManager == null || realTimeManager.getStationList() == null) {
            return;
        }
        isGpsSignalNormal = true;
        updateGpsStatusIndicator(true);
        if (gpsLabel != null && gpsLabel.getVisibility() != View.VISIBLE) {
            gpsLabel.setVisibility(View.VISIBLE);
        }
        currentGpsLat = location.getLatitude();
        currentGpsLon = location.getLongitude();

        double gcjLat = currentGpsLat;
        double gcjLon = currentGpsLon;

        switch (currentCoordConvertMode) {
            case WGS_TO_GCJ:
                io.sgr.geometry.Coordinate wgsCoord = new io.sgr.geometry.Coordinate(currentGpsLat, currentGpsLon);
                io.sgr.geometry.Coordinate gcjCoordFromWgs = GeometryUtils.wgs2gcj(wgsCoord);
                gcjLat = gcjCoordFromWgs.getLat();
                gcjLon = gcjCoordFromWgs.getLng();
                Log.d(TAG, String.format(Locale.CHINA, "WGS→GCJ转换后: lat=%.6f, lon=%.6f", gcjLat, gcjLon));
                break;
            case GCJ_TO_WGS:
                io.sgr.geometry.Coordinate gcjCoordForWgs = new io.sgr.geometry.Coordinate(currentGpsLat, currentGpsLon);
                io.sgr.geometry.Coordinate wgsCoordFromGcj = GeometryUtils.gcj2wgs(gcjCoordForWgs);
                gcjLat = wgsCoordFromGcj.getLat();
                gcjLon = wgsCoordFromGcj.getLng();
                Log.d(TAG, String.format(Locale.CHINA, "GCJ→WGS转换后: lat=%.6f, lon=%.6f", gcjLat, gcjLon));
                break;
            case NO_CONVERT:
                Log.d(TAG, String.format(Locale.CHINA, "不转换，直接使用原始坐标"));
                break;
        }
        Log.d(TAG, String.format(Locale.CHINA, "最终使用: lat=%.6f, lon=%.6f", gcjLat, gcjLon));
        Log.d(TAG, String.format(Locale.CHINA, "============================"));

        String coordSystemLabel;
        switch (currentCoordConvertMode) {
            case NO_CONVERT:
                coordSystemLabel = "原始";
                break;
            case GCJ_TO_WGS:
                coordSystemLabel = "WGS";
                break;
            default:
                coordSystemLabel = "GCJ-02";
        }
        gpsLocationInfo.setText(String.format(Locale.CHINA, "坐标：%.6f, %.6f (%s)", gcjLat, gcjLon, coordSystemLabel));

        float speedMps = 0;
        float speedKmh = 0;

        long currentTime = System.currentTimeMillis();
        if (locationUpdateCount >= 2 && lastLocationTimeForSpeed > 0) {
            float[] results = new float[1];
            android.location.Location.distanceBetween(lastLocationLat, lastLocationLon, gcjLat, gcjLon, results);
            double distanceMoved = results[0];

            if (distanceMoved < STATIONARY_DISTANCE_THRESHOLD_M) {
                stationaryConsecutiveCount++;
            } else {
                stationaryConsecutiveCount = 0;
            }

            if (stationaryConsecutiveCount >= STATIONARY_CONSECUTIVE_UPDATES) {
                speedMps = 0;
                speedKmh = 0;
                Log.d(TAG, String.format(Locale.CHINA, "检测到车辆静止，速度归零"));
            } else {
                long timeDiff = currentTime - lastLocationTimeForSpeed;
                if (timeDiff > 0) {
                    speedMps = (float) (distanceMoved / (timeDiff / 1000.0));
                    speedKmh = speedMps * 3.6f;
                }
            }
        }
        locationUpdateCount++;
        lastLocationLat = gcjLat;
        lastLocationLon = gcjLon;
        lastLocationTimeForSpeed = currentTime;

        if (gpsSpeedText != null) {
            gpsSpeedText.setText(String.format(Locale.CHINA, "实速：%.0fkm/h", speedKmh));
        }

        updateGpsStatusIndicator(true);

        boolean wasInsideStation = isInsideStationRadius;
        List<BusApiClient.BusLineStation> stations = realTimeManager.getStationList();
        nearestStationName = "";
        nearestStationDistance = -1;
        nearestStationLat = 0;
        nearestStationLon = 0;
        nearestStationDirectDistance = -1;
        isInsideRadius = false;
        int currentInsideStationIndex = -1;

        for (int i = 0; i < stations.size(); i++) {
            BusApiClient.BusLineStation station = stations.get(i);
            double stationLat = station.poiOriginLat;
            double stationLon = station.poiOriginLon;
            if (stationLat == 0 && stationLon == 0) {
                continue;
            }

            double distance;
            double directDistance = -1;
            double gpsToRouteDist = -1;
            double stationToRouteDist = -1;
            double alongRouteDist = -1;

            if (routePoints != null && !routePoints.isEmpty()) {
                io.sgr.geometry.utils.RouteGeometryUtils.RouteDistanceResult distResult =
                        io.sgr.geometry.utils.RouteGeometryUtils.calculateDistances(
                                gcjLat, gcjLon, stationLat, stationLon, routePoints);

                directDistance = distResult.directDistance;
                alongRouteDist = distResult.alongRouteDistance;
                gpsToRouteDist = distResult.gpsToRouteDistance;
                stationToRouteDist = distResult.stationToRouteDistance;

                distance = alongRouteDist;
            } else {
                float[] results = new float[1];
                Location.distanceBetween(gcjLat, gcjLon, stationLat, stationLon, results);
                distance = results[0];
                directDistance = distance;
            }

            double distanceForCompare;
            if (currentDistanceMode == DistanceMode.ALONG_ROUTE && routePoints != null && !routePoints.isEmpty()) {
                distanceForCompare = alongRouteDist >= 0 ? alongRouteDist : directDistance;
            } else {
                distanceForCompare = directDistance;
            }
            if (nearestStationDistance < 0 || distanceForCompare < nearestStationDistance) {
                nearestStationDistance = distanceForCompare;
                nearestStationName = station.stationName;
                nearestStationLat = stationLat;
                nearestStationLon = stationLon;
                nearestStationDirectDistance = directDistance;
            }

            if (distanceForCompare <= stationRadius) {
                isInsideRadius = true;
                currentInsideStationIndex = i;
                int totalStations = stations.size();
                boolean isTerminalStation = i >= totalStations - 1;

                if (isTerminalStation && hasLeftTerminalStation) {
                    Log.d(TAG, "已离开过终点站，忽略此次进入终点站");
                    break;
                }

                Log.d(TAG, String.format(Locale.CHINA, "触发报站检查: isInsideStationRadius=%b, lastAnnouncedStationIndex=%d, i=%d", isInsideStationRadius, lastAnnouncedStationIndex, i));
                if (!isInsideStationRadius || lastAnnouncedStationIndex != i) {
                    isInsideStationRadius = true;
                    lastAnnouncedStationIndex = i;
                    announceStation(station.stationName, i, stations.size());
                    Log.d(TAG, "已触发报站: " + station.stationName);
                }
                break;
            }
        }

        int leavingStationFinal = -1;
        boolean isLeavingTerminal = false;
        if (!isInsideRadius && lastInsideStationIndex != -1) {
            int leavingIndex = lastInsideStationIndex;
            int totalStations = stations.size();
            boolean isTerminalStation = leavingIndex >= totalStations - 1;

            if (isTerminalStation) {
                isLeavingTerminal = true;
                hasLeftTerminalStation = true;
            }

            isInsideStationRadius = false;
            lastInsideStationIndex = -1;
            leavingStationFinal = leavingIndex;

            if (!isTerminalStation) {
                announceLeavingStation(stations.get(leavingIndex).stationName, leavingIndex, stations.size());
                Log.d(TAG, "已触发离站报站: " + stations.get(leavingIndex).stationName);
            } else {
                Log.d(TAG, "已离开终点站，不报站: " + stations.get(leavingIndex).stationName);
            }
        } else if (isInsideRadius) {
            int totalStations = stations.size();
            boolean isTerminalStation = currentInsideStationIndex >= totalStations - 1;

            if (currentInsideStationIndex >= 0 && !isTerminalStation) {
                hasLeftTerminalStation = false;
            }

            if (isTerminalStation && hasLeftTerminalStation) {
                currentInsideStationIndex = -1;
                isInsideRadius = false;
            } else {
                lastInsideStationIndex = currentInsideStationIndex;
            }
        }

        final boolean isInsideRadiusFinal = isInsideRadius;
        final int currentInsideStationIndexFinal = currentInsideStationIndex;
        final int leavingStationIndexFinal = leavingStationFinal;
        final boolean isLeavingTerminalFinal = isLeavingTerminal;

        runOnUiThread(() -> {
            if (nearestStationDistance >= 0) {
                gpsNearestStationInfo.setText(String.format(Locale.CHINA, "站点: %s (沿线%s/直线%s)",
                        nearestStationName, formatDistance(nearestStationDistance), formatDistance(nearestStationDirectDistance)));
            }
            if (busLineView != null) {
                if (isLeavingTerminalFinal) {
                    busLineView.updateGpsPosition(-1, false);
                } else if (isInsideRadiusFinal && currentInsideStationIndexFinal >= 0) {
                    busLineView.updateGpsPosition(currentInsideStationIndexFinal, true);
                } else if (!isInsideRadiusFinal && leavingStationIndexFinal >= 0) {
                    busLineView.updateGpsPosition(leavingStationIndexFinal, false);
                }
            }
            if (isLeavingTerminalFinal) {
                gpsStatusInfo.setLit(false);
                gpsStatusInfo.setText("已离终点站");
            } else if (isInsideRadiusFinal) {
                if (!wasInsideStation) {
                    isInsideStationRadius = true;
                    gpsStatusInfo.setLit(true);
                    gpsStatusInfo.setColors(0xFF00FF00, 0xFF00FF00);
                    gpsStatusInfo.setText("进站");
                }
            } else {
                if (wasInsideStation) {
                    isInsideStationRadius = false;
                    gpsStatusInfo.setLit(true);
                    gpsStatusInfo.setColors(0xFFFF0000, 0xFFFF0000);
                    gpsStatusInfo.setText("离站");
                } else {
                    gpsStatusInfo.setLit(false);
                    gpsStatusInfo.setText("站内");
                }
            }
        });
    }

    private void announceStation(String stationName, int stationIndex, int totalStations) {
        TTSUtils tts = TTSUtils.getInstance(this);
        boolean isStartStation = stationIndex == 0;
        boolean isTerminalStation = stationIndex == totalStations - 1;

        if (isStartStation) {
            String nextStationName = "";
            if (stationIndex + 1 < totalStations) {
                List<BusApiClient.BusLineStation> stations = realTimeManager.getStationList();
                nextStationName = stations.get(stationIndex + 1).stationName;
            }
            tts.playGpsStartStationAnnouncement(lineName, startStation, endStation, nextStationName);
            nextStationInfo.setText("下一站：" + nextStationName + "    Next Station:" + nextStationName);
        } else if (isTerminalStation) {
            tts.playGpsTerminalStationAnnouncement(stationName);
            nextStationInfo.setText(stationName + " 到了！We are now at " + stationName + " !");
        } else {
            tts.playGpsMiddleStationAnnouncement(stationName);
            nextStationInfo.setText(stationName + " 到了！We are now at " + stationName + " !");
        }
    }

    private void announceLeavingStation(String stationName, int stationIndex, int totalStations) {
        TTSUtils tts = TTSUtils.getInstance(this);
        boolean isTerminalStation = stationIndex + 1 >= totalStations - 1;
        String nextStationName = "";

        if (stationIndex + 1 < totalStations) {
            List<BusApiClient.BusLineStation> stations = realTimeManager.getStationList();
            nextStationName = stations.get(stationIndex + 1).stationName;
        }
        tts.playGpsLeavingStationAnnouncement(nextStationName, isTerminalStation);
        nextStationInfo.setText("下一站：" + nextStationName + "    Next Station:" + nextStationName);
    }

    private void updatePriceTips(BusApiClient.BusLineDirection lineDirection) {
        String[] priceTips = buildPriceTips(lineDirection);
        if (priceTips != null) {
            int[] priceColors = new int[priceTips.length];
            for (int i = 0; i < priceTips.length; i++) {
                priceColors[i] = TIPS_COLOR_PURPLE;
            }
            int baseCount = TIPS_TEXT_BASE.length;
            currentTipsText = new String[priceTips.length + baseCount];
            currentTipsColor = new int[currentTipsText.length];
            System.arraycopy(priceTips, 0, currentTipsText, 0, priceTips.length);
            System.arraycopy(priceColors, 0, currentTipsColor, 0, priceColors.length);
            System.arraycopy(TIPS_TEXT_BASE, 0, currentTipsText, priceTips.length, baseCount);
            System.arraycopy(TIPS_COLOR_BASE, 0, currentTipsColor, priceTips.length, baseCount);
        } else {
            currentTipsText = TIPS_TEXT_BASE;
            currentTipsColor = TIPS_COLOR_BASE;
        }
    }

    private String toChineseNumber(double number) {
        if (number == 1.0) return "一";
        if (number == 2.0) return "二";
        if (number == 3.0) return "三";
        if (number == 4.0) return "四";
        if (number == 5.0) return "五";
        if (number == 6.0) return "六";
        if (number == 7.0) return "七";
        if (number == 8.0) return "八";
        if (number == 9.0) return "九";
        if (number == 10.0) return "十";
        return formatPrice(number);
    }

    private String[] buildPriceTips(BusApiClient.BusLineDirection lineDirection) {
        double price = lineDirection.totalPrice;
        int lineType = lineDirection.lineType;
        String lineTypeName = lineDirection.lineTypeName;

        boolean isCityBus = (lineType == 1 || "城市".equals(lineTypeName));
        boolean isIntercityBus = (lineTypeName != null && lineTypeName.contains("城乡"));

        if (!isCityBus && !isIntercityBus && price > 0) {
            if (price == 1.0) {
                isCityBus = true;
            } else if (price >= 2.0) {
                isIntercityBus = true;
            }
        }

        if (isCityBus && price == 1.0) {
            return new String[]{"无人售票   票价一元"};
        } else if (isIntercityBus && price == 2.0) {
            return new String[]{"无人售票   票价二元"};
        } else if (isIntercityBus && price > 0) {
            return new String[]{"多票制二~" + toChineseNumber(price) + "元", "上下车均需刷卡扫码"};
        } else if (price == 1.0) {
            return new String[]{"无人售票   票价一元"};
        }

        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bus_line_details);
        Intent intent = getIntent();
        if (intent != null) {
            lineID = intent.getStringExtra("line_id");
            lineName = intent.getStringExtra("line_name");
            startStation = intent.getStringExtra("start_station");
            endStation = intent.getStringExtra("end_station");
            initViews();
            setupListeners();
            if (lineName == null) {
                Toast.makeText(this, "线路信息获取失败", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                initData();
            }
        }
    }

    private int findStationPositionById(String stationId) {
        if (realTimeManager != null && realTimeManager.getStationList() != null) {
            for (int i = 0; i < realTimeManager.getStationList().size(); i++) {
                if (stationId.equals(String.valueOf(realTimeManager.getStationList().get(i).id))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void initViews() {
        scheduleButton = findViewById(R.id.schedule_button);
        hideBusCardReader = findViewById(R.id.hide_bus_card_reader);
        busCardReaderView = findViewById(R.id.bus_card_reader_view);
        firstBusMarker = findViewById(R.id.first_bus_marker);
        lastBusMarker = findViewById(R.id.last_bus_marker);
        routeSummary = findViewById(R.id.route_summary);
        routeNumber = findViewById(R.id.route_number);
        swapOrientationLabel = findViewById(R.id.swap_orientation_label);
        routeNumber.setText(lineName);
        routeNumber.setTextColor(0xFF00FF00);
        routeNumber.setGravity(0);
        routeNumber.setScrollSpeed(220f);
        Typeface dottedSongti = Typeface.createFromAsset(getAssets(), "fonts/DottedSongtiSquareRegular.otf");
        routeNumber.setTypeface(dottedSongti);
        swapOrientationLabel.setTypeface(dottedSongti);
        scheduleButton.setText("时刻表");
        scheduleButton.setTypeface(dottedSongti);
        hideBusCardReader.setTypeface(dottedSongti);
        firstBusMarker.setTypeface(dottedSongti);
        lastBusMarker.setTypeface(dottedSongti);
        routeSummary.setTypeface(dottedSongti);

        int maxWidth = (int) (120 * getResources().getDisplayMetrics().density);
        int textWidth = (int) routeNumber.getTextWidth();
        if (textWidth > 0 && textWidth < maxWidth) {
            routeNumber.getLayoutParams().width = textWidth;
        }

        LinearLayout noticeBar = findViewById(R.id.notice_bar);
        noticeText = findViewById(R.id.notice_text);
        noticeBar.setVisibility(View.GONE);
        HorizontalScrollTextView endStationName = findViewById(R.id.end_station_name);
        endStationName.setGravity(2);
        endStationName.setText(endStation);
        endStationName.setTypeface(dottedSongti);
        endStationName.setScrollSpeed(220f);
        tips = findViewById(R.id.tips);
        tips.setTypeface(dottedSongti);
        tips.setGravity(1);
        startTipsAnimation();
        nextStationInfo = findViewById(R.id.next_station_info);
        nextStationInfo.setTypeface(dottedSongti);
        nextStationInfo.setTextColor(0xFFFF0000);
        nextStationInfo.setTextSize(30f);
        nextStationInfo.setText("欢迎乘坐" + lineName + "公交车");
        nextStationInfo.setScrollSpeed(220f);
        swapOrientation = findViewById(R.id.swap_orientation);
        swapOrientation.setVisibility(View.GONE);

        BorderLabel loopLineLabel = findViewById(R.id.loop_line_label);
        loopLineLabel.setVisibility(View.GONE);
        loopLineLabel.setTypeface(dottedSongti);
        loopLineLabel.setText("环线");
        loopLineLabel.setColor(0xFFFF0000);
        accessibilityIcon = findViewById(R.id.accessibility_icon);
        accessibilityIcon.setVisibility(View.GONE);
        refreshTime = findViewById(R.id.refresh_time);
        Typeface digiFont = Typeface.createFromAsset(getAssets(), "fonts/DS-DIGIB-2.ttf");
        refreshTime.setTypeface(digiFont);

        ticket = findViewById(R.id.ticket);
        ticket.setTypeface(digiFont, Typeface.NORMAL);
        updateTicketPrice();

        currentTime = findViewById(R.id.current_time);
        currentTime.setTypeface(digiFont, Typeface.NORMAL);
        timeHandler.post(timeScrollRunnable);

        errorIndicator = findViewById(R.id.error_indicator);
        errorIndicator.setOnClickListener(v -> {
            if (lastErrorDetail != null && !lastErrorDetail.isEmpty()) {
                new AlertDialog.Builder(BusLineDetailActivity.this)
                    .setTitle("错误详情")
                    .setMessage(lastErrorDetail)
                    .setPositiveButton("确定", null)
                    .show();
            } else if (lastErrorMessage != null && !lastErrorMessage.isEmpty()) {
                new AlertDialog.Builder(BusLineDetailActivity.this)
                    .setTitle("错误详情")
                    .setMessage(lastErrorMessage)
                    .setPositiveButton("确定", null)
                    .show();
            }
        });

        networkModeText = findViewById(R.id.network_mode_text);
        gpsModeText = findViewById(R.id.gps_mode_text);
        networkStatusIndicator = findViewById(R.id.network_status_indicator);
        gpsStatusIndicator = findViewById(R.id.gps_status_indicator);

        View.OnClickListener modeSwitchListener = v -> {
            if (currentAnnounceMode == AnnounceMode.GPS) {
                currentAnnounceMode = AnnounceMode.NETWORK;
            } else {
                currentAnnounceMode = AnnounceMode.GPS;
            }
            updateAnnounceModeState();
        };
        networkModeText.setOnClickListener(modeSwitchListener);
        gpsModeText.setOnClickListener(modeSwitchListener);
        updateAnnounceModeDisplay();
        gpsLocationInfo = findViewById(R.id.gps_location_info);
        gpsLocationInfo.setTypeface(dottedSongti);
        gpsLocationInfo.setOnClickListener(v -> showCoordConvertModeDialog());
        gpsNearestStationInfo = findViewById(R.id.gps_nearest_station_info);
        gpsNearestStationInfo.setTypeface(dottedSongti);
        gpsStatusInfo = findViewById(R.id.gps_status_info);
        gpsStatusInfo.setTypeface(dottedSongti);
        gpsStatusInfo.setLit(false);
        gpsStatusInfo.setText("站内");
        distanceModeInfo = findViewById(R.id.distance_mode_info);
        if (distanceModeInfo != null) {
            distanceModeInfo.setTypeface(dottedSongti);
        }
        gpsSpeedText = findViewById(R.id.gps_speed_text);
        gpsCount = findViewById(R.id.gps_count);
        if (gpsCount != null) {
            Typeface digitalTypeface = Typeface.createFromAsset(getAssets(), "fonts/DS-DIGIB-2.ttf");
            gpsCount.setTypeface(digitalTypeface);
        }
        gpsLabel = findViewById(R.id.gps_label);
        gpsLabel.setVisibility(View.GONE);
        if (distanceModeInfo != null) {
            updateDistanceModeDisplay();
            distanceModeInfo.setOnClickListener(v -> showDistanceModeDialog());
        }
        radiusSlider = findViewById(R.id.radius_slider);
        radiusMinText = findViewById(R.id.radius_min_text);
        radiusMaxText = findViewById(R.id.radius_max_text);
        if (radiusMinText != null) {
            radiusMinText.setTypeface(dottedSongti);
        }
        if (radiusMaxText != null) {
            radiusMaxText.setTypeface(dottedSongti);
        }
        if (radiusSlider != null) {
            radiusSlider.setValue((float) stationRadius);
            radiusSlider.addOnChangeListener((slider, value, fromUser) -> {
                stationRadius = value;
            });
        }

        networkModeText.setTypeface(dottedSongti);
        gpsModeText.setTypeface(dottedSongti);
        gpsSpeedText.setTypeface(dottedSongti);

        startErrorBlinkAnimation();

        // 创建格式化器，指定格式为 00:00:00
        SimpleDateFormat formatter = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        // 获取当前时间
        Date currentDate = new Date();
        refreshTime.setText(formatter.format(currentDate));
    }

    private void setupListeners() {
        swapOrientation.setOnClickListener(v -> swapDirection());
        hideBusCardReader.setOnClickListener(v -> {
            if (busCardReaderView.getVisibility() == View.VISIBLE) {
                hideBusCardReader.setLit(false);
                busCardReaderView.setVisibility(View.GONE);
            } else {
                hideBusCardReader.setLit(true);
                busCardReaderView.setVisibility(View.VISIBLE);
            }
        });

        View paymentSuccessfulView = findViewById(R.id.payment_successful);
        if (paymentSuccessfulView != null) {
            paymentSuccessfulView.setOnClickListener(v -> {
                TTSUtils ttsUtils = TTSUtils.getInstance(this);
                if (ttsUtils != null) {
                    ttsUtils.playScanCodeSuccessSound();
                }
            });
        }

        View cardAcceptedView = findViewById(R.id.card_accepted);
        if (cardAcceptedView != null) {
            cardAcceptedView.setOnClickListener(v -> {
                TTSUtils ttsUtils = TTSUtils.getInstance(this);
                if (ttsUtils != null) {
                    ttsUtils.playCardSwipeSuccessSound();
                }
            });
        }
    }

    private void initData() {
        busApiClient = new BusApiClient();

        // 查询线路通知
        queryLineNotification();

        // 查询公交线路详情
        queryBusLineDetail();
    }

    private void queryLineNotification() {
        try {
            busApiClient.queryLineNotification(lineName, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.LineNotificationResponse response) {
                    try {
                        if (response == null || response.data == null) {
                            Log.e(TAG + "-BusInfo-", "公告-无数据");
                            return;
                        }
                        if (!"200".equals(response.code)) {
                            Log.e(TAG + "-BusInfo-", "公告-状态码错误：" + response.code);
                            return;
                        }
                        if (response.data.hasNotification) {
                            runOnUiThread(() -> {
                                try {
                                    LinearLayout noticeBar = findViewById(R.id.notice_bar);
                                    noticeBar.setVisibility(View.VISIBLE);
                                    noticeText.setText(HtmlParser.htmlToFormattedText(response.data.text));
                                    noticeBar.setOnClickListener(v -> {
                                        try {
                                            showFullNoticeDialog(response.data.text);
                                        } catch (Exception e) {
                                            Log.e(TAG, "显示公告详情失败", e);
                                        }
                                    });
                                } catch (Exception e) {
                                    Log.e(TAG, "更新公告UI失败", e);
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG + "-BusInfo-", "处理公告数据失败", e);
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e(TAG + "-BusInfo-", "公告-网络请求失败：" + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "查询线路通知异常", e);
        }
    }

    private void queryBusLineDetail() {
        try {
            if (lineID != null && lineID.startsWith("test_line_")) {
                loadTestData();
                return;
            }
            
            busApiClient.queryBusLineDetail(lineName, 1, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.BusLineDetailResponse response) {
                    try {
                        cachedResponse = response;

                        if (response == null || response.data == null) {
                            Log.e(TAG + "-BusInfo-", "公交线路-无数据");
                            return;
                        }
                        if (!"200".equals(response.code)) {
                            Log.e(TAG + "-BusInfo-", "公交线路-状态码错误：" + response.code);
                            return;
                        }

                        isTwoWayLine = (response.data.up != null && response.data.down != null);

                        if (lineID != null) {
                            if (response.data.up != null && lineID.equals(response.data.up.id)) {
                                currentDirection = 1;
                            } else if (response.data.down != null && lineID.equals(response.data.down.id)) {
                                currentDirection = 2;
                            }
                        } else if (isTwoWayLine && startStation != null && endStation != null) {
                            if (startStation.equals(response.data.up.startStation) && endStation.equals(response.data.up.endStation)) {
                                currentDirection = 1;
                            } else if (startStation.equals(response.data.down.startStation) && endStation.equals(response.data.down.endStation)) {
                                currentDirection = 2;
                            }
                        } else if (!isTwoWayLine) {
                            if (response.data.up != null) {
                                currentDirection = 1;
                            } else if (response.data.down != null) {
                                currentDirection = 2;
                            }
                        }

                        runOnUiThread(() -> {
                            try {
                                if (isTwoWayLine) {
                                    swapOrientation.setVisibility(View.VISIBLE);
                                } else {
                                    BorderLabel loopLineLabel = findViewById(R.id.loop_line_label);
                                    Typeface dottedSongti = Typeface.createFromAsset(getAssets(), "fonts/DottedSongtiSquareRegular.otf");
                                    loopLineLabel.setVisibility(View.VISIBLE);
                                    loopLineLabel.setTypeface(dottedSongti);
                                    loopLineLabel.setText("环线");
                                    loopLineLabel.setColor(0xFFFF0000);
                                }

                                showDirection();
                            } catch (Exception e) {
                                Log.e(TAG, "更新线路详情UI失败", e);
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG + "-BusInfo-", "处理公交线路数据失败", e);
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e(TAG + "-BusInfo-", "获取公交线路失败: " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "查询公交线路详情异常", e);
        }
    }

    private void swapDirection() {
        if (!isTwoWayLine) return;

        currentDirection = (currentDirection == 1) ? 2 : 1;
        trackedVehiclePlate = null;
        Log.e(TAG + "-BusInfo-", "切换方向到: " + (currentDirection == 1 ? "上行" : "下行"));
        updateStartEndStations();
        showDirection();

        nextStationInfo.setText("欢迎乘坐" + lineName + "公交车");

        if (currentAnnounceMode == AnnounceMode.GPS) {
            GpsWarmingUp.removeListener(gpsActivityListener);
            lastAnnouncedStationIndex = -1;
            isInsideStationRadius = false;
            lastInsideStationIndex = -1;
            realTimeManager.stopTracking();
            realTimeManager.startTracking(getCurrentDirectionId(), this);
            GpsWarmingUp.addListener(gpsActivityListener);
            GpsWarmingUp.addSatelliteListener(satelliteCountListener);
            Location lastLocation = GpsWarmingUp.getLastKnownLocation();
            if (lastLocation != null) {
                handleGpsLocation(lastLocation);
            }
        }
    }

    private void updateStartEndStations() {
        if (cachedResponse == null || cachedResponse.data == null) return;

        BusApiClient.BusLineDirection lineDirection = getCurrentDirectionData();

        if (lineDirection != null) {
            runOnUiThread(() -> {
                HorizontalScrollTextView endStationName = findViewById(R.id.end_station_name);
                endStationName.setText(lineDirection.endStation);
                startStation = lineDirection.startStation;
                endStation = lineDirection.endStation;
            });
        }
    }

    private BusApiClient.BusLineDirection getCurrentDirectionData() {
        if (cachedResponse == null || cachedResponse.data == null) {
            return null;
        }

        if (currentDirection == 1 && cachedResponse.data.up != null) {
            return cachedResponse.data.up;
        } else if (currentDirection == 2 && cachedResponse.data.down != null) {
            return cachedResponse.data.down;
        }
        return null;
    }

    @SuppressLint("SetTextI18n")
    private void showDirection() {
        BusApiClient.BusLineDirection lineDirection = getCurrentDirectionData();
        if (lineDirection == null) {
            Log.e(TAG + "-BusInfo-", "显示方向失败: 方向数据为空");
            return;
        }

        scheduleButton.setOnClickListener(v -> showScheduleForDirection(lineDirection));

        if (realTimeManager != null) {
            realTimeManager.stopTracking();
        }

        routePoints = io.sgr.geometry.utils.RouteGeometryUtils.parseGeometry(lineDirection.geometry);

        updateAccessibilityTag(lineDirection);
        updateBusTimes(lineDirection);
        updateRouteSummary(lineDirection);
        updateTicketPrice();
        updatePriceTips(lineDirection);
        startTipsAnimation();
        setupStationList(lineDirection);
    }

    private void showScheduleForDirection(BusApiClient.BusLineDirection lineDirection) {
        try {
            busApiClient.getBusLinePlanTime(lineDirection.id, new BusApiClient.ApiCallback<BusApiClient.BusLinePlanTimeResponse>() {
                @Override
                public void onSuccess(BusApiClient.BusLinePlanTimeResponse response) {
                    try {
                        if (response == null || response.data == null) {
                            Log.e(TAG + "-BusInfo-", "时刻表-无数据");
                            runOnUiThread(() -> Toast.makeText(BusLineDetailActivity.this, "时刻表数据为空", Toast.LENGTH_SHORT).show());
                            return;
                        }
                        if (!"200".equals(response.code)) {
                            Log.e(TAG + "-BusInfo-", "时刻表-状态码错误：" + response.code);
                            runOnUiThread(() -> Toast.makeText(BusLineDetailActivity.this, "时刻表获取失败：" + response.code, Toast.LENGTH_SHORT).show());
                            return;
                        }
                        runOnUiThread(() -> {
                            try {
                                showScheduleDialog(response.data);
                            } catch (Exception e) {
                                Log.e(TAG, "显示时刻表失败", e);
                                Toast.makeText(BusLineDetailActivity.this, "显示时刻表失败", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG + "-BusInfo-", "处理时刻表数据失败", e);
                        runOnUiThread(() -> Toast.makeText(BusLineDetailActivity.this, "处理时刻表数据失败", Toast.LENGTH_SHORT).show());
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e(TAG + "-BusInfo-", "时刻表-请求失败：" + e.getMessage(), e);
                    runOnUiThread(() -> Toast.makeText(BusLineDetailActivity.this, "时刻表请求失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "获取时刻表异常", e);
        }
    }

    private void updateAccessibilityTag(BusApiClient.BusLineDirection lineDirection) {
        runOnUiThread(() -> {
            if (lineDirection.hasCj == 1) {
                accessibilityIcon.setVisibility(View.VISIBLE);
            } else {
                accessibilityIcon.setVisibility(View.GONE);
            }
        });
    }

    private void updateBusTimes(BusApiClient.BusLineDirection lineDirection) {
        runOnUiThread(() -> {
            TextView firstBusTime = findViewById(R.id.first_bus_time);
            TextView lastBusTime = findViewById(R.id.last_bus_time);
            Typeface dottedSongti = Typeface.createFromAsset(getAssets(), "fonts/DottedSongtiSquareRegular.otf");
            firstBusTime.setTypeface(dottedSongti);
            lastBusTime.setTypeface(dottedSongti);
            try {
                // 解析原始时间 00:00:00
                SimpleDateFormat inputFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                // 输出格式 00:00
                SimpleDateFormat outputFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

                Date firstDate = inputFormat.parse(lineDirection.startFirst);
                Date lastDate = inputFormat.parse(lineDirection.startLast);

                firstBusTime.setText(outputFormat.format(firstDate));
                lastBusTime.setText(outputFormat.format(lastDate));
            } catch (ParseException e) {
                e.printStackTrace();
                // 解析失败时使用原值或截取
                firstBusTime.setText(lineDirection.startFirst);
                lastBusTime.setText(lineDirection.startLast);
            }
        });
    }

    private void updateRouteSummary(BusApiClient.BusLineDirection lineDirection) {
        runOnUiThread(() -> {
            TextView routeSummary = findViewById(R.id.route_summary);
            routeSummary.setText("总里程：" + lineDirection.lineLength + " 公里");
        });
    }

    private void updateTicketPrice() {
        BusApiClient.BusLineDirection lineDirection = getCurrentDirectionData();
        double price = 1.0;
        if (lineDirection != null && lineDirection.totalPrice > 0) {
            price = lineDirection.totalPrice;
        }
        final String priceText = String.format(Locale.getDefault(), "%.2f", price);
        runOnUiThread(() -> {
            if (ticket != null) {
                ticket.setText(priceText);
            }
        });
    }

    private void setupStationList(BusApiClient.BusLineDirection lineDirection) {
        if (lineDirection.stationList == null) {
            Log.w(TAG + "-BusInfo-", "无站点数据");
            return;
        }

        runOnUiThread(() -> {
            try {
                busLineView = findViewById(R.id.bus_line_view);
                stationScrollView = findViewById(R.id.station_scroll_view);

                busLineView.setStations(lineDirection.stationList);
                busLineView.setOnStationClickListener(this::showStationDetails);
                busLineView.setOnGpsArrivalListener(this::scrollToStation);
                
                realTimeManager = new BusRealTimeManager(handler, lineDirection.stationList);
                realTimeManager.startTracking(lineDirection.id, BusLineDetailActivity.this);
                
                String stationId = getIntent().getStringExtra("station_id");
                if (stationId != null && !stationId.isEmpty()) {
                    int position = findStationPositionById(stationId);
                    if (position != -1) {
                        busLineView.setSelectedPosition(position);
                        busLineView.post(() -> {
                            int stationHeight = 120;
                            int scrollY = position * stationHeight - busLineView.getHeight() / 2;
                            stationScrollView.smoothScrollTo(0, scrollY);
                        });
                    }
                }
                
                setupEtaList();
            } catch (Exception e) {
                Log.e(TAG, "设置站点列表失败", e);
            }
        });
    }

    private void setupEtaList() {
        RecyclerView rvLiveVehicles = findViewById(R.id.rv_live_vehicles);
        rvLiveVehicles.setLayoutManager(new LinearLayoutManager(
                this,
                LinearLayoutManager.HORIZONTAL,
                false
        ));
        rvLiveVehicles.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                outRect.right = 12;
            }
        });
        busEtaAdapter = new BusEtaAdapter(etaItems, item -> {
        });
        rvLiveVehicles.setAdapter(busEtaAdapter);
    }

    private void showFullNoticeDialog(String noticeContent) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.ModalDialogTheme);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_notice, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(true);

        TextView noticeTextView = dialogView.findViewById(R.id.notice_content);
        Spanned html = Html.fromHtml(noticeContent, Html.FROM_HTML_MODE_COMPACT);
        noticeTextView.setText(html);

        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setDimAmount(0.4f);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.copyFrom(window.getAttributes());
                lp.width = (int) (getResources().getDisplayMetrics().widthPixels);
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                window.setAttributes(lp);
            }
        });

        dialog.show();
    }

    private void showScheduleDialog(List<String> scheduleTimes) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_bus_schedule, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView scheduleText = dialogView.findViewById(R.id.schedule_text);
        scheduleText.setTypeface(Typeface.MONOSPACE);
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        Date currentTime = new Date();
        int currentHour = currentTime.getHours();
        int currentMinute = currentTime.getMinutes();
        int currentTimeInMinutes = currentHour * 60 + currentMinute;

        int lastBusTimeInMinutes = -1;
        if (!scheduleTimes.isEmpty()) {
            try {
                String lastBusTime = scheduleTimes.get(scheduleTimes.size() - 1);
                String[] parts = lastBusTime.split(":");
                if (parts.length == 2) {
                    lastBusTimeInMinutes = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
                }
            } catch (Exception e) {
                Log.e(TAG, "解析末班车时间失败", e);
            }
        }

        boolean isAfterLastBus = lastBusTimeInMinutes != -1 && currentTimeInMinutes > lastBusTimeInMinutes;

        int itemsPerRow = 3;
        String timePadding = "   ";
        
        SpannableStringBuilder spannableBuilder = new SpannableStringBuilder();
        int itemsInCurrentRow = 0;
        boolean nextBusFound = false;
        int nextBusIndex = -1;

        for (int i = 0; i < scheduleTimes.size(); i++) {
            String timeStr = scheduleTimes.get(i);
            int startIndex = spannableBuilder.length();
            spannableBuilder.append(timeStr);

            if (!isAfterLastBus) {
                try {
                    String[] parts = timeStr.split(":");
                    if (parts.length == 2) {
                        int busTimeInMinutes = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
                        if (busTimeInMinutes < currentTimeInMinutes) {
                            spannableBuilder.setSpan(
                                new ForegroundColorSpan(Color.GRAY),
                                startIndex,
                                spannableBuilder.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            );
                        } else if (!nextBusFound) {
                            nextBusFound = true;
                            nextBusIndex = i;
                            spannableBuilder.setSpan(
                                new ForegroundColorSpan(Color.RED),
                                startIndex,
                                spannableBuilder.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            );
                            spannableBuilder.setSpan(
                                new StyleSpan(Typeface.BOLD),
                                startIndex,
                                spannableBuilder.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            );
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析时间失败", e);
                }
            }

            itemsInCurrentRow++;

            if (itemsInCurrentRow < itemsPerRow && i < scheduleTimes.size() - 1) {
                spannableBuilder.append(timePadding);
            }

            if (itemsInCurrentRow == itemsPerRow && i < scheduleTimes.size() - 1) {
                spannableBuilder.append("\n");
                itemsInCurrentRow = 0;
            }
        }

        scheduleText.setText(spannableBuilder);
        dialog.show();
    }

    private void scrollToStation(int stationIndex) {
        if (stationScrollView != null && stationIndex >= 0) {
            stationScrollView.post(() -> {
                int startY = 60;
                int stationHeight = 150;
                int stationCenterY = startY + stationIndex * stationHeight + stationHeight / 2;
                int scrollViewHeight = stationScrollView.getHeight();
                int scrollY = stationCenterY - scrollViewHeight / 2;
                int childHeight = stationScrollView.getChildAt(0) != null ? stationScrollView.getChildAt(0).getHeight() : 0;
                int maxScrollY = childHeight - scrollViewHeight;
                scrollY = Math.max(0, Math.min(scrollY, maxScrollY));
                stationScrollView.smoothScrollTo(0, scrollY);
            });
        }
    }

    private void showStationDetails(BusApiClient.BusLineStation station, int position) {
        if (busLineView != null) {
            busLineView.setSelectedPosition(position);
        }
        trackedVehiclePlate = null;
    }

    @SuppressLint("DefaultLocale")
    private String formatPrice(double price) {
        if (price == (long) price) {
            return String.format("%d", (long) price);
        } else {
            String formatted = String.format("%.2f", price);
            return formatted.replaceAll("0*$", "").replaceAll("\\.$", "");
        }
    }

    private String formatPrice(Double price) {
        if (price == null) {
            return "未知";
        }
        return formatPrice(price.doubleValue());
    }

    @Override
    public void onBusPositionsUpdated(List<BusApiClient.BusPosition> positions) {
        if (currentAnnounceMode == AnnounceMode.GPS) {
            return;
        }
        runOnUiThread(() -> {
            hideErrorIndicator();
            updateRefreshTimeDisplay();
            updateNetworkStatusIndicator(true);

            if (!positions.isEmpty()) {
                if (busLineView != null) {
                    busLineView.updateBusPositions(positions);
                }

                int selectedStationIndex = busLineView != null ? busLineView.getSelectedPosition() : -1;
                if (selectedStationIndex != -1) {
                    if (trackedVehiclePlate == null) {
                        for (BusApiClient.BusPosition vehicle : positions) {
                            int vehicleStationIndex = vehicle.currentStationOrder - 1;
                            if (vehicleStationIndex == selectedStationIndex) {
                                trackedVehiclePlate = vehicle.plateNumber;
                                break;
                            }
                        }
                    }
                    if (trackedVehiclePlate != null) {
                        for (BusApiClient.BusPosition vehicle : positions) {
                            if (trackedVehiclePlate.equals(vehicle.plateNumber)) {
                                int vehicleStationIndex = vehicle.currentStationOrder - 1;
                                if (vehicleStationIndex != selectedStationIndex) {
                                    busLineView.setSelectedPosition(vehicleStationIndex);
                                    selectedStationIndex = vehicleStationIndex;
                                }
                                break;
                            }
                        }
                    }
                    updateEtaItems(positions, selectedStationIndex);
                    checkAndAnnounceArrival(positions, selectedStationIndex);
                }
            }
        });
    }

    private void updateRefreshTimeDisplay() {
        SimpleDateFormat formatter = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        refreshTime.setText(formatter.format(new Date()));
    }

    private void clearEtaItems() {
        if (busEtaAdapter == null) {
            return;
        }
        etaItems.clear();
        busEtaAdapter.notifyDataSetChanged();
    }

    private void updateEtaItems(List<BusApiClient.BusPosition> positions, int selectedStationIndex) {
        if (busEtaAdapter == null) {
            return;
        }
        
        etaItems.clear();
        List<BusEtaItem> tempList = new ArrayList<>();

        for (BusApiClient.BusPosition vehicle : positions) {
            int vehicleStationIndex = vehicle.currentStationOrder - 1;

            if (vehicleStationIndex < 0 || vehicleStationIndex >= realTimeManager.getStationList().size()) {
                continue;
            }
            Log.w(TAG, vehicleStationIndex + "/" + selectedStationIndex);
            if (vehicleStationIndex < selectedStationIndex) {
                int totalDistanceMeters = vehicle.distanceToNext;
                for (int stationIndex = vehicleStationIndex + 1; stationIndex < selectedStationIndex + 1; stationIndex++) {
                    if (stationIndex < realTimeManager.getStationList().size()) {
                        totalDistanceMeters += realTimeManager.getStationList().get(stationIndex).lastDistance;
                    }
                }
                int etaMinutes = realTimeManager.busAverageSpeed > 0 ?
                        Math.round((float) totalDistanceMeters / realTimeManager.busAverageSpeed) : 0;

                tempList.add(new BusEtaItem(selectedStationIndex - vehicleStationIndex, etaMinutes, totalDistanceMeters, vehicle.isArrived, vehicle.plateNumber));
            }
        }

        Collections.reverse(tempList);
        etaItems.addAll(tempList);
        busEtaAdapter.notifyDataSetChanged();
    }

    @SuppressLint("SetTextI18n")
    private void checkAndAnnounceArrival(List<BusApiClient.BusPosition> positions, int selectedStationIndex) {
        // 创建格式化器，指定格式为 00:00:00
        SimpleDateFormat formatter = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        // 获取当前时间
        Date currentDate = new Date();
        refreshTime.setText(formatter.format(currentDate));
        BusApiClient.BusPosition nearestVehicle = null;
        for (BusApiClient.BusPosition vehicle : positions) {
            if (selectedStationIndex >= vehicle.currentStationOrder) {
                nearestVehicle = vehicle;
            }
        }
        if (nearestVehicle != null && lastVoiceStationOrder > 0 && (nearestVehicle.currentStationOrder - lastVoiceStationOrder) > 1) {
            boolean shouldAnnounceSkip = false;
            
            if (lastVehicleWasArrived && !nearestVehicle.isArrived) {
                shouldAnnounceSkip = true;
            }
            
            if (shouldAnnounceSkip) {
                String skipStationCn = "因网络延迟导致站点更新跳站，请注意确认车辆位置。";
                String skipStationEn = "Due to network delay, station updates may skip. Please verify vehicle location.";
                String skipStationCombined = skipStationCn + " " + skipStationEn;
                
                TTSUtils tts = TTSUtils.getInstance(this);
                tts.speak(skipStationCombined, "skip_announcement");
            }
        }
        
        if (nearestVehicle != null) {
            lastVehicleWasArrived = nearestVehicle.isArrived;
        }
        
        if (nearestVehicle != null && lastVoiceStationOrder != nearestVehicle.currentStationOrder) {
            int nextStationIndex = nearestVehicle.currentStationOrder + 1;
            if (nextStationIndex > 0 && nextStationIndex <= realTimeManager.getStationList().size()) {
                BusApiClient.BusLineStation nextStation = realTimeManager.getStationList().get(nextStationIndex - 1);

                TTSUtils tts = TTSUtils.getInstance(this);
                tts.playLineDetailAnnouncement(lineName, startStation, endStation, nextStation.stationName);
                nextStationInfo.setText("下一站：" + nextStation.stationName + "    Next Station:" + nextStation.stationName);
                lastVoiceStationOrder = nearestVehicle.currentStationOrder;
            }
        }

    }

    int lastVoiceStationOrder;
    boolean lastVehicleWasArrived = false;
    private String trackedVehiclePlate = null;

    private void startErrorBlinkAnimation() {
        errorBlinkAnimator = ValueAnimator.ofFloat(0f, 1f);
        errorBlinkAnimator.setDuration(1000);
        errorBlinkAnimator.setRepeatCount(ValueAnimator.INFINITE);
        errorBlinkAnimator.setRepeatMode(ValueAnimator.RESTART);
        errorBlinkAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            if (errorIndicator.getVisibility() == View.VISIBLE) {
                float alpha = progress < 0.5f ? 1f : 0f;
                errorIndicator.setAlpha(alpha);
            }
        });
        errorBlinkAnimator.start();
    }

    private void showErrorIndicator() {
        if (errorIndicator != null) {
            errorIndicator.setVisibility(View.VISIBLE);
            errorIndicator.setAlpha(1f);
        }
        updateNetworkStatusIndicator(false);
    }

    private void hideErrorIndicator() {
        if (errorIndicator != null) {
            errorIndicator.setVisibility(View.GONE);
        }
    }

    private void updateNetworkStatusIndicator(boolean isOnline) {
        if (networkStatusIndicator != null) {
            if (isOnline) {
                networkStatusIndicator.setTextColor(0xFF00FF00);
            } else {
                networkStatusIndicator.setTextColor(0xFF555555);
            }
        }
    }

    private void updateGpsStatusIndicator(boolean isGpsEnabled) {
        if (gpsStatusIndicator == null) {
            return;
        }
        stopGpsBlinkAnimation();
        if (isGpsEnabled) {
            if (isGpsSignalNormal) {
                gpsStatusIndicator.setTextColor(0xFF00FF00);
            } else {
                startGpsBlinkAnimation();
            }
        } else {
            gpsStatusIndicator.setTextColor(0xFF555555);
        }
    }

    private void startGpsBlinkAnimation() {
        if (gpsBlinkAnimator != null) {
            gpsBlinkAnimator.cancel();
        }
        gpsBlinkAnimator = ValueAnimator.ofFloat(0f, 1f);
        gpsBlinkAnimator.setDuration(500);
        gpsBlinkAnimator.setRepeatCount(ValueAnimator.INFINITE);
        gpsBlinkAnimator.setRepeatMode(ValueAnimator.RESTART);
        gpsBlinkAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            float alpha = progress < 0.5f ? 1f : 0f;
            if (gpsStatusIndicator != null) {
                gpsStatusIndicator.setAlpha(alpha);
            }
        });
        gpsBlinkAnimator.start();
    }

    private void stopGpsBlinkAnimation() {
        if (gpsBlinkAnimator != null) {
            gpsBlinkAnimator.cancel();
            gpsBlinkAnimator = null;
        }
        if (gpsStatusIndicator != null) {
            gpsStatusIndicator.setAlpha(1f);
        }
    }

    @Override
    public void onError(String message) {
        String userMessage = extractUserFriendlyMessage(message);
        String detailMessage = buildDetailMessage(message, getCurrentDirectionData());

        runOnUiThread(() -> {
            lastErrorMessage = userMessage;
            lastErrorDetail = detailMessage;
            showErrorIndicator();
        });
    }

    private String extractUserFriendlyMessage(String message) {
        if (message == null) return "未知错误";
        if (message.contains("SocketTimeoutException") || message.contains("timeout")) {
            return "网络超时，请检查网络连接";
        }
        if (message.contains("UnknownHostException")) {
            return "无法解析服务器地址";
        }
        if (message.contains("ConnectException")) {
            return "无法连接到服务器";
        }
        if (message.contains("UnrecognizedPropertyException") || message.contains("JSON")) {
            return "数据解析错误";
        }
        if (message.contains("API调用失败")) {
            return "API请求失败";
        }
        if (message.contains("HTTP错误")) {
            return "服务器响应错误";
        }
        return "获取实时数据失败";
    }

    private String buildDetailMessage(String message, BusApiClient.BusLineDirection lineDirection) {
        StringBuilder sb = new StringBuilder();
        sb.append("技术详情:\n");
        sb.append(message).append("\n\n");
        if (lineDirection != null) {
            sb.append("线路ID: ").append(lineDirection.id).append("\n");
        }
        if (cachedResponse != null) {
            sb.append("线路详情API状态: ").append(cachedResponse.code).append("\n");
            sb.append("线路详情返回信息: ").append(cachedResponse.returnInfo).append("\n");
        }
        return sb.toString();
    }

    private void loadTestData() {
        try {
            List<BusApiClient.BusLineStation> testStations = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                BusApiClient.BusLineStation station = new BusApiClient.BusLineStation();
                station.id = "test_station_" + String.format("%03d", i);
                station.stationName = "测试站点" + i + "号";
                station.stationOrder = i;
                station.lastDistance = 500;
                
                if (i == 4) {
                    station.status = BusApiClient.BusLineStation.StationStatus.NEXT_STATION;
                    station.plateNumber = "京A12345";
                } else if (i < 4) {
                    station.status = BusApiClient.BusLineStation.StationStatus.PASSED;
                } else {
                    station.status = BusApiClient.BusLineStation.StationStatus.NORMAL;
                }
                
                testStations.add(station);
            }

            runOnUiThread(() -> {
                try {
                    routeNumber.setText(lineName);
                    HorizontalScrollTextView endStationName = findViewById(R.id.end_station_name);
                    endStationName.setText(endStation);
                    busLineView = findViewById(R.id.bus_line_view);
                    stationScrollView = findViewById(R.id.station_scroll_view);
                    
                    busLineView.setStations(testStations);
                    busLineView.setOnStationClickListener(this::showStationDetails);

                    realTimeManager = new BusRealTimeManager(handler, testStations);
                    realTimeManager.startTracking("test_line_001", BusLineDetailActivity.this);

                    String stationId = getIntent().getStringExtra("station_id");
                    if (stationId != null && !stationId.isEmpty()) {
                        int position = 4;
                        busLineView.setSelectedPosition(position);
                    }

                    setupEtaList();
                } catch (Exception e) {
                    Log.e(TAG, "加载测试数据失败", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "创建测试数据失败", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (realTimeManager != null) {
            realTimeManager.startTracking(getCurrentDirectionId(), this);
        }
        if (currentAnnounceMode == AnnounceMode.GPS) {
            GpsWarmingUp.addListener(gpsActivityListener);
            GpsWarmingUp.addSatelliteListener(satelliteCountListener);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (realTimeManager != null) {
            //realTimeManager.stopTracking();
        }
        if (currentAnnounceMode == AnnounceMode.GPS) {
            GpsWarmingUp.removeListener(gpsActivityListener);
            GpsWarmingUp.removeSatelliteListener(satelliteCountListener);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (errorBlinkAnimator != null) {
            errorBlinkAnimator.cancel();
            errorBlinkAnimator = null;
        }
        if (gpsBlinkAnimator != null) {
            gpsBlinkAnimator.cancel();
            gpsBlinkAnimator = null;
        }
        if(realTimeManager != null){
            realTimeManager.stopTracking();
        }
        GpsWarmingUp.removeListener(gpsActivityListener);
        GpsWarmingUp.removeSatelliteListener(satelliteCountListener);
        GpsWarmingUp.stopWarmingUp();
        realTimeManager = null;
        if (tipsHandler != null) {
            tipsHandler.removeCallbacksAndMessages(null);
        }
    }

    private String getCurrentDirectionId() {
        BusApiClient.BusLineDirection direction = getCurrentDirectionData();
        return direction != null ? direction.id : "";
    }
}