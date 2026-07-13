package org.zjfgh.zhujibus;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.location.Location;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.LocationListener;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.amap.api.maps.AMap;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.LatLng;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.sgr.geometry.Coordinate;
import io.sgr.geometry.utils.RouteGeometryUtils;

public class CameraActivity extends AppCompatActivity {
    private static final String TAG = "CameraActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    private TextureView textureView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private String cameraId;
    private Size previewSize = new Size(1920, 1080);
    private Size videoSize = new Size(1920, 1080);
    private int selectedVideoFps = 30;
    private Range<Integer> selectedFpsRange = new Range<>(30, 30);
    private boolean useHighSpeedSession = false;
    private int sensorOrientation = 90;

    // 录制相关
    private TextView btnRecord;
    private boolean isRecording = false;
    private Uri currentVideoUri;
    private String currentTempFilePath;
    private ParcelFileDescriptor currentOutputPfd;

    // 设置面板相关
    private View settingsPanelContainer;
    private View settingsPanel;
    private TextView btnSettings;
    private Spinner spinnerResolution;
    private SeekBar seekbarVideoBitrate;
    private TextView tvVideoBitrateValue;
    private Spinner spinnerAudioBitrate;
    private Spinner spinnerSampleRate;
    private Spinner spinnerAudioChannels;
    private CheckBox checkboxShowMap;

    // 录制参数（默认值）
    private int selectedVideoBitrate = 30_000_000;
    private int selectedAudioBitrate = 128_000;
    private int selectedSampleRate = 44100;
    private int selectedAudioChannels = 2;

    private boolean isVideoBitrateManuallyAdjusted = false;

    private List<Size> availableVideoSizes = new ArrayList<>();
    private List<Integer> availableVideoFpsList = new ArrayList<>();
    private List<Range<Integer>> availableVideoFpsRanges = new ArrayList<>();
    private List<Boolean> availableHighSpeedFlags = new ArrayList<>();
    private List<String> resolutionDisplayNames = new ArrayList<>();

    private final int[] AUDIO_BITRATES = {64_000, 96_000, 128_000, 192_000, 256_000, 320_000};
    private final String[] AUDIO_BITRATE_NAMES = {"64 kbps", "96 kbps", "128 kbps", "192 kbps", "256 kbps", "320 kbps"};

    // ===== 地图相关 =====
    private FrameLayout mapContainer;
    private TextureMapView mapView;
    private AmapNavigationView navigationView;
    private TextView tvMapStatus;
    private boolean isMapEnabled = true;

    // ===== 线路信息面板相关 =====
    private FrameLayout infoPanelContainer;
    private CheckBox checkboxShowInfoPanel;
    private boolean isInfoPanelEnabled = false;
    private CheckBox checkboxShowCoordinate;
    private boolean isCoordinateEnabled = true;
    private View povCoordinateContainer;

    // ===== 线路参数与独立加载数据 =====
    private String lineId;
    private String lineName;
    private String startStationName;
    private String endStationName;
    private int direction = 1;
    private BusApiClient busApiClient;
    private BusApiClient.BusLineDetailResponse lineDetailResponse;
    private BusApiClient.BusLineDirection currentLineDirection;
    private List<BusApiClient.BusLineStation> stationList = new ArrayList<>();
    private List<Coordinate> routePoints = new ArrayList<>();

    // ===== GLES 渲染 =====
    private GLESVideoRenderer glesRenderer;
    private Surface glCameraSurface;
    private Surface glMapSurface;

    private Handler mapUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable mapUpdateRunnable;
    private long overlayTimelineStartMs = 0;
    private final List<OfflineVideoComposer.OverlayFrame> overlayTimeline = new ArrayList<>();
    private static final int MAP_UPDATE_INTERVAL = 40;
    private static final float POV_MAP_ZOOM = 19f;
    private static final float POV_MAP_TILT = 0f;
    private static final float POV_RECORD_MAP_CORNER_RADIUS_DP = 8f;

    // ===== 录制编码器 =====
    private android.media.MediaCodec videoEncoder;
    private android.media.MediaMuxer mediaMuxer;
    private MediaRecorder mediaRecorder;
    private Surface encoderSurface;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private volatile boolean isMuxerStarted = false;
    private final Object muxerLock = new Object();
    private android.media.MediaCodec audioEncoder;
    private long videoFrameCount = 0;
    private long audioFrameCount = 0;
    private long firstFrameTimeUs = 0;

    private android.media.AudioRecord audioRecord;
    private volatile boolean isAudioRecording = false;
    private Thread audioRecordingThread;

    // ===== 独立进出站检测器 =====
    private PovStationDetector povDetector;
    private PovStationDetector.Callback povCallback;
    private String lastPovStationTips;
    private String lastPovStationName;
    private String lastPovGpsText;
    private String lastPovSpeedText;
    private float smoothedPovSpeed = -1f;
    private static final float SPEED_EMA_ALPHA = 0.25f;
    private static final float SPEED_JUMP_THRESHOLD = 40f;

    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (povDetector != null) {
                povDetector.onGpsLocation(location);
            }
            updatePovRealtimeSpeed(location);
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    private final GpsWarmingUp.SatelliteCountListener satelliteCountListener = (used, total) -> {
        // 更新卫星信息（如果UI上有对应控件）
    };

    // ===== 生命周期 =====

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_camera);
        readRouteParams();
        busApiClient = new BusApiClient();

        textureView = findViewById(R.id.texture_camera_preview);
        textureView.post(this::updatePreviewViewSize);
        findViewById(R.id.btn_close_camera).setOnClickListener(v -> finish());

        btnRecord = findViewById(R.id.btn_record_camera);
        btnRecord.setOnClickListener(v -> onRecordButtonClick());

        initSettingsPanel();
        updateInfoPanelData();
        startDoorAnimation();
        initMapView(savedInstanceState);
        initPovDetector();
        loadRouteInfo();

        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), REQUEST_CAMERA_PERMISSION);
        } else {
            setupCamera();
        }
    }

    /**
     * 更新预览区域和叠加层的位置。
     * 修改点：面板固定在底部，地图固定在右下角且位于面板上方。
     */
    private void updatePreviewViewSize() {
        if (textureView == null) return;
        View parent = (View) textureView.getParent();
        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        if (parentWidth == 0 || parentHeight == 0) return;

        int previewWidth = parentWidth;
        int previewHeight = parentWidth * 9 / 16;
        if (previewHeight > parentHeight) {
            previewHeight = parentHeight;
            previewWidth = parentHeight * 16 / 9;
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) textureView.getLayoutParams();
        params.width = previewWidth;
        params.height = previewHeight;
        params.gravity = android.view.Gravity.CENTER;
        textureView.setLayoutParams(params);
        configureTransform(previewWidth, previewHeight);

        int offsetX = (parentWidth - previewWidth) / 2;
        int offsetY = (parentHeight - previewHeight) / 2;
        float density = getResources().getDisplayMetrics().density;
        int marginPx = (int) (2 * density);

        // 1. 线路信息面板 —— 底部，左对齐，宽度等于预览宽度
        if (infoPanelContainer != null) {
            FrameLayout.LayoutParams infoParams = (FrameLayout.LayoutParams) infoPanelContainer.getLayoutParams();
            infoParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
            infoParams.leftMargin = offsetX;
            infoParams.bottomMargin = parentHeight - (offsetY + previewHeight);
            infoParams.width = previewWidth;
            infoParams.height = FrameLayout.LayoutParams.WRAP_CONTENT;
            infoPanelContainer.setLayoutParams(infoParams);
        }

        // 2. 小地图 —— 右下角，位于信息面板上方（如果面板可见）
        if (mapContainer != null) {
            int mapSizePx = (int) (100 * density);
            FrameLayout.LayoutParams mapParams = (FrameLayout.LayoutParams) mapContainer.getLayoutParams();
            mapParams.width = mapSizePx;
            mapParams.height = mapSizePx;
            mapParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
            mapParams.rightMargin = offsetX + marginPx + 15;

            // 基础底部边距：预览区域底部到父容器底部的距离 + 间距
            int bottomMargin = parentHeight - (offsetY + previewHeight) + marginPx;

            // 如果信息面板可见，则在其上方再留出间距
            if (infoPanelContainer != null && infoPanelContainer.getVisibility() == View.VISIBLE) {
                int infoHeight = infoPanelContainer.getHeight();
                if (infoHeight > 0) {
                    bottomMargin += infoHeight + marginPx;
                }
            }
            mapParams.bottomMargin = bottomMargin;
            mapContainer.setLayoutParams(mapParams);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startBackgroundThread();
            if (textureView != null && textureView.isAvailable()) {
                openCamera(false);
            }
        }
        if (navigationView != null) {
            navigationView.onResume();
        }
        if (!GpsWarmingUp.isWarmingUp()) {
            GpsWarmingUp.startWarmingUp(this);
        }
        updateDetectorRouteData();
        GpsWarmingUp.addListener(gpsListener);
        GpsWarmingUp.addSatelliteListener(satelliteCountListener);
        Location last = GpsWarmingUp.getLastKnownLocation();
        if (last != null && povDetector != null) {
            povDetector.onGpsLocation(last);
            updatePovRealtimeSpeed(last);
        }
    }

    @Override
    protected void onPause() {
        GpsWarmingUp.removeSatelliteListener(satelliteCountListener);
        closeCamera();
        if (navigationView != null) {
            navigationView.onPause();
        }
        GpsWarmingUp.removeListener(gpsListener);
        GpsWarmingUp.removeSatelliteListener(satelliteCountListener);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopDoorAnimation();
        closeCamera();
        destroyMapAndLocation();
        if (povDetector != null) {
            povDetector.destroy();
            povDetector = null;
        }
        if (busApiClient != null) {
            busApiClient.cancelAllRequests();
        }
        super.onDestroy();
    }

    // ===== 初始化 =====

    private void readRouteParams() {
        android.content.Intent intent = getIntent();
        if (intent == null) return;
        lineId = intent.getStringExtra("line_id");
        lineName = intent.getStringExtra("line_name");
        startStationName = intent.getStringExtra("start_station");
        endStationName = intent.getStringExtra("end_station");
        direction = intent.getIntExtra("direction", 1);
    }

    private void loadRouteInfo() {
        if (lineName == null || lineName.isEmpty() || busApiClient == null) {
            Log.w(TAG, "缺少线路名称，POV路线信息不加载");
            return;
        }
        busApiClient.queryBusLineDetail(lineName, 1, new BusApiClient.ApiCallback<>() {
            @Override
            public void onSuccess(BusApiClient.BusLineDetailResponse response) {
                lineDetailResponse = response;
                selectCurrentDirection();
                runOnUiThread(() -> {
                    updateInfoPanelData();
                    drawBusLineRoute();
                    updateDetectorRouteData();
                });
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Log.e(TAG, "POV线路信息加载失败", e);
            }
        });
    }

    private void selectCurrentDirection() {
        if (lineDetailResponse == null || lineDetailResponse.data == null) return;
        BusApiClient.BusLineDirection selected = null;
        if (lineId != null) {
            if (lineDetailResponse.data.up != null && lineId.equals(lineDetailResponse.data.up.id)) {
                selected = lineDetailResponse.data.up;
                direction = 1;
            } else if (lineDetailResponse.data.down != null && lineId.equals(lineDetailResponse.data.down.id)) {
                selected = lineDetailResponse.data.down;
                direction = 2;
            }
        }
        if (selected == null && startStationName != null && endStationName != null) {
            if (lineDetailResponse.data.up != null
                    && startStationName.equals(lineDetailResponse.data.up.startStation)
                    && endStationName.equals(lineDetailResponse.data.up.endStation)) {
                selected = lineDetailResponse.data.up;
                direction = 1;
            } else if (lineDetailResponse.data.down != null
                    && startStationName.equals(lineDetailResponse.data.down.startStation)
                    && endStationName.equals(lineDetailResponse.data.down.endStation)) {
                selected = lineDetailResponse.data.down;
                direction = 2;
            }
        }
        if (selected == null) {
            selected = direction == 2 ? lineDetailResponse.data.down : lineDetailResponse.data.up;
        }
        if (selected == null) {
            selected = lineDetailResponse.data.up != null ? lineDetailResponse.data.up : lineDetailResponse.data.down;
        }
        currentLineDirection = selected;
        if (selected == null) return;
        lineId = selected.id;
        startStationName = selected.startStation;
        endStationName = selected.endStation;
        stationList.clear();
        if (selected.stationList != null) {
            stationList.addAll(selected.stationList);
        }
        routePoints.clear();
        if (selected.geometry != null && !selected.geometry.isEmpty()) {
            List<Coordinate> parsed = RouteGeometryUtils.parseGeometry(selected.geometry);
            if (parsed != null) {
                routePoints.addAll(parsed);
            }
        }
    }

    private void updateDetectorRouteData() {
        if (povDetector != null) {
            povDetector.setStationRadius(30.0, 80.0);
            povDetector.setRouteData(stationList, routePoints);
        }
        updateInitialNextStationDisplay();
    }

    private void initPovDetector() {

        povDetector = new PovStationDetector();
        povDetector.setCallback(povCallback = new PovStationDetector.Callback() {
            @Override
            public void onStationStatusChanged(boolean isAtStation, int stationIndex, String stationName) {
                runOnUiThread(() -> updateNextStationDisplay(isAtStation, stationName));
            }
            @Override
            public void onNearestStationUpdated(String name, double distance, double directDistance) {
            }
            @Override
            public void onEatUpdated(String eatText) {
            }
            @Override
            public void onGpsUpdated(double lat, double lng) {
                @SuppressLint("DefaultLocale") String gpsText = String.format("%.7f,%.7f", lng, lat);
                if (gpsText.equals(lastPovGpsText)) return;
                lastPovGpsText = gpsText;
                runOnUiThread(() -> {
                    TextView tvCoord = findViewById(R.id.tv_pov_gps_coordinate);
                    if (tvCoord != null) {
                        tvCoord.setText(gpsText);
                    }
                });
            }
        });

        updateDetectorRouteData();
        GpsWarmingUp.addListener(gpsListener);
        GpsWarmingUp.addSatelliteListener(satelliteCountListener);
        Location last = GpsWarmingUp.getLastKnownLocation();
        if (last != null) {
            povDetector.onGpsLocation(last);
            updatePovRealtimeSpeed(last);
        }
    }

    private String formatDistance(double meters) {
        if (meters >= 1000) return String.format("%.1fkm", meters / 1000);
        return String.format("%.0fm", meters);
    }

    private void updatePovRealtimeSpeed(Location location) {
        if (location == null) return;
        if (!location.hasSpeed()) {
            applyPovSpeedUpdate(0f);
            return;
        }
        float rawSpeedKmh = location.getSpeed() * 3.6f;
        if (rawSpeedKmh < 0f) rawSpeedKmh = 0f;

        if (smoothedPovSpeed < 0f) {
            smoothedPovSpeed = rawSpeedKmh;
        } else if (Math.abs(rawSpeedKmh - smoothedPovSpeed) > SPEED_JUMP_THRESHOLD) {
            return;
        } else {
            smoothedPovSpeed = smoothedPovSpeed + SPEED_EMA_ALPHA * (rawSpeedKmh - smoothedPovSpeed);
        }
        applyPovSpeedUpdate(smoothedPovSpeed);
    }

    private void applyPovSpeedUpdate(float speedKmh) {
        if (speedKmh < 0f) speedKmh = 0f;
        int display = Math.round(speedKmh);
        if (speedKmh < 0.5f) display = 0;
        String speedText = display + "km/h";
        if (speedText.equals(lastPovSpeedText)) return;
        lastPovSpeedText = speedText;
        runOnUiThread(() -> {
            TextView speedView = findViewById(R.id.pov_realtime_speed);
            if (speedView != null) {
                speedView.setText(speedText);
            }
        });
    }

    private void updateInitialNextStationDisplay() {
        TextView povNextStationTips = findViewById(R.id.pov_next_station_tips);
        HorizontalScrollTextView povNextStationName = findViewById(R.id.pov_next_station_name);
        if (povNextStationTips == null || povNextStationName == null) return;

        lastPovStationTips = "下一站:";
        lastPovStationName = "等待进站";
        povNextStationTips.setText(lastPovStationTips);
        povNextStationName.setText(lastPovStationName);
    }

    private void updateNextStationDisplay(boolean isAtStation, String stationName) {
        TextView povNextStationTips = findViewById(R.id.pov_next_station_tips);
        HorizontalScrollTextView povNextStationName = findViewById(R.id.pov_next_station_name);
        if (povNextStationTips == null || povNextStationName == null) return;
        if (stationName == null || stationName.isEmpty()) return;

        String startStation = startStationName;
        String endStation = endStationName;

        String tips;
        if (isAtStation) {
            if (stationName != null && stationName.equals(startStation)) {
                tips = "起点站:";
            } else if (stationName != null && stationName.equals(endStation)) {
                tips = "终点站:";
            } else {
                tips = "当前站:";
            }
        } else {
            tips = "下一站:";
        }

        if (tips.equals(lastPovStationTips) && stationName.equals(lastPovStationName)) {
            return;
        }
        lastPovStationTips = tips;
        lastPovStationName = stationName;
        povNextStationTips.setText(tips);
        povNextStationName.setText(stationName);
    }

    private void initSettingsPanel() {
        settingsPanelContainer = findViewById(R.id.settings_panel_container);
        settingsPanel = findViewById(R.id.settings_panel);
        btnSettings = findViewById(R.id.btn_settings_camera);
        spinnerResolution = findViewById(R.id.spinner_resolution);
        seekbarVideoBitrate = findViewById(R.id.seekbar_video_bitrate);
        tvVideoBitrateValue = findViewById(R.id.tv_video_bitrate_value);
        spinnerAudioBitrate = findViewById(R.id.spinner_audio_bitrate);
        spinnerSampleRate = findViewById(R.id.spinner_sample_rate);
        spinnerAudioChannels = findViewById(R.id.spinner_audio_channels);
        checkboxShowMap = findViewById(R.id.checkbox_show_map);
        checkboxShowInfoPanel = findViewById(R.id.checkbox_show_info_panel);
        checkboxShowCoordinate = findViewById(R.id.checkbox_show_coordinate);
        povCoordinateContainer = findViewById(R.id.pov_coordinate_container);
        infoPanelContainer = findViewById(R.id.pov_info_panel_container);

        checkboxShowInfoPanel.setChecked(true);
        isInfoPanelEnabled = true;
        checkboxShowMap.setChecked(true);
        isMapEnabled = true;
        checkboxShowCoordinate.setChecked(true);
        isCoordinateEnabled = true;
        updateMapVisibility();
        updateInfoPanelVisibility();
        updateCoordinateVisibility();

        checkboxShowInfoPanel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isInfoPanelEnabled = isChecked;
            updateInfoPanelVisibility();
        });
        btnSettings.setOnClickListener(v -> showSettingsPanel());
        findViewById(R.id.btn_close_settings).setOnClickListener(v -> hideSettingsPanel());
        settingsPanelContainer.setOnClickListener(v -> hideSettingsPanel());
        settingsPanel.setOnClickListener(v -> {});
        findViewById(R.id.btn_apply_settings).setOnClickListener(v -> hideSettingsPanel());

        seekbarVideoBitrate.setMax(100);
        seekbarVideoBitrate.setProgress(30);
        seekbarVideoBitrate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 1) progress = 1;
                selectedVideoBitrate = progress * 1_000_000;
                tvVideoBitrateValue.setText(progress + " Mbps");
                if (fromUser) {
                    isVideoBitrateManuallyAdjusted = true;
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        ArrayAdapter<String> audioBitrateAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, AUDIO_BITRATE_NAMES);
        audioBitrateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAudioBitrate.setAdapter(audioBitrateAdapter);
        spinnerAudioBitrate.setSelection(2);
        spinnerAudioBitrate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedAudioBitrate = AUDIO_BITRATES[position];
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        String[] sampleRates = {"44.1 kHz", "48 kHz"};
        ArrayAdapter<String> sampleRateAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, sampleRates);
        sampleRateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSampleRate.setAdapter(sampleRateAdapter);
        spinnerSampleRate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedSampleRate = position == 0 ? 44100 : 48000;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        String[] audioChannels = {"单声道", "立体声"};
        ArrayAdapter<String> audioChannelsAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, audioChannels);
        audioChannelsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAudioChannels.setAdapter(audioChannelsAdapter);
        spinnerAudioChannels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedAudioChannels = position == 0 ? 1 : 2;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        spinnerAudioChannels.setSelection(1);

        checkboxShowMap.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isMapEnabled = isChecked;
            updateMapVisibility();
        });

        checkboxShowInfoPanel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isInfoPanelEnabled = isChecked;
            updateInfoPanelVisibility();
        });

        checkboxShowCoordinate.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isCoordinateEnabled = isChecked;
            updateCoordinateVisibility();
        });
    }

    private void showSettingsPanel() {
        if (isRecording) {
            Toast.makeText(this, "录制中无法调整参数", Toast.LENGTH_SHORT).show();
            return;
        }
        settingsPanelContainer.setVisibility(View.VISIBLE);
    }

    private void hideSettingsPanel() {
        settingsPanelContainer.setVisibility(View.GONE);
    }

    // ===== 摄像头 =====

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            boolean allGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                }
            }
            if (allGranted) {
                setupCamera();
            } else {
                Toast.makeText(this, "摄像头和录音权限未授予", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void setupCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "摄像头服务不可用", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (orientation != null) sensorOrientation = orientation;

                    StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        Size[] videoSizesArray = map.getOutputSizes(MediaRecorder.class);
                        if (videoSizesArray != null) {
                            availableVideoSizes.clear();
                            availableVideoFpsList.clear();
                            availableVideoFpsRanges.clear();
                            availableHighSpeedFlags.clear();
                            resolutionDisplayNames.clear();

                            Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                            Range<Integer> normal30Range = chooseFpsRange(map, fpsRanges, 30);
                            Range<Integer> highSpeed60Range = hasHighSpeedFpsRange(map, 60) ? chooseFpsRange(map, fpsRanges, 60) : null;
                            int normalMaxHeight = getMaxSupportedHeight(map, 30);
                            int highSpeedMaxHeight = highSpeed60Range != null ? getMaxHighSpeedHeight(map, 60) : 0;
                            logCameraVideoCapabilities(characteristics, map, fpsRanges, highSpeed60Range != null ? 60 : 30,
                                    highSpeed60Range != null ? highSpeedMaxHeight : normalMaxHeight);

                            for (Size size : videoSizesArray) {
                                if (isSupportedVideoSize(size, normalMaxHeight) && size.getWidth() * 9 == size.getHeight() * 16) {
                                    addVideoOption(size, 30, normal30Range, false);
                                }
                                if (highSpeed60Range != null && isSupportedVideoSize(size, highSpeedMaxHeight)
                                        && hasHighSpeedSizeFps(map, size, 60) && hasHighSpeedRecordingProfile(size)
                                        && size.getWidth() * 9 == size.getHeight() * 16) {
                                    addVideoOption(size, 60, chooseHighSpeedFixedFpsRangeForSize(map, size), true);
                                }
                            }

                            if (availableVideoSizes.isEmpty()) {
                                for (Size size : videoSizesArray) {
                                    float ratio = (float) size.getWidth() / size.getHeight();
                                    float diff = Math.abs(ratio - 16f / 9f);
                                    if (isSupportedVideoSize(size, normalMaxHeight) && diff < 0.1f) {
                                        addVideoOption(size, 30, normal30Range, false);
                                    }
                                    if (highSpeed60Range != null && isSupportedVideoSize(size, highSpeedMaxHeight)
                                            && hasHighSpeedSizeFps(map, size, 60) && hasHighSpeedRecordingProfile(size)
                                            && diff < 0.1f) {
                                        addVideoOption(size, 60, chooseHighSpeedFixedFpsRangeForSize(map, size), true);
                                    }
                                }
                            }

                            sortVideoOptions();

                            ArrayAdapter<String> resolutionAdapter = new ArrayAdapter<>(this,
                                    android.R.layout.simple_spinner_item, resolutionDisplayNames);
                            resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                            spinnerResolution.setAdapter(resolutionAdapter);

                            int defaultOptionIndex = findDefaultVideoOptionIndex();
                            if (defaultOptionIndex >= 0) {
                                spinnerResolution.setSelection(defaultOptionIndex);
                                applyVideoOption(defaultOptionIndex);
                            }
                        }

                        Log.d(TAG, "Selected video size: " + videoSize.getWidth() + "x" + videoSize.getHeight()
                                + ", fps=" + selectedVideoFps + ", fpsRange=" + selectedFpsRange
                                + ", sensorOrientation=" + sensorOrientation);
                    }
                    break;
                }
            }

            if (cameraId == null) {
                Toast.makeText(this, "未找到后置摄像头", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            spinnerResolution.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position >= 0 && position < availableVideoSizes.size()) {
                        applyVideoOption(position);
                        Log.d(TAG, "Resolution changed to: " + videoSize.getWidth() + "x" + videoSize.getHeight()
                                + ", fps=" + selectedVideoFps + ", highSpeed=" + useHighSpeedSession);

                        if (!isVideoBitrateManuallyAdjusted) {
                            int recommended = calculateRecommendedBitrate(videoSize.getHeight());
                            int recommendedMbps = recommended / 1_000_000;
                            updateVideoBitrateUI(recommendedMbps);
                            Log.d(TAG, "Auto-adjusted bitrate to: " + recommendedMbps + " Mbps");
                        }
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });

            startBackgroundThread();
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    configureTransform(width, height);
                    openCamera(false);
                }
                @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    configureTransform(width, height);
                }
                @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return true; }
                @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
            });

            if (textureView.isAvailable()) {
                configureTransform(textureView.getWidth(), textureView.getHeight());
                openCamera(false);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access error", e);
            Toast.makeText(this, "无法访问摄像头", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void openCamera(boolean forRecording) {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (manager == null || cameraId == null || cameraDevice != null) return;

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCameraPreviewSession(forRecording);
                }
                @Override public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }
                @Override public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    finish();
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Open camera error", e);
        }
    }

    private void createCameraPreviewSession(boolean forRecording) {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) return;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            List<Surface> surfaces = new ArrayList<>();
            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) return;
                    captureSession = session;
                    try {
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedFpsRange);
                        captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Capture request error", e);
                    }
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(CameraActivity.this, "摄像头配置失败", Toast.LENGTH_SHORT).show();
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Session creation error", e);
        }
    }

    // ===== 录制 =====

    private void onRecordButtonClick() {
        if (!isRecording) {
            startRecording();
        } else {
            stopRecording();
        }
    }

    private void startRecording() {
        if (cameraDevice == null) return;

        try {
            if (videoEncoder != null || mediaMuxer != null || encoderSurface != null) {
                Log.w(TAG, "发现残留的编码器资源,先释放...");
                releaseEncoderResources();
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "POV_" + timeStamp + ".mp4";
            currentVideoUri = null;
            currentTempFilePath = null;
            currentOutputPfd = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/POV");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                currentVideoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (currentVideoUri == null) throw new IllegalStateException("创建相册输出Uri失败");
                currentOutputPfd = getContentResolver().openFileDescriptor(currentVideoUri, "rw");
                if (currentOutputPfd == null) throw new IllegalStateException("打开相册输出文件失败");
            } else {
                File outputDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "POV");
                if (!outputDir.exists()) outputDir.mkdirs();
                File outputFile = new File(outputDir, fileName);
                currentTempFilePath = outputFile.getAbsolutePath();
            }

            if (useHighSpeedSession) {
                Log.d(TAG, "开始创建MediaRecorder（高速录像模式）...");
                setupHighSpeedMediaRecorder();
                encoderSurface = mediaRecorder.getSurface();
            } else {
                Log.d(TAG, "开始创建新的编码器和muxer（Surface输入模式）...");

                android.media.MediaFormat videoFormat = android.media.MediaFormat.createVideoFormat("video/avc", videoSize.getWidth(), videoSize.getHeight());
                videoFormat.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                videoFormat.setInteger(android.media.MediaFormat.KEY_BIT_RATE, selectedVideoBitrate);
                videoFormat.setInteger(android.media.MediaFormat.KEY_FRAME_RATE, selectedVideoFps);
                videoFormat.setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                videoFormat.setInteger(android.media.MediaFormat.KEY_PRIORITY, 0);

                videoEncoder = android.media.MediaCodec.createEncoderByType("video/avc");
                videoEncoder.configure(videoFormat, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoderSurface = videoEncoder.createInputSurface();
                videoEncoder.start();

                if (currentOutputPfd != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mediaMuxer = new android.media.MediaMuxer(currentOutputPfd.getFileDescriptor(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                } else {
                    mediaMuxer = new android.media.MediaMuxer(currentTempFilePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                }
                mediaMuxer.setOrientationHint(0);
            }

            videoTrackIndex = -1;
            audioTrackIndex = -1;
            isMuxerStarted = false;
            videoFrameCount = 0;
            audioFrameCount = 0;
            firstFrameTimeUs = 0;

            try {
                if (useHighSpeedSession) {
                    Log.d(TAG, "高速模式跳过GLES合成，使用Camera2直接输出到编码器");
                    glesRenderer = null;
                    glCameraSurface = null;
                } else {
                glesRenderer = new GLESVideoRenderer();
                glesRenderer.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
                glesRenderer.setMapEnabled(isMapEnabled);

                if (mapContainer != null && mapContainer.getVisibility() == View.VISIBLE) {
                    int[] mapPos = new int[2];
                    mapContainer.getLocationOnScreen(mapPos);
                    int[] texturePos = new int[2];
                    textureView.getLocationOnScreen(texturePos);
                    int textureW = textureView.getWidth();
                    int textureH = textureView.getHeight();

                    int relX = mapPos[0] - texturePos[0];
                    int relY = mapPos[1] - texturePos[1];
                    int mapW = mapContainer.getWidth();
                    int mapH = mapContainer.getHeight();

                    int videoMapX = (int) ((float) relX / textureW * videoSize.getWidth());
                    int videoMapY = (int) ((float) relY / textureH * videoSize.getHeight());
                    int videoMapW = (int) ((float) mapW / textureW * videoSize.getWidth());
                    int videoMapH = (int) ((float) mapH / textureH * videoSize.getHeight());

                    glesRenderer.setMapSize(videoMapW, videoMapH);
                    glesRenderer.setMapPosition(videoMapX, videoMapY);
                    float mapCornerRadius = POV_RECORD_MAP_CORNER_RADIUS_DP * getResources().getDisplayMetrics().density
                            * ((float) videoSize.getWidth() / textureW);
                    glesRenderer.setMapCornerRadius(mapCornerRadius);
                    Log.d(TAG, "地图录制参数: pos=(" + videoMapX + "," + videoMapY + ") size=" + videoMapW + "x" + videoMapH
                            + ", radius=" + mapCornerRadius);
                } else {
                    glesRenderer.setMapSize(300, 300);
                    glesRenderer.setMapCornerRadius(POV_RECORD_MAP_CORNER_RADIUS_DP * getResources().getDisplayMetrics().density);
                }

                if (!glesRenderer.initEGL(encoderSurface)) {
                    Log.e(TAG, "GLES EGL初始化失败，回退到纯摄像头模式");
                    glesRenderer = null;
                } else {
                    Log.d(TAG, "EGL初始化成功，启动渲染线程...");
                    glesRenderer.startRendering();

                    if (!glesRenderer.waitForGLInit()) {
                        Log.e(TAG, "GL资源初始化超时，回退到纯摄像头模式");
                        glesRenderer.stopRendering();
                        glesRenderer.release();
                        glesRenderer = null;
                    } else {
                        android.graphics.SurfaceTexture cameraSurfaceTexture = glesRenderer.getCameraSurfaceTexture();
                        if (cameraSurfaceTexture != null) {
                            glCameraSurface = new Surface(cameraSurfaceTexture);
                            Log.d(TAG, "GLES渲染器初始化成功，使用合成模式");

                            if ((isMapEnabled && mapView != null) || isInfoPanelEnabled) {
                                startMapBitmapUpdate();
                            }

                            if (isInfoPanelEnabled && infoPanelContainer != null) {
                                setupOverlayPosition();
                                glesRenderer.setOverlayEnabled(true);
                            }
                        } else {
                            Log.e(TAG, "获取摄像头SurfaceTexture失败，回退到纯摄像头模式");
                            glesRenderer.stopRendering();
                            glesRenderer.release();
                            glesRenderer = null;
                            glCameraSurface = null;
                        }
                    }
                }
                }
            } catch (Exception e) {
                Log.e(TAG, "GLES渲染器初始化失败，回退到纯摄像头模式", e);
                glesRenderer = null;
                glCameraSurface = null;
            }

            if (!useHighSpeedSession) {
                try {
                    android.media.MediaFormat audioFormat = android.media.MediaFormat.createAudioFormat("audio/mp4a-latm", selectedSampleRate, selectedAudioChannels);
                    audioFormat.setInteger(android.media.MediaFormat.KEY_BIT_RATE, selectedAudioBitrate);
                    audioFormat.setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    int maxInputSize = selectedSampleRate * selectedAudioChannels * 2;
                    audioFormat.setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize);
                    audioEncoder = android.media.MediaCodec.createEncoderByType("audio/mp4a-latm");
                    audioEncoder.configure(audioFormat, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
                    audioEncoder.start();
                    Log.d(TAG, "音频编码器启动成功: sampleRate=" + selectedSampleRate + ", channels=" + selectedAudioChannels + ", bitrate=" + selectedAudioBitrate);
                } catch (Exception e) {
                    Log.e(TAG, "音频编码器初始化失败", e);
                }
            }

            isRecording = true;
            if (!useHighSpeedSession) {
                startEncoderOutputThread();

                if (audioEncoder != null) {
                    startAudioRecordingThread();
                }
            }

            if (useHighSpeedSession && (isMapEnabled || isInfoPanelEnabled)) {
                startOverlayTimeline();
                startMapBitmapUpdate();
            }

            if (captureSession != null) {
                try {
                    captureSession.stopRepeating();
                    captureSession.abortCaptures();
                } catch (CameraAccessException e) {
                    Log.w(TAG, "停止预览Session失败", e);
                }
                captureSession.close();
                captureSession = null;
            }
            if (useHighSpeedSession && backgroundHandler != null) {
                backgroundHandler.postDelayed(this::createCameraPreviewSessionForRecording, 300);
            } else {
                createCameraPreviewSessionForRecording();
            }

            btnRecord.setText("停止");
            btnRecord.setBackgroundResource(R.drawable.record_button_bg_recording);
            Toast.makeText(this, "开始录制", Toast.LENGTH_SHORT).show();

            Log.d(TAG, "录制启动成功: video=" + videoSize.getWidth() + "x" + videoSize.getHeight()
                    + ", fps=" + selectedVideoFps + ", bitrate=" + selectedVideoBitrate);

        } catch (Exception e) {
            Log.e(TAG, "Start recording error", e);
            Toast.makeText(this, "录制启动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            releaseEncoderResources();
            if (currentVideoUri != null) {
                getContentResolver().delete(currentVideoUri, null, null);
                currentVideoUri = null;
            }
        }
    }

    private void setupOverlayPosition() {
        if (infoPanelContainer == null || textureView == null || glesRenderer == null) return;

        int[] previewLoc = new int[2];
        textureView.getLocationOnScreen(previewLoc);
        int previewW = textureView.getWidth();
        int previewH = textureView.getHeight();

        int[] panelLoc = new int[2];
        infoPanelContainer.getLocationOnScreen(panelLoc);
        int panelW = infoPanelContainer.getWidth();
        int panelH = infoPanelContainer.getHeight();

        int relX = panelLoc[0] - previewLoc[0];
        int relY = panelLoc[1] - previewLoc[1];

        int vidW = videoSize.getWidth();
        int vidH = videoSize.getHeight();
        int overlayX = (int) ((float) relX / previewW * vidW);
        int overlayY = (int) ((float) relY / previewH * vidH);
        int overlayW = (int) ((float) panelW / previewW * vidW);
        int overlayH = (int) ((float) panelH / previewH * vidH);

        glesRenderer.setOverlaySize(overlayW, overlayH);
        glesRenderer.setOverlayPosition(overlayX, overlayY);

        Log.d(TAG, "信息面板叠加位置: pos=(" + overlayX + "," + overlayY + "), size=" + overlayW + "x" + overlayH
                + " (预览: pos=(" + relX + "," + relY + "), size=" + panelW + "x" + panelH + ")");
    }

    private void createCameraPreviewSessionForRecording() {
        try {
            List<Surface> surfaces = new ArrayList<>();
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            if (glesRenderer != null && glCameraSurface != null && glCameraSurface.isValid()) {
                surfaces.add(glCameraSurface);
                builder.addTarget(glCameraSurface);

                if (!useHighSpeedSession) {
                    android.graphics.SurfaceTexture texture = textureView.getSurfaceTexture();
                    if (texture != null) {
                        texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
                        Surface previewSurface = new Surface(texture);
                        surfaces.add(previewSurface);
                        builder.addTarget(previewSurface);
                    }
                    Log.d(TAG, "使用GLES合成模式: Camera2 → GLES(录制+地图) + TextureView(预览)");
                } else {
                    Log.d(TAG, "使用高速GLES合成模式: Camera2 → GLES(录制+地图)");
                }
            } else {
                if (useHighSpeedSession) {
                    android.graphics.SurfaceTexture texture = textureView.getSurfaceTexture();
                    if (texture == null) {
                        Log.e(TAG, "SurfaceTexture为null");
                        return;
                    }
                    texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
                    Surface previewSurface = new Surface(texture);
                    surfaces.add(previewSurface);
                    builder.addTarget(previewSurface);

                    if (encoderSurface == null || !encoderSurface.isValid()) {
                        Log.e(TAG, "高速录制encoderSurface无效");
                        return;
                    }
                    surfaces.add(encoderSurface);
                    builder.addTarget(encoderSurface);
                    Log.d(TAG, "使用demo标准高速MediaRecorder模式: Camera2 → TextureView预览 + MediaRecorder");
                } else {
                    android.graphics.SurfaceTexture texture = textureView.getSurfaceTexture();
                    if (texture == null) {
                        Log.e(TAG, "SurfaceTexture为null");
                        return;
                    }
                    texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());

                    Surface previewSurface = new Surface(texture);
                    surfaces.add(previewSurface);
                    builder.addTarget(previewSurface);

                    if (encoderSurface != null && encoderSurface.isValid()) {
                        surfaces.add(encoderSurface);
                        builder.addTarget(encoderSurface);
                    }
                    Log.d(TAG, "使用纯摄像头模式: Camera2 → 编码器+预览");
                }
            }

            Log.d(TAG, "正在创建录制CaptureSession: highSpeed=" + useHighSpeedSession);

            CameraCaptureSession.StateCallback stateCallback = new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) return;
                    captureSession = session;
                    try {
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        if (session instanceof CameraConstrainedHighSpeedCaptureSession) {
                            captureSession.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
                                private boolean recorderStarted = false;

                                @Override
                                public void onCaptureStarted(@NonNull CameraCaptureSession session,
                                                             @NonNull CaptureRequest request,
                                                             long timestamp,
                                                             long frameNumber) {
                                    if (!recorderStarted && mediaRecorder != null) {
                                        mediaRecorder.start();
                                        recorderStarted = true;
                                    }
                                }
                            }, backgroundHandler);
                            Log.d(TAG, "高速录制CaptureSession配置成功: demo标准请求, fpsRange=" + selectedFpsRange);
                        } else {
                            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedFpsRange);
                            captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            Log.d(TAG, "录制CaptureSession配置成功: fpsRange=" + selectedFpsRange);
                        }
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Capture request error", e);
                    }
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "录制CaptureSession配置失败: highSpeed=" + useHighSpeedSession + ", surfaces=" + surfaces.size());
                    runOnUiThread(() -> {
                        Toast.makeText(CameraActivity.this, "摄像头配置失败，请选择30fps重试", Toast.LENGTH_SHORT).show();
                        if (isRecording) stopRecording();
                    });
                }
            };

            if (useHighSpeedSession) {
                if (surfaces.size() != 2 || encoderSurface == null || !encoderSurface.isValid()) {
                    throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "高速录制需要MediaRecorder Surface和预览Surface");
                }
                cameraDevice.createConstrainedHighSpeedCaptureSession(surfaces, stateCallback, backgroundHandler);
            } else {
                cameraDevice.createCaptureSession(surfaces, stateCallback, backgroundHandler);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Session creation error", e);
            runOnUiThread(() -> {
                Toast.makeText(CameraActivity.this, "录制配置失败，请选择30fps重试", Toast.LENGTH_SHORT).show();
                if (isRecording) stopRecording();
            });
        }
    }

    private void setupHighSpeedMediaRecorder() throws Exception {
        CamcorderProfile profile = getHighSpeedCamcorderProfile(videoSize);
        if (profile == null) {
            throw new IllegalStateException("当前尺寸没有CamcorderProfile高速录像支持: " + videoSize);
        }
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setProfile(profile);
        int recorderFps = profile.videoFrameRate;
        mediaRecorder.setOrientationHint(0);
        if (currentOutputPfd != null) {
            mediaRecorder.setOutputFile(currentOutputPfd.getFileDescriptor());
        } else {
            mediaRecorder.setOutputFile(currentTempFilePath);
        }
        mediaRecorder.prepare();
        Log.d(TAG, "MediaRecorder准备完成: " + videoSize.getWidth() + "x" + videoSize.getHeight()
                + ", recorderFps=" + recorderFps + ", requestFpsRange=" + selectedFpsRange
                + ", bitrate=" + selectedVideoBitrate);
    }

    private void startEncoderOutputThread() {
        new Thread(() -> {
            android.media.MediaCodec.BufferInfo bufferInfo = new android.media.MediaCodec.BufferInfo();

            while (isRecording && videoEncoder != null) {
                try {
                    int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000);

                    if (outputBufferIndex == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        android.media.MediaFormat videoFormat = videoEncoder.getOutputFormat();
                        Log.d(TAG, "视频编码器输出格式变更: " + videoFormat.toString());

                        synchronized (muxerLock) {
                            if (mediaMuxer != null && !isMuxerStarted && videoTrackIndex < 0) {
                                videoTrackIndex = mediaMuxer.addTrack(videoFormat);
                                Log.d(TAG, "视频轨道已添加: trackIndex=" + videoTrackIndex);
                            }
                        }
                        checkAndStartMuxer();
                        continue;
                    }

                    if (outputBufferIndex == android.media.MediaCodec.INFO_TRY_AGAIN_LATER) {
                        continue;
                    }

                    if (outputBufferIndex >= 0) {
                        if ((bufferInfo.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(TAG, "视频编码器EOS标记已接收");
                            videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                            break;
                        }

                        if (!isMuxerStarted) {
                            videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                            continue;
                        }

                        java.nio.ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(outputBufferIndex);
                        if (bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                            synchronized (muxerLock) {
                                if (mediaMuxer != null && isMuxerStarted) {
                                    mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);
                                    videoFrameCount++;
                                }
                            }
                        }

                        videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "视频编码器输出线程出错", e);
                    break;
                }
            }
            Log.d(TAG, "视频编码器输出线程结束: videoFrameCount=" + videoFrameCount);
        }).start();

        if (audioEncoder != null) {
            new Thread(() -> {
                android.media.MediaCodec.BufferInfo bufferInfo = new android.media.MediaCodec.BufferInfo();

                while (isRecording && audioEncoder != null) {
                    try {
                        int outputBufferIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 10000);

                        if (outputBufferIndex == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            android.media.MediaFormat audioFormat = audioEncoder.getOutputFormat();
                            Log.d(TAG, "音频编码器输出格式变更: " + audioFormat.toString());

                            synchronized (muxerLock) {
                                if (mediaMuxer != null && !isMuxerStarted && audioTrackIndex < 0) {
                                    audioTrackIndex = mediaMuxer.addTrack(audioFormat);
                                    Log.d(TAG, "音频轨道已添加: trackIndex=" + audioTrackIndex);
                                }
                            }
                            checkAndStartMuxer();
                            continue;
                        }

                        if (outputBufferIndex == android.media.MediaCodec.INFO_TRY_AGAIN_LATER) {
                            continue;
                        }

                        if (outputBufferIndex >= 0) {
                            if ((bufferInfo.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                Log.d(TAG, "音频编码器EOS标记已接收");
                                audioEncoder.releaseOutputBuffer(outputBufferIndex, false);
                                break;
                            }

                            if (!isMuxerStarted) {
                                audioEncoder.releaseOutputBuffer(outputBufferIndex, false);
                                continue;
                            }

                            java.nio.ByteBuffer outputBuffer = audioEncoder.getOutputBuffer(outputBufferIndex);
                            if (bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset);
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                                synchronized (muxerLock) {
                                    if (mediaMuxer != null && isMuxerStarted && audioTrackIndex >= 0) {
                                        mediaMuxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo);
                                        audioFrameCount++;
                                    }
                                }
                            }

                            audioEncoder.releaseOutputBuffer(outputBufferIndex, false);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "音频编码器输出线程出错", e);
                        break;
                    }
                }
                Log.d(TAG, "音频编码器输出线程结束: audioFrameCount=" + audioFrameCount);
            }).start();
        }
    }

    private void checkAndStartMuxer() {
        synchronized (muxerLock) {
            if (isMuxerStarted || mediaMuxer == null) return;
            if (videoTrackIndex >= 0 && (audioEncoder == null || audioTrackIndex >= 0)) {
                Log.d(TAG, "准备启动Muxer: videoTrackIndex=" + videoTrackIndex + ", audioTrackIndex=" + audioTrackIndex);
                mediaMuxer.start();
                isMuxerStarted = true;
                Log.d(TAG, "MediaMuxer已启动");
            }
        }
    }

    private void startAudioRecordingThread() {
        if (audioRecord != null) {
            stopAudioRecordingThread();
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "没有录音权限");
            return;
        }

        int bufferSize = android.media.AudioRecord.getMinBufferSize(selectedSampleRate,
                selectedAudioChannels == 2 ? android.media.AudioFormat.CHANNEL_IN_STEREO : android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT);

        try {
            audioRecord = new android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    selectedSampleRate,
                    selectedAudioChannels == 2 ? android.media.AudioFormat.CHANNEL_IN_STEREO : android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);

            if (audioRecord.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败");
                return;
            }

            audioRecord.startRecording();
            isAudioRecording = true;

            audioRecordingThread = new Thread(() -> {
                byte[] audioBuffer = new byte[bufferSize];
                while (isAudioRecording && isRecording && audioRecord != null) {
                    int bytesRead = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                    if (bytesRead > 0) {
                        encodeAudioFrame(audioBuffer, bytesRead);
                    }
                }
            });
            audioRecordingThread.start();

            Log.d(TAG, "音频录制线程已启动: sampleRate=" + selectedSampleRate + ", channels=" + selectedAudioChannels);
        } catch (Exception e) {
            Log.e(TAG, "启动音频录制失败: " + e.getMessage(), e);
        }
    }

    private void stopAudioRecordingThread() {
        isAudioRecording = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "停止音频录制失败", e);
            }
            audioRecord = null;
        }
        if (audioRecordingThread != null) {
            audioRecordingThread.interrupt();
            try {
                audioRecordingThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "音频线程join失败", e);
            }
            audioRecordingThread = null;
        }
        Log.d(TAG, "音频录制线程已停止");
    }

    private void encodeAudioFrame(byte[] audioData, int size) {
        if (audioEncoder == null || audioData == null || size <= 0) return;

        try {
            int inputBufferIndex = audioEncoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                java.nio.ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex);
                inputBuffer.clear();

                int remaining = inputBuffer.remaining();
                int writeSize = Math.min(size, remaining);
                if (writeSize > 0) {
                    inputBuffer.put(audioData, 0, writeSize);
                    long presentationTimeUs = System.nanoTime() / 1000;
                    audioEncoder.queueInputBuffer(inputBufferIndex, 0, writeSize, presentationTimeUs, 0);
                    audioFrameCount++;
                }

                if (size > remaining) {
                    Log.w(TAG, "音频数据过大，已分批处理: total=" + size + ", written=" + writeSize);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "编码音频帧失败: " + e.getMessage(), e);
        }
    }

    private void signalAudioEncoderEndOfStream() {
        if (audioEncoder == null) return;
        try {
            int inputBufferIndex = audioEncoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                audioEncoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                Log.d(TAG, "音频编码器结束流标记已发送");
            }
        } catch (Exception e) {
            Log.e(TAG, "发送音频编码器结束标记失败", e);
        }
    }

    private void releaseEncoderResources() {
        Log.d(TAG, "开始释放编码器资源...");

        stopMapBitmapUpdate();
        stopAudioRecordingThread();

        if (glesRenderer != null) {
            glesRenderer.stopRendering();
            glesRenderer.release();
            glesRenderer = null;
            Log.d(TAG, "GLES渲染器已释放");
        }

        if (glCameraSurface != null) {
            glCameraSurface.release();
            glCameraSurface = null;
        }
        if (glMapSurface != null) {
            glMapSurface.release();
            glMapSurface = null;
        }

        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                Log.d(TAG, "MediaRecorder已停止");
            } catch (Exception e) {
                Log.e(TAG, "停止MediaRecorder失败", e);
            }
            try {
                mediaRecorder.reset();
                mediaRecorder.release();
                Log.d(TAG, "MediaRecorder已释放");
            } catch (Exception e) {
                Log.e(TAG, "释放MediaRecorder失败", e);
            }
            mediaRecorder = null;
        }

        if (videoEncoder != null) {
            try {
                videoEncoder.signalEndOfInputStream();
                Thread.sleep(500);
                videoEncoder.stop();
                videoEncoder.release();
                Log.d(TAG, "视频编码器已释放");
            } catch (Exception e) {
                Log.e(TAG, "释放视频编码器失败", e);
            }
            videoEncoder = null;
        }

        if (encoderSurface != null) {
            encoderSurface.release();
            encoderSurface = null;
            Log.d(TAG, "编码器Surface已释放");
        }

        if (audioEncoder != null) {
            try {
                signalAudioEncoderEndOfStream();
                Thread.sleep(100);
                audioEncoder.stop();
                audioEncoder.release();
                Log.d(TAG, "音频编码器已释放");
            } catch (Exception e) {
                Log.e(TAG, "释放音频编码器失败", e);
            }
            audioEncoder = null;
        }

        synchronized (muxerLock) {
            if (mediaMuxer != null) {
                try {
                    if (isMuxerStarted) {
                        mediaMuxer.stop();
                        Log.d(TAG, "MediaMuxer已停止");
                    }
                    mediaMuxer.release();
                    Log.d(TAG, "MediaMuxer已释放");
                } catch (Exception e) {
                    Log.e(TAG, "释放Muxer失败", e);
                }
                mediaMuxer = null;
                isMuxerStarted = false;
            }
        }

        if (currentOutputPfd != null) {
            try {
                currentOutputPfd.close();
                Log.d(TAG, "输出文件描述符已关闭");
            } catch (Exception e) {
                Log.e(TAG, "关闭输出文件描述符失败", e);
            }
            currentOutputPfd = null;
        }

        videoTrackIndex = -1;
        audioTrackIndex = -1;
        videoFrameCount = 0;
        audioFrameCount = 0;
        firstFrameTimeUs = 0;

        Log.d(TAG, "编码器资源释放完成");
    }

    private void stopRecording() {
        if (!isRecording) return;

        try {
            isRecording = false;
            releaseEncoderResources();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (currentVideoUri != null) {
                    ContentValues updateValues = new ContentValues();
                    updateValues.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(currentVideoUri, updateValues, null, null);
                    Log.d(TAG, "视频已直接保存到相册: " + currentVideoUri);
                }
            } else if (currentTempFilePath != null) {
                File outputFile = new File(currentTempFilePath);
                if (outputFile.exists() && outputFile.length() > 0) {
                    android.media.MediaScannerConnection.scanFile(this,
                            new String[]{currentTempFilePath},
                            new String[]{"video/mp4"},
                            null);
                    Log.d(TAG, "视频已直接保存到相册路径: " + currentTempFilePath + ", size=" + outputFile.length());
                } else {
                    Log.e(TAG, "输出文件无效: " + currentTempFilePath);
                    Toast.makeText(this, "录制失败：文件为空", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            if (useHighSpeedSession && !overlayTimeline.isEmpty()) {
                List<OfflineVideoComposer.OverlayFrame> timelineSnapshot = new ArrayList<>(overlayTimeline);
                Uri inputUri = currentVideoUri;
                String inputPath = currentTempFilePath;
                OfflineVideoComposer.compose(this, inputUri, inputPath, timelineSnapshot, new OfflineVideoComposer.Callback() {
                    @Override public void onComplete(Uri outputUri, String outputPath) {
                        runOnUiThread(() -> Toast.makeText(CameraActivity.this,
                                "离线合成入口已触发，当前保留原始60fps视频", Toast.LENGTH_LONG).show());
                    }

                    @Override public void onError(Exception error) {
                        Log.e(TAG, "离线合成入口执行失败", error);
                        runOnUiThread(() -> Toast.makeText(CameraActivity.this,
                                "离线合成入口执行失败，已保留原始60fps视频", Toast.LENGTH_LONG).show());
                    }
                });
            } else {
                Toast.makeText(this, "录制已保存到相册", Toast.LENGTH_SHORT).show();
            }
            Log.d(TAG, "录制完成: videoFrameCount=" + videoFrameCount + ", audioFrameCount=" + audioFrameCount);

        } catch (Exception e) {
            Log.e(TAG, "Stop recording error", e);
            Toast.makeText(this, "录制保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            if (currentVideoUri != null) {
                getContentResolver().delete(currentVideoUri, null, null);
            }
        } finally {
            isRecording = false;
            currentVideoUri = null;
            currentTempFilePath = null;
            overlayTimelineStartMs = 0;
            if (mapUpdateRunnable != null) {
                mapUpdateHandler.removeCallbacks(mapUpdateRunnable);
                mapUpdateRunnable = null;
            }
            videoFrameCount = 0;
            audioFrameCount = 0;
            btnRecord.setText("录制");
            btnRecord.setBackgroundResource(R.drawable.record_button_bg);
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            createCameraPreviewSession(false);
        }
    }

    // ===== 地图 =====

    private void initMapView(Bundle savedInstanceState) {
        mapContainer = findViewById(R.id.map_container);
        mapView = findViewById(R.id.map_view_camera);
        tvMapStatus = findViewById(R.id.tv_map_status);

        if (mapView == null) {
            Log.w(TAG, "地图控件未找到");
            return;
        }

        final Bundle finalSavedState = savedInstanceState;
        mapView.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (mapView == null || mapView.getWidth() <= 0 || mapView.getHeight() <= 0) {
                            return;
                        }
                        mapView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        try {
                            navigationView = new AmapNavigationView(CameraActivity.this, mapView);
                            navigationView.setNavigationCamera(POV_MAP_ZOOM, POV_MAP_TILT);
                            navigationView.setLocationUpdateListener(location -> {
                                if (povDetector != null) {
                                    povDetector.onGpsLocation(location);
                                }
                            });
                            navigationView.onCreate(finalSavedState);
                            navigationView.setGpsMode(true);
                            navigationView.setCompassMode(false);

                            if (tvMapStatus != null) {
                                tvMapStatus.setText("GPS导航模式");
                                tvMapStatus.setVisibility(View.GONE);
                            }

                            Log.d(TAG, "AmapNavigationView初始化完成（GPS模式）");
                            drawBusLineRoute();
                        } catch (Throwable t) {
                            Log.e(TAG, "AmapNavigationView初始化失败: " + t.getMessage(), t);
                        }
                    }
                });

        Log.d(TAG, "地图初始化完成（等待布局）");
    }

    private void drawBusLineRoute() {
        try {
            if (routePoints == null || routePoints.isEmpty()) {
                Log.d(TAG, "无公交线路数据，不绘制路线");
                return;
            }

            List<LatLng> mapPoints = new ArrayList<>();
            for (Coordinate c : routePoints) {
                mapPoints.add(new LatLng(c.getLat(), c.getLng()));
            }

            if (navigationView != null) {
                navigationView.drawRoute(mapPoints);
            }

            if (stationList != null && stationList.size() >= 2 && navigationView != null) {
                BusApiClient.BusLineStation first = stationList.get(0);
                BusApiClient.BusLineStation last = stationList.get(stationList.size() - 1);
                if (first.poiOriginLat != 0 && first.poiOriginLon != 0
                        && last.poiOriginLat != 0 && last.poiOriginLon != 0) {
                    navigationView.setBusLineStartAndEnd(
                            first.poiOriginLat, first.poiOriginLon,
                            last.poiOriginLat, last.poiOriginLon);
                    Log.d(TAG, "导航起终点已设置: start=(" + first.poiOriginLat + "," + first.poiOriginLon
                            + "), end=(" + last.poiOriginLat + "," + last.poiOriginLon + ")");
                }
            }

            Log.d(TAG, "POV公交线路已绘制: " + mapPoints.size() + "个点");
        } catch (Exception e) {
            Log.e(TAG, "绘制公交线路失败", e);
        }
    }

    private void updateMapVisibility() {
        if (mapContainer == null) return;
        if (isMapEnabled) {
            mapContainer.setVisibility(View.VISIBLE);
        } else {
            mapContainer.setVisibility(View.INVISIBLE);
        }
        Log.d(TAG, "地图显示状态: " + (isMapEnabled ? "显示" : "隐藏"));
    }

    private void updateInfoPanelVisibility() {
        if (infoPanelContainer == null) return;
        if (isInfoPanelEnabled) {
            infoPanelContainer.setVisibility(View.VISIBLE);
        } else {
            infoPanelContainer.setVisibility(View.GONE);
        }
        if (textureView != null) {
            textureView.post(this::updatePreviewViewSize);
        }
        if (glesRenderer != null) {
            glesRenderer.setOverlayEnabled(isInfoPanelEnabled && isRecording);
        }
        Log.d(TAG, "线路信息面板显示状态: " + (isInfoPanelEnabled ? "显示" : "隐藏"));
    }

    private void updateCoordinateVisibility() {
        if (povCoordinateContainer == null) return;
        povCoordinateContainer.setVisibility(isCoordinateEnabled ? View.VISIBLE : View.GONE);
        Log.d(TAG, "坐标信息显示状态: " + (isCoordinateEnabled ? "显示" : "隐藏"));
    }

    private void updateInfoPanelData() {
        Log.d(TAG, "POV信息面板赋值: lineName=" + lineName + ", endStation=" + endStationName);

        TextView povLineNumber = findViewById(R.id.pov_line_number);
        if (povLineNumber != null && lineName != null) {
            povLineNumber.setText(lineName);
        }

        HorizontalScrollTextView povStartStation = findViewById(R.id.pov_start_station_name);
        if (povStartStation != null && startStationName != null && !startStationName.isEmpty()) {
            povStartStation.setText(startStationName);
        }

        HorizontalScrollTextView povEndStation = findViewById(R.id.pov_end_station_name);
        if (povEndStation != null && endStationName != null && !endStationName.isEmpty()) {
            povEndStation.setText(endStationName);
        }
    }

    private void startOverlayTimeline() {
        overlayTimeline.clear();
        overlayTimelineStartMs = System.currentTimeMillis();
        Log.d(TAG, "高速录制UI时间线已初始化");
    }

    private void startMapBitmapUpdate() {
        if (mapUpdateRunnable != null) return;

        mapUpdateRunnable = () -> {
            if (isRecording) {
                if (isMapEnabled && mapView != null) {
                    try {
                        mapView.getMap().getMapScreenShot(new AMap.OnMapScreenShotListener() {
                            @Override public void onMapScreenShot(android.graphics.Bitmap bitmap) {
                                handleMapScreenshot(bitmap);
                            }
                            @Override public void onMapScreenShot(android.graphics.Bitmap bitmap, int status) {
                                handleMapScreenshot(bitmap);
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "获取地图Bitmap失败", e);
                    }
                }

                if (isInfoPanelEnabled && infoPanelContainer != null && infoPanelContainer.getVisibility() == View.VISIBLE) {
                    try {
                        android.graphics.Bitmap bmp = captureViewBitmap(infoPanelContainer);
                        if (bmp != null) {
                            if (glesRenderer != null) {
                                glesRenderer.updateOverlayBitmap(bmp);
                            } else if (useHighSpeedSession) {
                                recordOverlayFrame(false, null, bmp);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "获取信息面板Bitmap失败", e);
                    }
                } else if (useHighSpeedSession) {
                    recordOverlayFrame(false, null, null);
                }
            }

            if (isRecording && (isMapEnabled || isInfoPanelEnabled)) {
                mapUpdateHandler.postDelayed(this.mapUpdateRunnable, MAP_UPDATE_INTERVAL);
            }
        };

        mapUpdateHandler.post(mapUpdateRunnable);
        Log.d(TAG, "地图/信息面板Bitmap定时更新已启动，间隔=" + MAP_UPDATE_INTERVAL + "ms");
    }

    private void handleMapScreenshot(android.graphics.Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        android.graphics.Bitmap safeBitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
        if (safeBitmap == null) return;
        if (glesRenderer != null) {
            glesRenderer.updateMapBitmap(safeBitmap);
        } else if (useHighSpeedSession) {
            android.graphics.Bitmap infoBitmap = null;
            if (isInfoPanelEnabled && infoPanelContainer != null && infoPanelContainer.getVisibility() == View.VISIBLE) {
                infoBitmap = captureViewBitmap(infoPanelContainer);
            }
            recordOverlayFrame(true, safeBitmap, infoBitmap);
        }
    }

    private android.graphics.Bitmap captureViewBitmap(View view) {
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) return null;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        view.draw(canvas);
        return bmp;
    }

    private void recordOverlayFrame(boolean fromMapCallback, android.graphics.Bitmap mapBitmap, android.graphics.Bitmap infoBitmap) {
        if (!useHighSpeedSession || overlayTimelineStartMs <= 0) return;
        if (!fromMapCallback && isMapEnabled) {
            return;
        }
        OfflineVideoComposer.OverlayFrame frame = buildOverlayFrame(mapBitmap, infoBitmap);
        overlayTimeline.add(frame);
    }

    private OfflineVideoComposer.OverlayFrame buildOverlayFrame(android.graphics.Bitmap mapBitmap, android.graphics.Bitmap infoBitmap) {
        OverlayBounds mapBounds = calculateOverlayBounds(mapContainer);
        OverlayBounds infoBounds = calculateOverlayBounds(infoPanelContainer);
        return new OfflineVideoComposer.OverlayFrame(
                System.currentTimeMillis() - overlayTimelineStartMs,
                isMapEnabled,
                isInfoPanelEnabled,
                mapBitmap,
                infoBitmap,
                mapBounds.x,
                mapBounds.y,
                mapBounds.width,
                mapBounds.height,
                infoBounds.x,
                infoBounds.y,
                infoBounds.width,
                infoBounds.height,
                videoSize.getWidth(),
                videoSize.getHeight());
    }

    private OverlayBounds calculateOverlayBounds(View overlayView) {
        if (overlayView == null || textureView == null || textureView.getWidth() <= 0 || textureView.getHeight() <= 0) {
            return new OverlayBounds(0, 0, 0, 0);
        }
        int[] overlayLoc = new int[2];
        int[] previewLoc = new int[2];
        overlayView.getLocationOnScreen(overlayLoc);
        textureView.getLocationOnScreen(previewLoc);
        int relX = overlayLoc[0] - previewLoc[0];
        int relY = overlayLoc[1] - previewLoc[1];
        int x = (int) ((float) relX / textureView.getWidth() * videoSize.getWidth());
        int y = (int) ((float) relY / textureView.getHeight() * videoSize.getHeight());
        int w = (int) ((float) overlayView.getWidth() / textureView.getWidth() * videoSize.getWidth());
        int h = (int) ((float) overlayView.getHeight() / textureView.getHeight() * videoSize.getHeight());
        return new OverlayBounds(x, y, w, h);
    }

    private static class OverlayBounds {
        final int x;
        final int y;
        final int width;
        final int height;

        OverlayBounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private void stopMapBitmapUpdate() {
        if (mapUpdateRunnable != null) {
            mapUpdateHandler.removeCallbacks(mapUpdateRunnable);
            mapUpdateRunnable = null;
            Log.d(TAG, "地图Bitmap定时更新已停止");
        }
    }

    // ===== 开门动效 =====
    private View doorLeft;
    private View doorRight;
    private android.animation.AnimatorSet doorAnimator;
    private void startDoorAnimation() {
        doorLeft = findViewById(R.id.pov_door_left);
        doorRight = findViewById(R.id.pov_door_right);
        if (doorLeft == null || doorRight == null) return;

        float moveDistance = 30f * getResources().getDisplayMetrics().density;

        android.animation.ObjectAnimator leftOpen = android.animation.ObjectAnimator.ofFloat(doorLeft, View.TRANSLATION_X, 0f, -moveDistance);
        leftOpen.setDuration(5000);
        android.animation.ObjectAnimator leftClose = android.animation.ObjectAnimator.ofFloat(doorLeft, View.TRANSLATION_X, -moveDistance, 0f);
        leftClose.setDuration(0);

        android.animation.ObjectAnimator rightOpen = android.animation.ObjectAnimator.ofFloat(doorRight, View.TRANSLATION_X, 0f, moveDistance);
        rightOpen.setDuration(5000);
        android.animation.ObjectAnimator rightClose = android.animation.ObjectAnimator.ofFloat(doorRight, View.TRANSLATION_X, moveDistance, 0f);
        rightClose.setDuration(0);

        android.animation.AnimatorSet openSet = new android.animation.AnimatorSet();
        openSet.playTogether(leftOpen, rightOpen);

        android.animation.AnimatorSet closeSet = new android.animation.AnimatorSet();
        closeSet.playTogether(leftClose, rightClose);

        doorAnimator = new android.animation.AnimatorSet();
        doorAnimator.playSequentially(openSet, closeSet);
        doorAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (doorAnimator != null) {
                    doorAnimator.start();
                }
            }
        });
        doorAnimator.start();
    }

    private void stopDoorAnimation() {
        if (doorAnimator != null) {
            doorAnimator.removeAllListeners();
            doorAnimator.cancel();
            doorAnimator = null;
        }
        if (doorLeft != null) doorLeft.setTranslationX(0f);
        if (doorRight != null) doorRight.setTranslationX(0f);
    }

    // ===== 资源清理 =====

    private void destroyMapAndLocation() {
        if (navigationView != null) {
            navigationView.onDestroyWithoutNavi();
            navigationView = null;
        }
        if (mapView != null) {
            try { mapView.onDestroy(); } catch (Throwable t) {}
            mapView = null;
        }
        Log.d(TAG, "地图和定位资源已销毁（POV模式，AMapNavi单例保留）");
    }

    // ===== 工具方法 =====

    private void addVideoOption(Size size, int fps, Range<Integer> fpsRange, boolean highSpeed) {
        availableVideoSizes.add(size);
        availableVideoFpsList.add(fps);
        availableVideoFpsRanges.add(fpsRange);
        availableHighSpeedFlags.add(highSpeed);
        String fpsText = highSpeed && fpsRange.getUpper() != fps ? fps + "fps输出/" + fpsRange.getUpper() + "fps采集" : fps + "fps";
        resolutionDisplayNames.add(size.getHeight() + "p " + fpsText + " (" + size.getWidth() + "x" + size.getHeight() + ")");
    }

    private void applyVideoOption(int position) {
        videoSize = availableVideoSizes.get(position);
        previewSize = videoSize;
        selectedVideoFps = availableVideoFpsList.get(position);
        selectedFpsRange = availableVideoFpsRanges.get(position);
        useHighSpeedSession = availableHighSpeedFlags.get(position);
    }

    private int findDefaultVideoOptionIndex() {
        int first30FpsIndex = -1;
        for (int i = 0; i < availableVideoSizes.size(); i++) {
            if (availableVideoFpsList.get(i) == 30 && !availableHighSpeedFlags.get(i)) {
                if (availableVideoSizes.get(i).getHeight() == 1080) {
                    return i;
                }
                if (first30FpsIndex < 0) {
                    first30FpsIndex = i;
                }
            }
        }
        if (first30FpsIndex >= 0) {
            return first30FpsIndex;
        }
        return availableVideoSizes.isEmpty() ? -1 : 0;
    }

    private void sortVideoOptions() {
        for (int i = 0; i < availableVideoSizes.size() - 1; i++) {
            for (int j = i + 1; j < availableVideoSizes.size(); j++) {
                Size left = availableVideoSizes.get(i);
                Size right = availableVideoSizes.get(j);
                if (left.getHeight() < right.getHeight()
                        || (left.getHeight() == right.getHeight() && availableVideoFpsList.get(i) < availableVideoFpsList.get(j))) {
                    swapVideoOptions(i, j);
                }
            }
        }
    }

    private void swapVideoOptions(int i, int j) {
        Size tempSize = availableVideoSizes.get(i);
        availableVideoSizes.set(i, availableVideoSizes.get(j));
        availableVideoSizes.set(j, tempSize);

        int tempFps = availableVideoFpsList.get(i);
        availableVideoFpsList.set(i, availableVideoFpsList.get(j));
        availableVideoFpsList.set(j, tempFps);

        Range<Integer> tempRange = availableVideoFpsRanges.get(i);
        availableVideoFpsRanges.set(i, availableVideoFpsRanges.get(j));
        availableVideoFpsRanges.set(j, tempRange);

        boolean tempHighSpeed = availableHighSpeedFlags.get(i);
        availableHighSpeedFlags.set(i, availableHighSpeedFlags.get(j));
        availableHighSpeedFlags.set(j, tempHighSpeed);

        String tempName = resolutionDisplayNames.get(i);
        resolutionDisplayNames.set(i, resolutionDisplayNames.get(j));
        resolutionDisplayNames.set(j, tempName);
    }

    private int chooseTargetVideoFps(StreamConfigurationMap map, Range<Integer>[] fpsRanges) {
        return hasHighSpeedFpsRange(map, 60) ? 60 : 30;
    }

    private boolean isSupportedVideoSize(Size size, int maxSupportedHeight) {
        return maxSupportedHeight <= 0 || size.getHeight() <= maxSupportedHeight;
    }

    private boolean hasHighSpeedRecordingProfile(Size size) {
        return getHighSpeedCamcorderProfile(size) != null;
    }

    private CamcorderProfile getHighSpeedCamcorderProfile(Size size) {
        int cameraIntId;
        try {
            cameraIntId = Integer.parseInt(cameraId);
        } catch (Exception e) {
            return null;
        }

        int quality = getHighSpeedCamcorderQuality(size);
        if (quality < 0 || !CamcorderProfile.hasProfile(cameraIntId, quality)) {
            return null;
        }
        return CamcorderProfile.get(cameraIntId, quality);
    }

    private int getHighSpeedCamcorderQuality(Size size) {
        if (size.getWidth() == 1920 && size.getHeight() == 1080) {
            return CamcorderProfile.QUALITY_HIGH_SPEED_1080P;
        }
        if (size.getWidth() == 1280 && size.getHeight() == 720) {
            return CamcorderProfile.QUALITY_HIGH_SPEED_720P;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && size.getWidth() == 3840 && size.getHeight() == 2160) {
            return CamcorderProfile.QUALITY_HIGH_SPEED_2160P;
        }
        if (size.getWidth() == 720 && size.getHeight() == 480) {
            return CamcorderProfile.QUALITY_HIGH_SPEED_480P;
        }
        return -1;
    }

    private int getMaxSupportedHeight(StreamConfigurationMap map, int targetFps) {
        int highSpeedMaxHeight = getMaxHighSpeedHeight(map, targetFps);
        if (targetFps >= 60) {
            return highSpeedMaxHeight;
        }
        if (highSpeedMaxHeight > 0) {
            return highSpeedMaxHeight;
        }

        Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
        int maxHeight = 0;
        if (videoSizes != null) {
            for (Size size : videoSizes) {
                if (size.getHeight() > maxHeight) {
                    maxHeight = size.getHeight();
                }
            }
        }
        return maxHeight;
    }

    private int getMaxHighSpeedHeight(StreamConfigurationMap map, int targetFps) {
        Size[] videoSizes = map.getHighSpeedVideoSizes();
        int maxHeight = 0;
        if (videoSizes != null) {
            for (Size size : videoSizes) {
                Range<Integer>[] ranges = map.getHighSpeedVideoFpsRangesFor(size);
                if (hasFpsRange(ranges, targetFps) && size.getHeight() > maxHeight) {
                    maxHeight = size.getHeight();
                }
            }
        }
        return maxHeight;
    }

    private int getMaxHighSpeedHeight(StreamConfigurationMap map, Range<Integer> fpsRange) {
        return getMaxHighSpeedHeight(map, selectedVideoFps >= 60 ? 60 : fpsRange.getLower());
    }

    private boolean hasHighSpeedFpsRange(StreamConfigurationMap map, int targetFps) {
        return hasFpsRange(map.getHighSpeedVideoFpsRanges(), targetFps);
    }

    private Range<Integer> chooseHighSpeedFixedFpsRangeForSize(StreamConfigurationMap map, Size size) {
        Range<Integer>[] ranges = map.getHighSpeedVideoFpsRangesFor(size);
        if (ranges == null || ranges.length == 0) {
            return new Range<>(120, 120);
        }
        for (Range<Integer> range : ranges) {
            if (range.getLower() == 120 && range.getUpper() == 120) {
                return range;
            }
        }
        for (Range<Integer> range : ranges) {
            if (range.getLower().equals(range.getUpper()) && range.getLower() >= 60) {
                return range;
            }
        }
        for (Range<Integer> range : ranges) {
            if (range.getLower() <= 60 && range.getUpper() >= 60) {
                return range;
            }
        }
        return ranges[0];
    }

    private boolean hasHighSpeedSizeFps(StreamConfigurationMap map, Size size, int targetFps) {
        try {
            return hasFpsRange(map.getHighSpeedVideoFpsRangesFor(size), targetFps);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean hasFpsRange(Range<Integer>[] fpsRanges, int targetFps) {
        if (fpsRanges == null) return false;
        for (Range<Integer> range : fpsRanges) {
            if (range.getLower() <= targetFps && range.getUpper() >= targetFps) {
                return true;
            }
        }
        return false;
    }

    private Range<Integer> chooseFpsRange(StreamConfigurationMap map, Range<Integer>[] fpsRanges, int targetFps) {
        if (targetFps >= 60) {
            Range<Integer>[] highSpeedRanges = map.getHighSpeedVideoFpsRanges();
            Range<Integer> highSpeedRange = chooseFpsRangeFromArray(highSpeedRanges, targetFps);
            if (highSpeedRange != null) {
                return highSpeedRange;
            }
        }
        Range<Integer> normalRange = chooseFpsRangeFromArray(fpsRanges, targetFps);
        return normalRange != null ? normalRange : new Range<>(30, 30);
    }

    private Range<Integer> chooseFpsRangeFromArray(Range<Integer>[] fpsRanges, int targetFps) {
        if (fpsRanges == null || fpsRanges.length == 0) {
            return null;
        }

        for (Range<Integer> range : fpsRanges) {
            if (range.getLower() == targetFps && range.getUpper() == targetFps) {
                return range;
            }
        }

        for (Range<Integer> range : fpsRanges) {
            if (range.getLower() <= targetFps && range.getUpper() >= targetFps) {
                return range;
            }
        }

        return null;
    }

    private void logCameraVideoCapabilities(CameraCharacteristics characteristics, StreamConfigurationMap map,
                                            Range<Integer>[] fpsRanges, int targetFps, int maxSupportedHeight) {
        int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean hasConstrainedHighSpeed = false;
        StringBuilder capabilityText = new StringBuilder();
        if (capabilities != null) {
            for (int capability : capabilities) {
                if (capabilityText.length() > 0) capabilityText.append(", ");
                capabilityText.append(capability);
                if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) {
                    hasConstrainedHighSpeed = true;
                }
            }
        }

        StringBuilder ranges = new StringBuilder();
        if (fpsRanges != null) {
            for (Range<Integer> range : fpsRanges) {
                if (ranges.length() > 0) ranges.append(", ");
                ranges.append(range);
            }
        }

        StringBuilder highSpeedRanges = new StringBuilder();
        Range<Integer>[] highSpeedFpsRanges = map.getHighSpeedVideoFpsRanges();
        if (highSpeedFpsRanges != null) {
            for (Range<Integer> range : highSpeedFpsRanges) {
                if (highSpeedRanges.length() > 0) highSpeedRanges.append(", ");
                highSpeedRanges.append(range);
            }
        }

        StringBuilder highSpeedSizeText = new StringBuilder();
        Size[] highSpeedSizes = map.getHighSpeedVideoSizes();
        if (highSpeedSizes != null) {
            for (Size size : highSpeedSizes) {
                if (highSpeedSizeText.length() > 0) highSpeedSizeText.append("; ");
                highSpeedSizeText.append(size.getWidth()).append("x").append(size.getHeight()).append("=");
                Range<Integer>[] sizeRanges = map.getHighSpeedVideoFpsRangesFor(size);
                if (sizeRanges != null) {
                    for (int i = 0; i < sizeRanges.length; i++) {
                        if (i > 0) highSpeedSizeText.append(",");
                        highSpeedSizeText.append(sizeRanges[i]);
                    }
                }
            }
        }

        Log.d(TAG, "相机视频能力: capabilities=[" + capabilityText + "], hasConstrainedHighSpeed=" + hasConstrainedHighSpeed
                + ", aeFpsRanges=[" + ranges + "], highSpeedFpsRanges=[" + highSpeedRanges
                + "], highSpeedSizes={" + highSpeedSizeText + "}, targetFps=" + targetFps
                + ", selectedFpsRange=" + selectedFpsRange + ", highSpeed=" + useHighSpeedSession
                + ", maxSupportedHeight=" + maxSupportedHeight + ", highSpeed60MaxHeight=" + getMaxHighSpeedHeight(map, 60));
    }

    private int calculateRecommendedBitrate(int height) {
        int baseHeight = 1080;
        int baseBitrate = 30_000_000;
        int recommended = (int) ((float) height / baseHeight * baseBitrate * selectedVideoFps / 30f);
        return Math.max(5_000_000, Math.min(100_000_000, recommended));
    }

    private void updateVideoBitrateUI(int bitrateMbps) {
        selectedVideoBitrate = bitrateMbps * 1_000_000;
        tvVideoBitrateValue.setText(bitrateMbps + " Mbps");
        seekbarVideoBitrate.setProgress(bitrateMbps);
    }

    private void startBackgroundThread() {
        if (backgroundThread != null) return;
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Background thread join error", e);
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    private void closeCamera() {
        if (isRecording) {
            stopRecording();
        }
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        stopBackgroundThread();
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (textureView == null || previewSize == null || viewWidth == 0 || viewHeight == 0) return;

        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
        float scale = Math.max((float) viewHeight / previewSize.getHeight(), (float) viewWidth / previewSize.getWidth());
        matrix.postScale(scale, scale, centerX, centerY);
        matrix.postRotate(sensorOrientation == 270 ? 90 : -90, centerX, centerY);
        textureView.setTransform(matrix);
    }
}