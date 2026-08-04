package org.zjfgh.zhujibus;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.location.LocationListener;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
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
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.amap.api.maps.AMap;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.LatLng;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.sgr.geometry.Coordinate;
import io.sgr.geometry.utils.RouteGeometryUtils;

public class CameraActivity extends AppCompatActivity {
    private static final String TAG = "CameraActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    private TextureView textureView;
    private String cameraId;
    private Size previewSize = new Size(1920, 1080);
    private Size videoSize = new Size(1920, 1080);
    private int selectedVideoFps = 30;
    private Range<Integer> selectedFpsRange = new Range<>(30, 30);
    private int sensorOrientation = 90;
    // ⭐ SurfaceTexture 类支持的输出分辨率集合（启动时从 StreamConfigurationMap 读取，
    //   用于判断能否强制 camera buffer=videoSize 绕过 CameraX 的 1080p 限制）
    private final java.util.Set<String> surfaceTextureSupportedSizes = new java.util.HashSet<>();
    // ⭐ 高帧率模式 fps 范围（constrained high-speed），用于添加 60fps 等选项
    private Range<Integer>[] highSpeedFpsRanges;

    // ⭐ 录制时长显示
    private TextView tvRecordDuration;
    private Handler recordDurationHandler;
    private Runnable recordDurationRunnable;
    private long recordStartTimeMs;

    // ⭐ CameraX 相关
    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private Executor cameraExecutor;
    private Surface previewSurface;
    private boolean glesInitialized = false;

    // 录制相关
    private TextView btnRecord;
    private boolean isRecording = false;
    private volatile boolean isStoppingRecording = false;
    private Thread stopRecordingThread;
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
    // ⭐ Step2: 信息面板按 videoSize 离屏绘制的目标尺寸（setupOverlayPosition 计算，captureViewBitmap 使用）
    private int overlayVideoW = 0;
    private int overlayVideoH = 0;
    private CheckBox checkboxShowCoordinate;
    private boolean isCoordinateEnabled = true;
    private View povCoordinateContainer;

    // ===== 线路参数与独立加载数据 =====
    private String lineId;
    private String lineName;
    private String startStationName;
    private String endStationName;
    private int direction = 1;
    private List<BusApiClient.BusLineStation> stationList = new ArrayList<>();
    private List<Coordinate> routePoints = new ArrayList<>();

    // ===== GLES 渲染 =====
    private GLESVideoRenderer glesRenderer;

    private Handler mapUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable mapUpdateRunnable;
    // ⭐ 截图频率:地图 30fps(33ms)
    private static final int MAP_UPDATE_INTERVAL = 33;
    // ⭐ map 截图防堆积 + 看门狗 + 定期保活
    private volatile boolean mapScreenshotPending = false;
    private volatile long lastMapScreenshotTime = 0;
    private volatile long lastMapResumeTime = 0;
    private static final long MAP_SCREENSHOT_TIMEOUT_MS = 5000;
    private static final long MAP_RESUME_INTERVAL_MS = 10000; // 每 10s 主动 onResume 保活
    // ⭐ 信息面板更新频率:30fps(33ms)
    private static final int OVERLAY_UPDATE_INTERVAL = 33;
    private long lastOverlayUpdateTime = 0;
    private static final float POV_MAP_ZOOM = 19f;
    private static final float POV_MAP_TILT = 0f;
    private static final float POV_RECORD_MAP_CORNER_RADIUS_DP = 8f;

    // ===== 录制编码器 =====
    private android.media.MediaCodec videoEncoder;
    private android.media.MediaMuxer mediaMuxer;
    private Surface encoderSurface;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private volatile boolean isMuxerStarted = false;
    private final Object muxerLock = new Object();
    private android.media.MediaCodec audioEncoder;
    private long videoFrameCount = 0;
    private long audioFrameCount = 0;
    private long firstFrameTimeUs = 0;
    private Thread videoOutputThread;
    private Thread audioOutputThread;

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

        textureView = findViewById(R.id.texture_camera_preview);
        textureView.post(this::updatePreviewViewSize);
        findViewById(R.id.btn_close_camera).setOnClickListener(v -> finish());

        btnRecord = findViewById(R.id.btn_record_camera);
        btnRecord.setOnClickListener(v -> onRecordButtonClick());
        tvRecordDuration = findViewById(R.id.tv_record_duration);
        tvRecordDuration.setVisibility(View.GONE);

        cameraExecutor = Executors.newSingleThreadExecutor();

        initSettingsPanel();
        updateInfoPanelData();
        startDoorAnimation();
        initMapView(savedInstanceState);
        initPovDetector();
        loadRouteInfoFromDetail();

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
            // ⭐ 修复:与 activity_camera.xml 中 @dimen/_80sdp 保持一致,不同设备按 sdp 缩放
            //   原硬编码 100*density 会让 sdp 失效,在平板/大屏上小地图偏小,在小屏上偏大
            //   nonTransitiveRClass 下 R.dimen._80sdp 不可见,改用 getIdentifier 运行时查找 sdp 资源
            int mapSizeResId = getResources().getIdentifier("_80sdp", "dimen", getPackageName());
            int mapSizePx = mapSizeResId > 0
                    ? (int) getResources().getDimension(mapSizeResId)
                    : (int) (80 * density);
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
            if (textureView != null && textureView.isAvailable()) {
                startGlesAndCameraX(textureView.getSurfaceTexture());
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

    private void loadRouteInfoFromDetail() {
        // 直接复用详情页当前方向的数据，不再通过 lineId 重新请求接口
        BusApiClient.BusLineDirection dir = BusLineDetailActivity.getCurrentLineDirection();
        if (dir == null) {
            Log.w(TAG, "详情页方向数据为空，POV线路信息加载失败");
            return;
        }
        lineName = BusLineDetailActivity.getCurrentLineName();
        lineId = dir.id;
        startStationName = dir.startStation;
        endStationName = dir.endStation;
        stationList.clear();
        if (dir.stationList != null) {
            stationList.addAll(dir.stationList);
        }
        routePoints.clear();
        if (dir.geometry != null && !dir.geometry.isEmpty()) {
            List<Coordinate> parsed = RouteGeometryUtils.parseGeometry(dir.geometry);
            if (parsed != null) {
                routePoints.addAll(parsed);
            }
        }
        updateInfoPanelData();
        drawBusLineRoute();
        updateDetectorRouteData();
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
                        // ⭐ 诊断：对比 SurfaceTexture 类（GLES camera Surface 的实际类型）和 MediaRecorder 类支持的分辨率
                        //   如果 SurfaceTexture 不含 4K 而 MediaRecorder 含，说明 4K 只能走 MediaRecorder/MediaCodec 流，CameraX Preview 拿不到
                        Size[] texSizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
                        if (texSizes != null) {
                            surfaceTextureSupportedSizes.clear();
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < texSizes.length; i++) {
                                if (i > 0) sb.append(", ");
                                sb.append(texSizes[i].getWidth()).append("x").append(texSizes[i].getHeight());
                                surfaceTextureSupportedSizes.add(texSizes[i].getWidth() + "x" + texSizes[i].getHeight());
                            }
                            Log.d(TAG, "[诊断] SurfaceTexture 支持分辨率(" + texSizes.length + "): " + sb);
                        }
                        Size[] videoSizesArray = map.getOutputSizes(MediaRecorder.class);
                        if (videoSizesArray != null) {
                            availableVideoSizes.clear();
                            availableVideoFpsList.clear();
                            availableVideoFpsRanges.clear();
                            resolutionDisplayNames.clear();

                            Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                            Range<Integer> normal30Range = chooseFpsRange(fpsRanges, 30);
                            int normalMaxHeight = getMaxSupportedHeight(map);
                            logCameraVideoCapabilities(characteristics, map, fpsRanges, normalMaxHeight);

                            // ⭐ 诊断：高帧率模式（constrained high-speed）支持的 fps 范围和尺寸
                            //   60fps+ 通常只在高帧率模式下可用，不在 CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES 里
                            //   该模式需 createConstrainedHighSpeedCaptureSession，CameraX Preview 不支持
                            Range<Integer>[] hsFpsRanges = map.getHighSpeedVideoFpsRanges();
                            Size[] hsSizes = map.getHighSpeedVideoSizes();
                            highSpeedFpsRanges = hsFpsRanges; // 保存供添加高帧率选项使用
                            if (hsFpsRanges != null && hsFpsRanges.length > 0) {
                                StringBuilder sbFps = new StringBuilder();
                                for (int i = 0; i < hsFpsRanges.length; i++) {
                                    if (i > 0) sbFps.append(", ");
                                    sbFps.append("[").append(hsFpsRanges[i].getLower()).append(",").append(hsFpsRanges[i].getUpper()).append("]");
                                }
                                Log.d(TAG, "[诊断] 高帧率模式 fps 范围(" + hsFpsRanges.length + "): " + sbFps);
                            } else {
                                Log.d(TAG, "[诊断] 高帧率模式: 不支持（getHighSpeedVideoFpsRanges 为空）");
                            }
                            if (hsSizes != null && hsSizes.length > 0) {
                                StringBuilder sbS = new StringBuilder();
                                for (int i = 0; i < hsSizes.length; i++) {
                                    if (i > 0) sbS.append(", ");
                                    sbS.append(hsSizes[i].getWidth()).append("x").append(hsSizes[i].getHeight());
                                }
                                Log.d(TAG, "[诊断] 高帧率模式支持尺寸(" + hsSizes.length + "): " + sbS);
                            }

                            for (Size size : videoSizesArray) {
                                if (isSupportedVideoSize(size, normalMaxHeight) && size.getWidth() * 9 == size.getHeight() * 16) {
                                    addVideoOption(size, 30, normal30Range);
                                }
                            }

                            if (availableVideoSizes.isEmpty()) {
                                for (Size size : videoSizesArray) {
                                    float ratio = (float) size.getWidth() / size.getHeight();
                                    float diff = Math.abs(ratio - 16f / 9f);
                                    if (isSupportedVideoSize(size, normalMaxHeight) && diff < 0.1f) {
                                        addVideoOption(size, 30, normal30Range);
                                    }
                                }
                            }

                            // ⭐ 添加高帧率 60fps 选项：仅 16:9 的高帧率尺寸，且该尺寸支持含 60 的 fps 范围。
                            //   注意：高帧率 fps 范围（如 [60,120]）理论上需 constrained high-speed session，
                            //   但通过 Camera2Interop 在普通模式设置部分设备/驱动会接受，不行再考虑 Camera2。
                            if (hsSizes != null && hsFpsRanges != null && hsFpsRanges.length > 0) {
                                Range<Integer> fps60Range = findBestFpsRange(hsFpsRanges, 60);
                                if (fps60Range != null) {
                                    for (Size size : hsSizes) {
                                        if (size.getWidth() * 9 != size.getHeight() * 16) continue; // 仅 16:9
                                        Range<Integer>[] sizeFpsRanges = map.getHighSpeedVideoFpsRangesFor(size);
                                        boolean supports60 = false;
                                        if (sizeFpsRanges != null) {
                                            for (Range<Integer> r : sizeFpsRanges) {
                                                if (r.getLower() <= 60 && r.getUpper() >= 60) { supports60 = true; break; }
                                            }
                                        }
                                        if (supports60) {
                                            addVideoOption(size, 60, fps60Range);
                                            // 标记暂不可用（需 Camera2 constrained high-speed session 才能获得真 60fps 画质）
                                            int lastIdx = resolutionDisplayNames.size() - 1;
                                            resolutionDisplayNames.set(lastIdx, resolutionDisplayNames.get(lastIdx) + " (暂不可用)");
                                            Log.d(TAG, "添加高帧率选项(已禁用): " + size.getWidth() + "x" + size.getHeight() + " 60fps, range=" + fps60Range);
                                        }
                                    }
                                }
                            }

                            sortVideoOptions();

                            // ⭐ 自定义 Adapter：60fps 选项显示但不可选（灰色+不可点击）
                            ArrayAdapter<String> resolutionAdapter = getStringArrayAdapter();
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
                                + ", fps=" + selectedVideoFps);

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

            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    configureTransform(width, height);
                    startGlesAndCameraX(surface);
                }
                @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    configureTransform(width, height);
                }
                @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return true; }
                @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
            });

            if (textureView.isAvailable()) {
                configureTransform(textureView.getWidth(), textureView.getHeight());
                startGlesAndCameraX(textureView.getSurfaceTexture());
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access error", e);
            Toast.makeText(this, "无法访问摄像头", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @NonNull
    private ArrayAdapter<String> getStringArrayAdapter() {
        ArrayAdapter<String> resolutionAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, resolutionDisplayNames) {
            @Override
            public boolean isEnabled(int position) {
                return position >= 0 && position < availableVideoFpsList.size()
                        && availableVideoFpsList.get(position) != 60;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                if (view instanceof TextView) {
                    TextView tv = (TextView) view;
                    if (position >= 0 && position < availableVideoFpsList.size()
                            && availableVideoFpsList.get(position) == 60) {
                        tv.setTextColor(android.graphics.Color.GRAY);
                    } else {
                        tv.setTextColor(android.graphics.Color.BLACK);
                    }
                }
                return view;
            }
        };
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return resolutionAdapter;
    }

    // ===== CameraX + GLES 启动 =====

    /**
     * ⭐ 初始化 GLES 渲染器并启动 CameraX Preview
     * 流程：initEGL(previewSurface) → startRendering → 异步 waitForGLInit → startCameraX
     */
    private void startGlesAndCameraX(SurfaceTexture texture) {
        if (texture == null) return;
        if (glesRenderer != null && glesInitialized) {
            Log.d(TAG, "GLES 已初始化，跳过重复启动");
            return;
        }

        try {
            // 释放旧的资源（如果有）
            if (glesRenderer != null) {
                glesRenderer.release();
                glesRenderer = null;
            }
            if (previewSurface != null) {
                previewSurface.release();
                previewSurface = null;
            }
            glesInitialized = false;

            // 设置 TextureView 的 SurfaceTexture 默认缓冲区大小
            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            previewSurface = new Surface(texture);

            // 创建 GLES 渲染器
            glesRenderer = new GLESVideoRenderer();
            glesRenderer.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            glesRenderer.setMapEnabled(false);
            glesRenderer.setOverlayEnabled(false);

            if (!glesRenderer.initEGL(previewSurface)) {
                Log.e(TAG, "GLES EGL 初始化失败");
                glesRenderer = null;
                previewSurface.release();
                previewSurface = null;
                return;
            }

            Log.d(TAG, "EGL 初始化成功，启动渲染线程...");
            glesRenderer.startRendering();

            // 异步等待 GL 资源初始化完成，然后启动 CameraX
            cameraExecutor.execute(() -> {
                if (glesRenderer.waitForGLInit()) {
                    glesInitialized = true;
                    Log.d(TAG, "GL 资源初始化完成，启动 CameraX");
                    runOnUiThread(this::startCameraX);
                } else {
                    Log.e(TAG, "GL 资源初始化失败");
                    runOnUiThread(() -> Toast.makeText(CameraActivity.this, "GLES 初始化失败", Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "GLES + CameraX 启动失败", e);
        }
    }

    /**
     * ⭐ 启动 CameraX Preview，绑定到生命周期
     * 使用 Camera2Interop 设置 CONTROL_AE_TARGET_FPS_RANGE 和 CONTROL_AF_MODE
     * SurfaceProvider 提供 glesRenderer.getCameraSurface() 作为 Camera 输出目标
     */
    @SuppressLint("UnsafeOptInUsageError")
    private void startCameraX() {
        if (glesRenderer == null || !glesInitialized) {
            Log.e(TAG, "GLES 未初始化，无法启动 CameraX");
            return;
        }

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                if (cameraProvider == null) return;

                // 解绑旧的用例（如果有）
                cameraProvider.unbindAll();

                // ⭐ Camera2Interop：必须作用于 Preview.Builder（build 之前）
                Preview.Builder previewBuilder = new Preview.Builder();
                // 横屏锁定应用：targetRotation 设为显示器实际旋转，使 sensorOrientation 与 displayRotation 抵消（净旋转 0°），
                // CameraX 产生与 SurfaceTexture(1920x1080) 匹配的横屏 buffer，避免竖屏 buffer 塞进横屏 Surface 导致压扁
                previewBuilder.setTargetRotation(getWindowManager().getDefaultDisplay().getRotation());
                // ⭐ 让 CameraX 按用户选择的 videoSize 请求分辨率。之前未设置时 CameraX 默认给 1600×1200(4:3)，
                //   与 16:9 videoSize 不匹配 → 画面被裁剪/错位。CameraX 会选最接近的支持分辨率，
                //   剩余宽高比差异仍由 GLES updateCameraVertices 的 center-crop 兜底。
                previewBuilder.setTargetResolution(new android.util.Size(videoSize.getWidth(), videoSize.getHeight()));
                Camera2Interop.Extender<Preview> extender = new Camera2Interop.Extender<>(previewBuilder);
                extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedFpsRange);
                extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

                // 创建 Preview 用例
                preview = previewBuilder.build();

                // 设置 SurfaceProvider，提供 GLES 的 camera Surface
                preview.setSurfaceProvider(cameraExecutor, request -> {
                    android.util.Size camRes = request.getResolution();
                    // ⭐ 诊断日志：CameraX 实际请求分辨率 vs 用户选择 videoSize
                    Log.d(TAG, "[诊断] CameraX 请求分辨率 camRes=" + camRes.getWidth() + "x" + camRes.getHeight()
                            + ", videoSize=" + videoSize.getWidth() + "x" + videoSize.getHeight()
                            + ", camAspect=" + String.format(java.util.Locale.US, "%.3f", (float) camRes.getWidth() / Math.max(1, camRes.getHeight()))
                            + ", videoAspect=" + String.format(java.util.Locale.US, "%.3f", (float) videoSize.getWidth() / Math.max(1, videoSize.getHeight())));
                    // ⭐ CameraX Preview 内部把分辨率限在 1080p，但设备 SurfaceTexture 可能支持更高（如 4K）。
                    //   相机帧大小由 SurfaceTexture 的 setDefaultBufferSize 决定，故尝试强制设为 videoSize 绕过 CameraX 限制。
                    //   兜底：若 videoSize 不在 SurfaceTexture 支持列表（该设备/该分辨率不支持），回退到 CameraX 请求的 camRes，
                    //   剩余宽高比差异由 GLES updateCameraVertices 的 center-crop 兜底。
                    String videoSizeKey = videoSize.getWidth() + "x" + videoSize.getHeight();
                    int bufW, bufH;
                    if (surfaceTextureSupportedSizes.contains(videoSizeKey)) {
                        bufW = videoSize.getWidth();
                        bufH = videoSize.getHeight();
                        Log.d(TAG, "强制相机 buffer=" + bufW + "x" + bufH + "（videoSize 在 SurfaceTexture 支持列表）");
                    } else {
                        bufW = camRes.getWidth();
                        bufH = camRes.getHeight();
                        Log.w(TAG, "videoSize " + videoSizeKey + " 不在 SurfaceTexture 支持列表，回退到 CameraX 请求的 " + bufW + "x" + bufH);
                    }
                    glesRenderer.setCameraBufferSize(bufW, bufH);
                    Surface cameraSurface = glesRenderer.getCameraSurface();
                    if (cameraSurface != null && cameraSurface.isValid()) {
                        request.provideSurface(cameraSurface, cameraExecutor, result -> {
                            Log.d(TAG, "Camera Surface 结果: " + result.getResultCode());
                        });
                    } else {
                        Log.e(TAG, "cameraSurface 无效，无法提供给 CameraX");
                        request.willNotProvideSurface();
                    }
                });

                // 选择后置摄像头
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // 绑定到生命周期
                cameraProvider.bindToLifecycle(this, cameraSelector, preview);

                Log.d(TAG, "CameraX Preview 已绑定: fpsRange=" + selectedFpsRange);
            } catch (Exception e) {
                Log.e(TAG, "CameraX 启动失败", e);
                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "摄像头启动失败", Toast.LENGTH_SHORT).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ===== 录制 =====

    private void onRecordButtonClick() {
        if (isStoppingRecording) {
            Toast.makeText(this, "正在停止录制，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isRecording) {
            startRecording();
        } else {
            stopRecording();
        }
    }

    private void startRecording() {
        if (glesRenderer == null || !glesInitialized) {
            Toast.makeText(this, "摄像头未就绪", Toast.LENGTH_SHORT).show();
            return;
        }

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

            videoTrackIndex = -1;
            audioTrackIndex = -1;
            isMuxerStarted = false;
            videoFrameCount = 0;
            audioFrameCount = 0;
            firstFrameTimeUs = 0;

            // ⭐ 新架构：glesRenderer 已在摄像头启动时创建，这里只需添加 encoder Surface
            // 兜底同步 GLES viewport = 当前 videoSize（applyVideoOption 已同步，这里防其他路径改了 videoSize）
            glesRenderer.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            // 设置地图位置和大小
            glesRenderer.setMapEnabled(isMapEnabled);
            // ⭐ 诊断日志：录制开始时各尺寸对比（排查相机输出与 videoSize 不匹配）
            {
                int[] camBuf = glesRenderer.getCameraBufferSize();
                String texSize = (textureView != null) ? textureView.getWidth() + "x" + textureView.getHeight() : "null";
                Log.d(TAG, "[诊断] 录制开始: videoSize=" + videoSize.getWidth() + "x" + videoSize.getHeight()
                        + ", camBuffer=" + camBuf[0] + "x" + camBuf[1]
                        + ", textureView=" + texSize);
            }
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

            // 设置信息面板位置
            if (isInfoPanelEnabled && infoPanelContainer != null) {
                setupOverlayPosition();
                glesRenderer.setOverlayEnabled(true);
            }

            // ⭐ Step2: 录制时把屏幕上的 map/infoPanel 平移到屏外，消除"双显"。
            //   保持 VISIBLE 不变：AMap 的 GLSurfaceView 由独立渲染线程驱动，view.draw 也与位置无关，
            //   因此 getMapScreenShot 与 captureViewBitmap 仍正常工作。GLES 已把 map+info 合成进预览 Surface，
            //   故预览即最终录制画面。stopRecording 中恢复 translationX=0。
            //   注意：必须在 setupOverlayPosition 之后平移——后者要用 getLocationOnScreen 读未偏移的位置。
            if (mapContainer != null) {
                mapContainer.setTranslationX(10000f);
            }
            if (infoPanelContainer != null) {
                infoPanelContainer.setTranslationX(10000f);
            }

            // ⭐ 动态添加 encoder Surface 到 GLES 渲染器（渲染线程会在下一帧创建 EGL Surface）
            glesRenderer.setEncoderSurface(encoderSurface);
            Log.d(TAG, "Encoder Surface 已添加到 GLES 渲染器");

            // 启动地图/信息面板 Bitmap 更新
            if ((isMapEnabled && mapView != null) || isInfoPanelEnabled) {
                startMapBitmapUpdate();
            }

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

            isRecording = true;
            startEncoderOutputThread();

            if (audioEncoder != null) {
                startAudioRecordingThread();
            }

            btnRecord.setText("停止");
            btnRecord.setBackgroundResource(R.drawable.record_button_bg_recording);
            Toast.makeText(this, "开始录制", Toast.LENGTH_SHORT).show();

            Log.d(TAG, "录制启动成功: video=" + videoSize.getWidth() + "x" + videoSize.getHeight()
                    + ", fps=" + selectedVideoFps + ", bitrate=" + selectedVideoBitrate);

            // ⭐ 启动录制时长显示
            recordStartTimeMs = System.currentTimeMillis();
            if (recordDurationHandler == null) recordDurationHandler = new Handler(Looper.getMainLooper());
            if (recordDurationRunnable == null) {
                recordDurationRunnable = new Runnable() {
                    @Override
                    public void run() {
                        if (!isRecording) return;
                        long elapsed = System.currentTimeMillis() - recordStartTimeMs;
                        int totalSec = (int) (elapsed / 1000);
                        int h = totalSec / 3600;
                        int m = (totalSec % 3600) / 60;
                        int s = totalSec % 60;
                        tvRecordDuration.setText(String.format("● %02d:%02d:%02d", h, m, s));
                        recordDurationHandler.postDelayed(this, 500);
                    }
                };
            }
            tvRecordDuration.setVisibility(View.VISIBLE);
            recordDurationHandler.post(recordDurationRunnable);

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

        // ⭐ Step2: 保存为字段，供 startMapBitmapUpdate 调用 captureViewBitmap 按 videoSize 离屏绘制
        overlayVideoW = overlayW;
        overlayVideoH = overlayH;

        glesRenderer.setOverlaySize(overlayW, overlayH);
        glesRenderer.setOverlayPosition(overlayX, overlayY);

        Log.d(TAG, "信息面板叠加位置: pos=(" + overlayX + "," + overlayY + "), size=" + overlayW + "x" + overlayH
                + " (预览: pos=(" + relX + "," + relY + "), size=" + panelW + "x" + panelH + ")");
    }

    private void startEncoderOutputThread() {
        videoOutputThread = new Thread(() -> {
            android.media.MediaCodec.BufferInfo bufferInfo = new android.media.MediaCodec.BufferInfo();

            while (isRecording && videoEncoder != null) {
                try {
                    // ⭐ 超时从 10ms 增到 50ms，减少忙轮询 CPU 开销
                    int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 50000);

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
        });
        videoOutputThread.start();

        if (audioEncoder != null) {
            audioOutputThread = new Thread(() -> {
                android.media.MediaCodec.BufferInfo bufferInfo = new android.media.MediaCodec.BufferInfo();

                while (isRecording && audioEncoder != null) {
                    try {
                        int outputBufferIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 50000);

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
            });
            audioOutputThread.start();
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

    /**
     * ⭐ 释放编码器资源
     * 新架构：不释放 glesRenderer（保持预览），仅清除 encoder Surface
     */
    private void releaseEncoderResources() {
        Log.d(TAG, "开始释放编码器资源...");

        // ⭐ 停止录制时长显示
        if (recordDurationHandler != null && recordDurationRunnable != null) {
            recordDurationHandler.removeCallbacks(recordDurationRunnable);
        }

        stopMapBitmapUpdate();
        stopAudioRecordingThread();

        // ⭐ 新架构：仅清除 encoder Surface，不停止/释放 glesRenderer（保持预览继续运行）
        if (glesRenderer != null) {
            glesRenderer.clearEncoderSurface();
            glesRenderer.setMapEnabled(false);
            glesRenderer.setOverlayEnabled(false);
        }

        // ⭐ Step2: 恢复屏幕 UI 位置（startRecording 中被平移到屏外）
        if (mapContainer != null) {
            mapContainer.setTranslationX(0f);
        }
        if (infoPanelContainer != null) {
            infoPanelContainer.setTranslationX(0f);
        }

        // 通知编码器结束输入流，让输出线程排空剩余帧
        if (videoEncoder != null) {
            try {
                videoEncoder.signalEndOfInputStream();
                Log.d(TAG, "视频编码器EOS已发送");
            } catch (Exception e) {
                Log.e(TAG, "发送视频编码器EOS失败", e);
            }
        }
        if (audioEncoder != null) {
            signalAudioEncoderEndOfStream();
        }

        // 等待输出线程排空并退出
        joinEncoderThread(videoOutputThread, "视频输出线程", 2000);
        videoOutputThread = null;
        joinEncoderThread(audioOutputThread, "音频输出线程", 1000);
        audioOutputThread = null;

        isRecording = false;

        if (videoEncoder != null) {
            try {
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

    private void joinEncoderThread(Thread thread, String name, long timeoutMs) {
        if (thread == null) return;
        try {
            thread.join(timeoutMs);
            if (thread.isAlive()) {
                Log.w(TAG, name + " join超时，中断线程");
                thread.interrupt();
                thread.join(500);
            }
        } catch (InterruptedException e) {
            Log.e(TAG, name + " join失败", e);
        }
    }

    /**
     * ⭐ 异步停止录制：移到子线程执行 releaseEncoderResources，避免主线程 join 导致 ANR
     */
    private void stopRecording() {
        if (!isRecording || isStoppingRecording) return;
        isStoppingRecording = true;

        // UI 立即响应
        btnRecord.setText("停止中");
        btnRecord.setEnabled(false);

        stopRecordingThread = new Thread(() -> {
            try {
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
                        runOnUiThread(() -> Toast.makeText(CameraActivity.this, "录制失败：文件为空", Toast.LENGTH_SHORT).show());
                    }
                }

                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "录制已保存到相册", Toast.LENGTH_SHORT).show());
                Log.d(TAG, "录制完成: videoFrameCount=" + videoFrameCount + ", audioFrameCount=" + audioFrameCount);

            } catch (Exception e) {
                Log.e(TAG, "Stop recording error", e);
                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "录制保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                if (currentVideoUri != null) {
                    getContentResolver().delete(currentVideoUri, null, null);
                }
            } finally {
                isRecording = false;
                isStoppingRecording = false;
                currentVideoUri = null;
                currentTempFilePath = null;
                if (mapUpdateRunnable != null) {
                    mapUpdateHandler.removeCallbacks(mapUpdateRunnable);
                    mapUpdateRunnable = null;
                }
                videoFrameCount = 0;
                audioFrameCount = 0;
                runOnUiThread(() -> {
                    btnRecord.setText("录制");
                    btnRecord.setEnabled(true);
                    btnRecord.setBackgroundResource(R.drawable.record_button_bg);
                    tvRecordDuration.setVisibility(View.GONE);
                });
            }
        });
        stopRecordingThread.start();
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
                            // ⭐ 地图加载完成后再绘制路线，避免在 onMapLoaded 前 addPolyline 不生效导致路线迟迟不显示
                            navigationView.setOnMapReadyCallback(CameraActivity.this::drawBusLineRoute);
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

    private void startMapBitmapUpdate() {
        if (mapUpdateRunnable != null) return;
        mapScreenshotPending = false;
        lastMapScreenshotTime = 0;
        lastMapResumeTime = System.currentTimeMillis();

        mapUpdateRunnable = () -> {
            if (isRecording) {
                long now = System.currentTimeMillis();
                // 1. 地图截图
                if (isMapEnabled && mapView != null) {
                    // ⭐ 定期主动 onResume 保活 AMap 渲染线程（防止屏外停渲染）
                    if (now - lastMapResumeTime > MAP_RESUME_INTERVAL_MS) {
                        lastMapResumeTime = now;
                        try {
                            mapView.onResume();
                        } catch (Exception e) {
                            Log.e(TAG, "mapView.onResume 保活失败", e);
                        }
                    }

                    if (mapScreenshotPending) {
                        // 看门狗：回调超时 5s 则强制恢复
                        if (now - lastMapScreenshotTime > MAP_SCREENSHOT_TIMEOUT_MS) {
                            Log.w(TAG, "⚠️ 地图截图回调超时(" + (now - lastMapScreenshotTime) + "ms)，强制恢复");
                            mapScreenshotPending = false;
                            try {
                                mapView.onResume();
                            } catch (Exception e) {
                                Log.e(TAG, "恢复 AMap 渲染失败", e);
                            }
                        }
                    } else {
                        try {
                            mapScreenshotPending = true;
                            lastMapScreenshotTime = now;
                            mapView.getMap().getMapScreenShot(new AMap.OnMapScreenShotListener() {
                                @Override public void onMapScreenShot(android.graphics.Bitmap bitmap) {
                                    handleMapScreenshot(bitmap);
                                }
                                @Override public void onMapScreenShot(android.graphics.Bitmap bitmap, int status) {
                                    handleMapScreenshot(bitmap);
                                }
                            });
                        } catch (Exception e) {
                            mapScreenshotPending = false;
                            Log.e(TAG, "获取地图Bitmap失败", e);
                        }
                    }
                }

                // 2. 信息面板截图
                if (isInfoPanelEnabled && infoPanelContainer != null
                        && infoPanelContainer.getVisibility() == View.VISIBLE
                        && now - lastOverlayUpdateTime >= OVERLAY_UPDATE_INTERVAL) {
                    lastOverlayUpdateTime = now;
                    try {
                        android.graphics.Bitmap bmp = captureViewBitmap(infoPanelContainer, overlayVideoW, overlayVideoH);
                        if (bmp != null && glesRenderer != null) {
                            glesRenderer.updateOverlayBitmap(bmp);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "获取信息面板Bitmap失败", e);
                    }
                }
            }

            if (isRecording && (isMapEnabled || isInfoPanelEnabled)) {
                mapUpdateHandler.postDelayed(this.mapUpdateRunnable, MAP_UPDATE_INTERVAL);
            }
        };

        lastOverlayUpdateTime = 0;
        mapUpdateHandler.post(mapUpdateRunnable);
        Log.d(TAG, "地图/信息面板Bitmap定时更新已启动: map=" + MAP_UPDATE_INTERVAL + "ms, overlay=" + OVERLAY_UPDATE_INTERVAL + "ms");
    }

    private void handleMapScreenshot(android.graphics.Bitmap bitmap) {
        mapScreenshotPending = false;
        if (bitmap == null || bitmap.isRecycled()) return;
        if (glesRenderer == null) {
            bitmap.recycle();
            return;
        }
        android.graphics.Bitmap copy = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
        bitmap.recycle();
        if (copy != null) {
            glesRenderer.updateMapBitmap(copy);
        }
    }

    // ⭐ Step2: 按 tgtW×tgtH 创建 bitmap 并缩放绘制，让信息面板以 videoSize 分辨率离屏渲染（与预览分辨率解耦）
    //   tgtW/tgtH <= 0 时回退到 view 自身尺寸（保持旧行为，防止 setupOverlayPosition 未调用的边界）
    private android.graphics.Bitmap captureViewBitmap(View view, int tgtW, int tgtH) {
        int viewW = view.getWidth();
        int viewH = view.getHeight();
        if (viewW <= 0 || viewH <= 0) return null;
        int bmpW = tgtW > 0 ? tgtW : viewW;
        int bmpH = tgtH > 0 ? tgtH : viewH;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        canvas.scale((float) bmpW / viewW, (float) bmpH / viewH);
        view.draw(canvas);
        return bmp;
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

    private void addVideoOption(Size size, int fps, Range<Integer> fpsRange) {
        availableVideoSizes.add(size);
        availableVideoFpsList.add(fps);
        availableVideoFpsRanges.add(fpsRange);
        resolutionDisplayNames.add(size.getHeight() + "p " + fps + "fps (" + size.getWidth() + "x" + size.getHeight() + ")");
    }

    /** 在 fps 范围数组中找最匹配 targetFps 的范围。
     *  优先设备提供的精确 [t,t]；若没有但有包含 t 的范围（如 [60,120]），则构造 [t,t] 固定帧率
     *  （匹配编码器 KEY_FRAME_RATE，避免变帧率导致时间戳/编码问题）。 */
    private Range<Integer> findBestFpsRange(Range<Integer>[] ranges, int targetFps) {
        if (ranges == null) return null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() == targetFps && r.getUpper() == targetFps) return r;
        }
        for (Range<Integer> r : ranges) {
            if (r.getLower() <= targetFps && r.getUpper() >= targetFps) {
                return new Range<>(targetFps, targetFps);
            }
        }
        return null;
    }

    private void applyVideoOption(int position) {
        videoSize = availableVideoSizes.get(position);
        previewSize = videoSize;
        selectedVideoFps = availableVideoFpsList.get(position);
        selectedFpsRange = availableVideoFpsRanges.get(position);
        // ⭐ videoSize 变化时同步 GLES viewport 和预览 SurfaceTexture buffer：
        //   setVideoSize 只在 startGlesAndCameraX 调过一次，spinner 改分辨率后若不同步，
        //   录制时 encoder surface=新videoSize 而 GLES viewport=旧值 → glViewport 超出 surface，
        //   画面只渲染到 surface 左下角，其余黑屏（即"4K 录制只看到 1080p 左下角"的根因）。
        if (glesRenderer != null) {
            glesRenderer.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        }
        if (textureView != null && textureView.isAvailable() && textureView.getSurfaceTexture() != null) {
            textureView.getSurfaceTexture().setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
        }
        // ⭐ 重绑 CameraX 让 setTargetResolution(新 videoSize) 生效：之前不重绑时 CameraX 一直用旧 bind 的目标分辨率，
        //   导致 4K 录制相机仍给 1080p（看不出清晰度提升）。录制中不重绑（会中断编码器），新分辨率下次录制生效。
        if (glesRenderer != null && glesInitialized && !isRecording) {
            Log.d(TAG, "分辨率变更，重绑 CameraX: " + videoSize.getWidth() + "x" + videoSize.getHeight());
            startCameraX();
        } else if (isRecording) {
            Log.w(TAG, "录制中，分辨率变更将在下次录制生效: " + videoSize.getWidth() + "x" + videoSize.getHeight());
        }
    }

    private int findDefaultVideoOptionIndex() {
        int firstIndex = -1;
        for (int i = 0; i < availableVideoSizes.size(); i++) {
            if (availableVideoSizes.get(i).getHeight() == 1080) {
                return i;
            }
            if (firstIndex < 0) {
                firstIndex = i;
            }
        }
        return firstIndex >= 0 ? firstIndex : (availableVideoSizes.isEmpty() ? -1 : 0);
    }

    private void sortVideoOptions() {
        for (int i = 0; i < availableVideoSizes.size() - 1; i++) {
            for (int j = i + 1; j < availableVideoSizes.size(); j++) {
                Size left = availableVideoSizes.get(i);
                Size right = availableVideoSizes.get(j);
                if (left.getHeight() < right.getHeight()) {
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

        String tempName = resolutionDisplayNames.get(i);
        resolutionDisplayNames.set(i, resolutionDisplayNames.get(j));
        resolutionDisplayNames.set(j, tempName);
    }

    private boolean isSupportedVideoSize(Size size, int maxSupportedHeight) {
        return maxSupportedHeight <= 0 || size.getHeight() <= maxSupportedHeight;
    }

    private int getMaxSupportedHeight(StreamConfigurationMap map) {
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

    private Range<Integer> chooseFpsRange(Range<Integer>[] fpsRanges, int targetFps) {
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
                                            Range<Integer>[] fpsRanges, int maxSupportedHeight) {
        int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        StringBuilder capabilityText = new StringBuilder();
        if (capabilities != null) {
            for (int capability : capabilities) {
                if (capabilityText.length() > 0) capabilityText.append(", ");
                capabilityText.append(capability);
            }
        }

        StringBuilder ranges = new StringBuilder();
        if (fpsRanges != null) {
            for (Range<Integer> range : fpsRanges) {
                if (ranges.length() > 0) ranges.append(", ");
                ranges.append(range);
            }
        }

        Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
        StringBuilder sizeText = new StringBuilder();
        if (videoSizes != null) {
            for (Size size : videoSizes) {
                if (sizeText.length() > 0) sizeText.append(", ");
                sizeText.append(size.getWidth()).append("x").append(size.getHeight());
            }
        }

        Log.d(TAG, "相机视频能力: capabilities=[" + capabilityText + "], aeFpsRanges=[" + ranges
                + "], videoSizes=[" + sizeText + "], selectedFpsRange=" + selectedFpsRange
                + ", maxSupportedHeight=" + maxSupportedHeight);
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

    /**
     * ⭐ 关闭摄像头：解绑 CameraX + 释放 GLES + 停止录制
     */
    private void closeCamera() {
        // 如果正在录制，先同步停止（用于 onPause/onDestroy）
        if (isRecording) {
            if (isStoppingRecording && stopRecordingThread != null) {
                // 异步停止正在执行，等待完成
                Log.d(TAG, "closeCamera: 等待异步停止录制线程完成...");
                try {
                    stopRecordingThread.join(3000);
                } catch (InterruptedException e) {
                    Log.e(TAG, "等待停止录制线程失败", e);
                }
                stopRecordingThread = null;
            } else {
                // 同步停止录制
                stopRecordingSync();
            }
        }

        // 解绑 CameraX（停止 Camera 输出到 GLES）
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            Log.d(TAG, "CameraX 已解绑");
        }

        // 释放 GLES 渲染器
        if (glesRenderer != null) {
            glesRenderer.release();
            glesRenderer = null;
            Log.d(TAG, "GLES 渲染器已释放");
        }
        glesInitialized = false;

        // 释放 previewSurface
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    /**
     * 同步停止录制（用于 closeCamera）
     */
    private void stopRecordingSync() {
        if (!isRecording) return;
        try {
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
                }
            }

            Log.d(TAG, "录制完成(同步): videoFrameCount=" + videoFrameCount + ", audioFrameCount=" + audioFrameCount);

        } catch (Exception e) {
            Log.e(TAG, "Stop recording error", e);
            if (currentVideoUri != null) {
                getContentResolver().delete(currentVideoUri, null, null);
            }
        } finally {
            isRecording = false;
            isStoppingRecording = false;
            currentVideoUri = null;
            currentTempFilePath = null;
            if (mapUpdateRunnable != null) {
                mapUpdateHandler.removeCallbacks(mapUpdateRunnable);
                mapUpdateRunnable = null;
            }
            videoFrameCount = 0;
            audioFrameCount = 0;
            btnRecord.setText("录制");
            btnRecord.setEnabled(true);
            btnRecord.setBackgroundResource(R.drawable.record_button_bg);
            tvRecordDuration.setVisibility(View.GONE);
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (textureView == null || viewWidth == 0 || viewHeight == 0) return;
        // 横屏锁定应用：sensorOrientation(90) 与 displayRotation(90) 抵消，净旋转 0°，
        // GLES 输出已是正确方向的横屏画面，TextureView 无需旋转。布局已保证 16:9，直接显示。
        textureView.setTransform(new Matrix());
    }
}
