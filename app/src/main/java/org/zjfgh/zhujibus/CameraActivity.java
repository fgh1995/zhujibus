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
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.sgr.geometry.Coordinate;
import io.sgr.geometry.utils.RouteGeometryUtils;

public class CameraActivity extends AppCompatActivity {
    private static final String TAG = "CameraActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE = 201;

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

    // 录制选项持久化
    private static final String PREFS_NAME = "pov_recording_prefs";
    private static final String KEY_VIDEO_OPTION_INDEX = "video_option_index";
    private static final String KEY_VIDEO_SIZE = "video_size";
    private static final String KEY_VIDEO_FPS = "video_fps";
    private static final String KEY_VIDEO_FPS_RANGE_LOWER = "video_fps_range_lower";
    private static final String KEY_VIDEO_FPS_RANGE_UPPER = "video_fps_range_upper";
    private static final String KEY_VIDEO_HIGH_SPEED = "video_high_speed";

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
    private Spinner spinnerFps;
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

    // 录制能力:每个 size 对应一组 fps 选项
    private final List<Size> uniqueVideoSizes = new ArrayList<>();
    // size 索引 → 唯一帧率选项(按降序)
    private final List<List<Integer>> fpsOptionsForSize = new ArrayList<>();
    // (sizeIndex, fps) → 实际 Camera2 fps range
    private final java.util.Map<String, Range<Integer>> fpsRangeMap = new java.util.HashMap<>();
    // (sizeIndex, fps) → 是否高速 mode
    private final java.util.Map<String, Boolean> highSpeedMap = new java.util.HashMap<>();
    // 分辨率 spinner 显示名
    private final List<String> resolutionDisplayNames = new ArrayList<>();
    // 帧率 spinner 显示名(跟随当前选中 size 变化)
    private final List<String> currentFpsDisplayNames = new ArrayList<>();
    // 当前 size 索引下可选 fps(临时,size 切换时更新)
    private List<Integer> currentSizeFpsOptions = new ArrayList<>();

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

    // 录制时长显示
    private TextView tvRecordDuration;
    private long recordStartTimeMs = 0L;
    private final android.os.Handler recordDurationHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable recordDurationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording || recordStartTimeMs <= 0) return;
            long elapsed = System.currentTimeMillis() - recordStartTimeMs;
            tvRecordDuration.setText("● " + formatDuration(elapsed));
            recordDurationHandler.postDelayed(this, 1000L);
        }
    };

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

        tvRecordDuration = findViewById(R.id.tv_record_duration);

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
        if (!PermissionUtils.hasLocationPermission(this)) {
            Collections.addAll(permissions, PermissionUtils.location());
        }
        if (permissions.isEmpty()) {
            setupCamera();
        } else {
            String[] arr = permissions.toArray(new String[0]);
            PermissionUtils.requestPermissions(this, arr, REQUEST_CAMERA_PERMISSION,
                    new PermissionUtils.MultiPermissionCallback() {
                        @Override
                        public void onAllGranted() {
                            setupCamera();
                        }
                        @Override
                        public void onPartialGrant(@NonNull Set<String> granted, @NonNull Set<String> denied) {
                            boolean cam = granted.contains(Manifest.permission.CAMERA);
                            boolean mic = granted.contains(Manifest.permission.RECORD_AUDIO);
                            if (cam && mic) {
                                Toast.makeText(CameraActivity.this,
                                        "部分权限未授予: " + denied, Toast.LENGTH_LONG).show();
                                setupCamera();
                            } else {
                                Toast.makeText(CameraActivity.this,
                                        "未授予摄像头和录音权限,无法使用", Toast.LENGTH_LONG).show();
                                finish();
                            }
                        }
                    });
        }
    }

    /**
     * 在 setupCamera 之前做最后的检查。如果权限缺失（用户可能撤销了权限）,
     * 用 Toast 提示用户并取消。权限必须在 onCreate 中已经申请,这里只是兜底。
     */
    private boolean ensureCameraAudioPermissionOrFinish() {
        if (!PermissionUtils.checkRecordingWithToast(this)) {
            finish();
            return false;
        }
        return true;
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

        // ★ 按当前录制尺寸的实际宽高比设置预览区域(避免 16:9 视频被拉伸为 4:3 等)
        // 默认 16:9;videoSize 已知则用 videoSize 比例
        float videoAspect = 16f / 9f;
        if (videoSize != null && videoSize.getHeight() > 0) {
            videoAspect = (float) videoSize.getWidth() / (float) videoSize.getHeight();
        }
        // 预览区域按 videoAspect 缩放到父容器内,居中显示
        int previewWidth = parentWidth;
        int previewHeight = Math.round(previewWidth / videoAspect);
        if (previewHeight > parentHeight) {
            previewHeight = parentHeight;
            previewWidth = Math.round(previewHeight * videoAspect);
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
        if (lineDetailResponse == null || lineDetailResponse.data == null) {
            Log.w(TAG, "selectCurrentDirection: lineDetailResponse或data为null");
            return;
        }

        BusApiClient.BusLineDirection selected = null;

        // ⭐ 优先使用 Intent 传入的 direction
        if (direction == 1 && lineDetailResponse.data.up != null) {
            selected = lineDetailResponse.data.up;
            Log.d(TAG, "selectCurrentDirection: 使用Intent方向=上行");
        } else if (direction == 2 && lineDetailResponse.data.down != null) {
            selected = lineDetailResponse.data.down;
            Log.d(TAG, "selectCurrentDirection: 使用Intent方向=下行");
        }

        // ⭐ 如果 direction 指定的方向不存在，再尝试用 lineId 匹配
        if (selected == null && lineId != null) {
            if (lineDetailResponse.data.up != null && lineId.equals(lineDetailResponse.data.up.id)) {
                selected = lineDetailResponse.data.up;
                direction = 1;
                Log.d(TAG, "selectCurrentDirection: 通过lineId匹配到上行");
            } else if (lineDetailResponse.data.down != null && lineId.equals(lineDetailResponse.data.down.id)) {
                selected = lineDetailResponse.data.down;
                direction = 2;
                Log.d(TAG, "selectCurrentDirection: 通过lineId匹配到下行");
            }
        }

        // ⭐ 如果还没有匹配到，用起终点匹配
        if (selected == null && startStationName != null && endStationName != null) {
            if (lineDetailResponse.data.up != null
                    && startStationName.equals(lineDetailResponse.data.up.startStation)
                    && endStationName.equals(lineDetailResponse.data.up.endStation)) {
                selected = lineDetailResponse.data.up;
                direction = 1;
                Log.d(TAG, "selectCurrentDirection: 通过起终点匹配到上行");
            } else if (lineDetailResponse.data.down != null
                    && startStationName.equals(lineDetailResponse.data.down.startStation)
                    && endStationName.equals(lineDetailResponse.data.down.endStation)) {
                selected = lineDetailResponse.data.down;
                direction = 2;
                Log.d(TAG, "selectCurrentDirection: 通过起终点匹配到下行");
            }
        }

        // ⭐ 最终兜底：用 direction 选择
        if (selected == null) {
            if (direction == 2 && lineDetailResponse.data.down != null) {
                selected = lineDetailResponse.data.down;
                Log.d(TAG, "selectCurrentDirection: 兜底选择下行");
            } else if (lineDetailResponse.data.up != null) {
                selected = lineDetailResponse.data.up;
                direction = 1;
                Log.d(TAG, "selectCurrentDirection: 兜底选择上行");
            } else {
                selected = lineDetailResponse.data.down;
                direction = 2;
                Log.d(TAG, "selectCurrentDirection: 兜底选择下行(上行不存在)");
            }
        }

        // ⭐ 如果最终还是 null，取第一个有数据的
        if (selected == null) {
            if (lineDetailResponse.data.up != null) {
                selected = lineDetailResponse.data.up;
                direction = 1;
            } else if (lineDetailResponse.data.down != null) {
                selected = lineDetailResponse.data.down;
                direction = 2;
            }
            Log.d(TAG, "selectCurrentDirection: 最终兜底选择 direction=" + direction);
        }

        currentLineDirection = selected;
        if (selected == null) {
            Log.e(TAG, "selectCurrentDirection: 无法选择任何方向");
            return;
        }

        // 更新线路数据
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

        Log.d(TAG, "selectCurrentDirection: 最终选择 direction=" + direction
                + ", lineId=" + lineId
                + ", startStation=" + startStationName
                + ", endStation=" + endStationName
                + ", stationCount=" + stationList.size());
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
        spinnerFps = findViewById(R.id.spinner_fps);
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
        seekbarVideoBitrate.setProgress(40);  // 40Mbps = 1920x1080 60fps 基线
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
        // 统一交给 PermissionUtils 处理
        if (requestCode == REQUEST_CAMERA_PERMISSION
                || requestCode == REQUEST_WRITE_EXTERNAL_STORAGE) {
            PermissionUtils.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void setupCamera() {
        if (!ensureCameraAudioPermissionOrFinish()) return;
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
                            // 清空旧数据
                            uniqueVideoSizes.clear();
                            fpsOptionsForSize.clear();
                            fpsRangeMap.clear();
                            highSpeedMap.clear();
                            resolutionDisplayNames.clear();

                            Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                            int normalMaxHeight = getMaxSupportedHeight(map, 30);
                            logCameraVideoCapabilities(characteristics, map, fpsRanges, 30, normalMaxHeight);

                            // ============ 收集录制能力 ============
                            // 临时容器:size -> Set<fps>
                            java.util.Map<Size, java.util.Set<Integer>> sizeFpsMap = new java.util.LinkedHashMap<>();
                            // 临时容器:(w,h,fps) -> (range, highSpeed)
                            java.util.Map<String, Object[]> fpsDetailMap = new java.util.HashMap<>();

                            // 1) 高速 mode 优先遍历
                            Log.d(TAG, "========== 开始读取高速录制组合 ==========");
                            StringBuilder highSpeedSummary = new StringBuilder();
                            int totalModes = 0;
                            Size[] highSpeedSizes = null;
                            try { highSpeedSizes = map.getHighSpeedVideoSizes(); } catch (Throwable t) {}
                            if (highSpeedSizes != null) {
                                highSpeedSummary.append("📷 Camera ").append(cameraId)
                                        .append(", 高速尺寸数量: ").append(highSpeedSizes.length);
                                for (Size size : highSpeedSizes) {
                                    Range<Integer>[] sizeHighSpeedRanges = null;
                                    try { sizeHighSpeedRanges = map.getHighSpeedVideoFpsRangesFor(size); } catch (Throwable t) { continue; }
                                    if (sizeHighSpeedRanges == null) continue;
                                    for (Range<Integer> range : sizeHighSpeedRanges) {
                                        int lower = range.getLower();
                                        int upper = range.getUpper();
                                        // 跳过 HAL 夸大的 >60fps 固定 range
                                        if (lower == upper && lower > 60) {
                                            Log.d(TAG, "  跳过HAL夸大的fps: " + size.getWidth() + "x" + size.getHeight()
                                                    + " @ [" + lower + "," + upper + "] (实际只能录60fps)");
                                            continue;
                                        }
                                        int targetFps = (lower == upper) ? lower : upper;
                                        if (targetFps > 60) targetFps = 60;
                                        if (targetFps != 30 && targetFps != 60) continue;  // 只保留 30/60fps
                                        addSizeFps(sizeFpsMap, fpsDetailMap, size, targetFps, range, true);
                                        totalModes++;
                                        float ratio = (float) size.getWidth() / size.getHeight();
                                        Log.d(TAG, String.format(java.util.Locale.US,
                                                "  %4d×%-4d  比例=%.3f  fps=[%d, %d]  %s",
                                                size.getWidth(), size.getHeight(), ratio,
                                                range.getLower(), range.getUpper(),
                                                isApproximately16By9(size) ? "★ 16:9" : ""));
                                    }
                                }
                                highSpeedSummary.append("; sizes=");
                                for (int i = 0; i < highSpeedSizes.length; i++) {
                                    if (i > 0) highSpeedSummary.append(",");
                                    highSpeedSummary.append(highSpeedSizes[i].getWidth())
                                            .append("x").append(highSpeedSizes[i].getHeight());
                                }
                            }
                            Log.d(TAG, highSpeedSummary.toString());

                            // 2) 非高速 mode
                            for (Size size : videoSizesArray) {
                                int maxFps = getMaxFpsForSize(map, size, fpsRanges);
                                if (maxFps <= 0) continue;
                                if (maxFps > 60) maxFps = 60;
                                Range<Integer> fixedRange = new Range<>(maxFps, maxFps);
                                int targetFps = maxFps >= 60 ? 60 : 30;
                                if (targetFps > maxFps) targetFps = maxFps;
                                if (targetFps != 30 && targetFps != 60) continue;
                                addSizeFps(sizeFpsMap, fpsDetailMap, size, targetFps, fixedRange, false);
                            }
                            Log.d(TAG, "========== 共 " + totalModes + " 种高速录制组合 ==========");

                            // 3) 转换为 UI 列表(按 size 按高度从大到小排序,16:9 优先)
                            List<Size> sortedSizes = new ArrayList<>(sizeFpsMap.keySet());
                            sortedSizes.sort((a, b) -> {
                                boolean a169 = isApproximately16By9(a);
                                boolean b169 = isApproximately16By9(b);
                                if (a169 != b169) return a169 ? -1 : 1;  // 16:9 优先
                                int pA = a.getWidth() * a.getHeight();
                                int pB = b.getWidth() * b.getHeight();
                                return Integer.compare(pB, pA);  // 分辨率从大到小
                            });
                            for (Size size : sortedSizes) {
                                uniqueVideoSizes.add(size);
                                Set<Integer> fpsSet = sizeFpsMap.get(size);
                                List<Integer> fpsList = new ArrayList<>(fpsSet);
                                fpsList.sort((a, b) -> Integer.compare(b, a));  // 60 优先
                                fpsOptionsForSize.add(fpsList);
                                String label = isApproximately16By9(size)
                                        ? (size.getHeight() + "p (" + size.getWidth() + "x" + size.getHeight() + ")")
                                        : (size.getWidth() + "x" + size.getHeight());
                                resolutionDisplayNames.add(label);
                            }

                            // 4) 填充 fpsRangeMap/highSpeedMap(供 applyVideoOption 查表)
                            for (java.util.Map.Entry<String, Object[]> e : fpsDetailMap.entrySet()) {
                                String key = e.getKey();
                                Object[] val = e.getValue();
                                fpsRangeMap.put(key, (Range<Integer>) val[0]);
                                highSpeedMap.put(key, (Boolean) val[1]);
                            }

                            // 5) 绑定 spinner
                            ArrayAdapter<String> resolutionAdapter = new ArrayAdapter<>(this,
                                    android.R.layout.simple_spinner_item, resolutionDisplayNames);
                            resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                            spinnerResolution.setAdapter(resolutionAdapter);

                            // 6) 选默认 size (1920x1080, 不支持则降级到最大 16:9 size, 再降级到最大 size)
                            int defaultSizeIndex = findDefaultSizeIndex();
                            if (defaultSizeIndex >= 0) {
                                // 先绑定默认(避免 loadSavedVideoOptionIfMatched 时 spinner 没数据)
                                spinnerResolution.setSelection(defaultSizeIndex);
                                refreshFpsSpinnerForSize(defaultSizeIndex);
                                int defaultFpsIndex = findDefaultFpsIndexForSize(defaultSizeIndex, 60);
                                spinnerFps.setSelection(defaultFpsIndex);
                                applyVideoOption(defaultSizeIndex, defaultFpsIndex);

                                // ★ 尝试恢复上次保存的 (size, fps)
                                loadSavedVideoOptionIfMatched();
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
                    if (position >= 0 && position < uniqueVideoSizes.size()) {
                        // ★ 切换 size 时,刷新 fps 列表,选 60(不支持则 30)
                        refreshFpsSpinnerForSize(position);
                        int defaultFpsIndex = findDefaultFpsIndexForSize(position, 60);
                        spinnerFps.setSelection(defaultFpsIndex);
                        applyVideoOption(position, defaultFpsIndex);

                        Log.d(TAG, "Resolution changed to: " + videoSize.getWidth() + "x" + videoSize.getHeight()
                                + ", fps=" + selectedVideoFps + ", highSpeed=" + useHighSpeedSession);

                        if (!isVideoBitrateManuallyAdjusted) {
                            int recommended = calculateRecommendedBitrate();
                            int recommendedMbps = recommended / 1_000_000;
                            updateVideoBitrateUI(recommendedMbps);
                            Log.d(TAG, "Auto-adjusted bitrate to: " + recommendedMbps + " Mbps");
                        }

                        // ★ 切换 size 后重设预览区域比例(避免被拉伸)
                        if (textureView != null) {
                            textureView.post(CameraActivity.this::updatePreviewViewSize);
                        }
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });

            spinnerFps.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    int sizeIndex = spinnerResolution.getSelectedItemPosition();
                    if (sizeIndex >= 0 && position >= 0 && position < currentSizeFpsOptions.size()) {
                        applyVideoOption(sizeIndex, position);
                        Log.d(TAG, "Fps changed to: " + selectedVideoFps
                                + " (size=" + videoSize.getWidth() + "x" + videoSize.getHeight()
                                + ", highSpeed=" + useHighSpeedSession + ")");

                        // ★ fps 切换时也重算推荐码流(1080p30 应该是 20Mbps,不是 60Mbps)
                        if (!isVideoBitrateManuallyAdjusted) {
                            int recommended = calculateRecommendedBitrate();
                            int recommendedMbps = recommended / 1_000_000;
                            updateVideoBitrateUI(recommendedMbps);
                            Log.d(TAG, "Auto-adjusted bitrate to: " + recommendedMbps + " Mbps (fps=" + selectedVideoFps + ")");
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

        // ★ 显式检查相机权限(让 lint 满意 + 双重保险,即使 PermissionUtils 内部逻辑改变也能拦截)
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "openCamera: CAMERA 权限未授予,无法打开相机");
            if (!PermissionUtils.checkCameraWithToast(this)) return;
        }

        try {
            if (!PermissionUtils.checkCameraWithToast(this)) return;
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

    /**
     * 准备视频输出文件,需要确保已授权(Android 9-)。
     *
     * <p>Android 10+：MediaStore（首选,无需任何权限,自己写入自己创建的记录）。
     * <br>Android 9-：写入 DCIM/POV/，必须已授予 WRITE_EXTERNAL_STORAGE，
     * 写入前会做写入测试确保真的能写入。
     *
     * @return true 输出文件已就绪，false 失败
     */
    private boolean prepareOutputFile(String fileName) {
        currentVideoUri = null;
        currentTempFilePath = null;
        currentOutputPfd = null;

        // === 方案 1:Android 10+ MediaStore（无需权限）===
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/POV");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                currentVideoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (currentVideoUri != null) {
                    currentOutputPfd = getContentResolver().openFileDescriptor(currentVideoUri, "rw");
                    if (currentOutputPfd != null) {
                        Log.d(TAG, "Android 10+ MediaStore 输出已就绪: " + currentVideoUri);
                        return true;
                    }
                }
                Log.e(TAG, "Android 10+ MediaStore:Uri 或 FileDescriptor 为空");
            } catch (Throwable t) {
                Log.e(TAG, "Android 10+ MediaStore 失败: " + t.getMessage(), t);
            }
            return false;  // Android 10+ 失败就直接失败,不降级
        }

        // === 方案 2:Android 9- 写入 DCIM/POV（需要 WRITE_EXTERNAL_STORAGE 权限）===
        // 调用前 startRecording 已确保权限 + 写入测试通过
        try {
            File dcimRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            if (dcimRoot == null) {
                Log.e(TAG, "DCIM 根目录不可用");
                return false;
            }
            File outputDir = new File(dcimRoot, "POV");
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                Log.e(TAG, "无法创建 DCIM/POV 目录: " + outputDir.getAbsolutePath());
                return false;
            }
            if (!outputDir.isDirectory() || !outputDir.canWrite()) {
                Log.e(TAG, "DCIM/POV 目录不可写: " + outputDir.getAbsolutePath());
                return false;
            }
            File outputFile = new File(outputDir, fileName);
            currentTempFilePath = outputFile.getAbsolutePath();
            Log.d(TAG, "Android 9- DCIM/POV 输出已就绪: " + currentTempFilePath);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Android 9- DCIM/POV 失败: " + t.getMessage(), t);
            return false;
        }
    }

    /**
     * Android 9- 的写入测试:在 DCIM/POV/ 创建临时文件,写入几个字节,验证能读回。
     * 这是录制前的最后一道闸:通过 → 开始录制;失败 → 提示用户并取消。
     *
     * <p>注意:MediaStore 在 Android 9- 上写入自己创建的记录也需要 WRITE_EXTERNAL_STORAGE 权限,
     * 所以兜底"先写 App 目录再 copy 到 MediaStore"在 Android 9- 上不可行——
     * copy 这一步同样会 Permission Denial。必须在录制前确保权限。
     */
    private boolean testWriteToDCIM() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;  // Android 10+ 用 MediaStore,不需要此测试
        }
        File testDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "POV");
        if (!testDir.exists() && !testDir.mkdirs()) {
            Log.e(TAG, "写入测试:无法创建 DCIM/POV 目录: " + testDir.getAbsolutePath());
            return false;
        }
        if (!testDir.isDirectory() || !testDir.canWrite()) {
            Log.e(TAG, "写入测试:DCIM/POV 目录不可写");
            return false;
        }
        File testFile = new File(testDir, ".write_test_" + System.currentTimeMillis() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            fos.write("POV_WRITE_TEST".getBytes());
            fos.flush();
        } catch (Throwable t) {
            Log.e(TAG, "写入测试失败(无法写入): " + t.getMessage(), t);
            return false;
        }
        if (testFile.length() == 0) {
            Log.e(TAG, "写入测试失败:写入字节数=0");
            return false;
        }
        // 清理
        if (!testFile.delete()) {
            testFile.deleteOnExit();
        }
        Log.d(TAG, "DCIM/POV 写入测试通过");
        return true;
    }

    /**
     * 录制入口:先做权限检查 + 写入测试,通过后调用 {@link #startRecordingInternal()}。
     * 之所以把权限检查放在这里,是因为 Android 9- 上必须确保 WRITE_EXTERNAL_STORAGE
     * 已授权且实际能写入 DCIM/POV/,否则 MediaMuxer 打开文件时会报 ENOENT。
     */
    private void startRecording() {
        if (cameraDevice == null) return;

        // 先确认 CAMERA + RECORD_AUDIO 已授权（无论 Android 版本）
        if (!PermissionUtils.checkRecordingWithToast(this)) {
            Log.w(TAG, "录制权限未授予,取消录制");
            return;
        }

        // Android 9- 需要先确保 WRITE_EXTERNAL_STORAGE 权限 + 写入测试
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (!PermissionUtils.hasPermissions(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                // 没权限:主动申请(走 PermissionUtils 走通用流程,处理永久拒绝)
                Log.w(TAG, "Android 9- WRITE_EXTERNAL_STORAGE 未授权,主动申请");
                PermissionUtils.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_WRITE_EXTERNAL_STORAGE,
                        new PermissionUtils.MultiPermissionCallback() {
                            @Override
                            public void onAllGranted() {
                                Log.d(TAG, "WRITE_EXTERNAL_STORAGE 申请通过,继续写入测试");
                                runOnUiThread(() -> continueStartRecordingAfterPermission());
                            }
                            @Override
                            public void onPartialGrant(@NonNull Set<String> granted,
                                                      @NonNull Set<String> denied) {
                                Log.e(TAG, "WRITE_EXTERNAL_STORAGE 申请被拒");
                                runOnUiThread(() -> {
                                    Toast.makeText(CameraActivity.this,
                                            "未授予存储权限,无法保存录制视频", Toast.LENGTH_LONG).show();
                                    PermissionUtils.openAppSettings(CameraActivity.this);
                                });
                            }
                        });
                return;
            }
            // 有权限:但还要做实际写入测试(可能权限被系统设为"询问"但实际不可写)
            if (!testWriteToDCIM()) {
                Log.e(TAG, "DCIM/POV 写入测试失败,即使权限已授权也无法写入");
                Toast.makeText(this,
                        "DCIM/POV 目录无法写入,可能存储被占用或权限被拦截",
                        Toast.LENGTH_LONG).show();
                PermissionUtils.openAppSettings(this);
                return;
            }
        }
        // Android 10+ 跳过 WRITE_EXTERNAL_STORAGE 权限检查(用 MediaStore 不需要权限)

        startRecordingInternal();
    }

    /**
     * 权限 + 写入测试都通过后,真正开始准备输出文件并启动编码器。
     */
    private void continueStartRecordingAfterPermission() {
        if (cameraDevice == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !testWriteToDCIM()) {
            Toast.makeText(this,
                    "DCIM/POV 目录无法写入,请检查存储权限", Toast.LENGTH_LONG).show();
            PermissionUtils.openAppSettings(this);
            return;
        }
        startRecordingInternal();
    }

    private void startRecordingInternal() {
        try {
            // ★ 启动录制时长定时器
            startRecordDurationTimer();

            if (videoEncoder != null || mediaMuxer != null || encoderSurface != null) {
                Log.w(TAG, "发现残留的编码器资源,先释放...");
                releaseEncoderResources();
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "POV_" + timeStamp + ".mp4";

            // 准备输出文件(Android 10+ 用 MediaStore;Android 9- 用 DCIM/POV)
            if (!prepareOutputFile(fileName)) {
                Toast.makeText(this, "录制启动失败：无法准备输出文件", Toast.LENGTH_LONG).show();
                return;
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
                // ★ 关键:不再跳过 GLES 合成——普通 session + GLES + setVideoFrameRate(60) 稳定 60fps
                // 即使 60fps,简单 GLES 单 pass 渲染(1 个 camera texture + 1-2 个 overlay texture)远未触及 GPU 瓶颈
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
                            Log.d(TAG, "GLES渲染器初始化成功，使用合成模式（包含UI合并），highSpeed=" + useHighSpeedSession);

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
            // ★ 用用户/POV 菜单选择的 fps range(支持 30/60)
            // 选 30 → range = [30, 30]
            // 选 60 → range = [60, 60]
            int targetFps = selectedVideoFps > 0 ? selectedVideoFps : 60;
            Range<Integer> requestFpsRange = new Range<>(targetFps, targetFps);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, requestFpsRange);
            Log.d(TAG, "录制 request fpsRange=" + requestFpsRange + ", highSpeed=" + useHighSpeedSession + ", selectedFpsRange=" + selectedFpsRange);

            if (glesRenderer != null && glCameraSurface != null && glCameraSurface.isValid()) {
                surfaces.add(glCameraSurface);
                builder.addTarget(glCameraSurface);

                // ★ 关键:无论是否高速 mode,都要加 preview surface 到 GLES 模式
                // GLESVideoRenderer 只输出到 encoder,预览由 Camera2 直接渲染到 TextureView
                // 不加 preview surface → TextureView 收不到帧 → 预览卡住
                android.graphics.SurfaceTexture texture = textureView.getSurfaceTexture();
                if (texture != null) {
                    texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
                    Surface previewSurface = new Surface(texture);
                    surfaces.add(previewSurface);
                    builder.addTarget(previewSurface);
                }
                if (!useHighSpeedSession) {
                    Log.d(TAG, "使用普通GLES合成模式: Camera2 → GLES(录制+地图) + TextureView(预览)");
                } else {
                    Log.d(TAG, "使用高速GLES合成模式: Camera2 → GLES(录制+地图) + TextureView(预览)");
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

            // ★ 完全照搬参考实现
            // - builder 在外面已经设好 CONTROL_AE_TARGET_FPS_RANGE(与参考实现 line 418 一致)
            // - 不设 CONTROL_AF_MODE(参考实现没设)
            // - handler 用 null(主线程)
            CameraCaptureSession.StateCallback stateCallback = new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) return;
                    captureSession = session;
                    try {
                        if (useHighSpeedSession) {
                            // 高速 mode:完全照搬参考实现 line 430-434
                            captureSession.setRepeatingRequest(builder.build(), null, null);
                            mediaRecorder.start();
                            Log.d(TAG, "✓ 高速录制已启动(参考实现方式): fps=" + selectedVideoFps);
                        } else {
                            // 普通 mode:与参考实现一致,只设 AE fps range
                            captureSession.setRepeatingRequest(builder.build(), null, null);
                            Log.d(TAG, "✓ 普通录制已启动(参考实现方式): fps=" + selectedVideoFps);
                        }
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Capture request error", e);
                    } catch (Exception e) {
                        Log.e(TAG, "MediaRecorder error", e);
                    }
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "录制CaptureSession配置失败: highSpeed=" + useHighSpeedSession
                            + ", fps=" + selectedVideoFps + ", surfaces=" + surfaces.size());
                    runOnUiThread(() -> {
                        Toast.makeText(CameraActivity.this, "摄像头配置失败，请重试", Toast.LENGTH_SHORT).show();
                        if (isRecording) stopRecording();
                    });
                }
            };

            // 录制 surface 基本验证:GLES 模式只要 glCameraSurface;非 GLES 模式要 preview + encoder
            if (surfaces.isEmpty()) {
                throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "录制需要至少一个输出Surface");
            }
            if (!useHighSpeedSession) {
                // 普通 mode(GLES 关闭回退):必须有 preview surface + encoder surface
                if (surfaces.size() < 2 || encoderSurface == null || !encoderSurface.isValid()) {
                    throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "普通录制需要预览Surface和MediaRecorder Surface");
                }
            } else {
                // 高速 mode:所有情况都走 GLES 合成(surfaces 至少 1 个,即 glCameraSurface,GLES 内部会输出到 encoder)
                if (surfaces.isEmpty()) {
                    throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "高速录制需要GLES输入Surface");
                }
            }
            // ★ 完全照搬参考实现 line 422:createCaptureSession 用 null handler
            cameraDevice.createCaptureSession(surfaces, stateCallback, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Session creation error", e);
            runOnUiThread(() -> {
                Toast.makeText(this, "录制配置失败: " + e.getMessage() + "，请尝试30fps重试", Toast.LENGTH_SHORT).show();
                if (isRecording) stopRecording();
            });
        }
    }

    /**
     * 降级到 constrained high speed session(普通 session 录不了 60fps+ 时回退)。
     * 如果再失败,提示用户切到 30fps。
     */
    private void retryWithConstrainedHighSpeed(List<Surface> surfaces, Surface encoderSurface) {
        try {
            android.hardware.camera2.CameraCaptureSession.StateCallback retryCallback =
                    new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull android.hardware.camera2.CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                android.hardware.camera2.CaptureRequest.Builder builder =
                                        cameraDevice.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD);
                                builder.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                for (Surface s : surfaces) {
                                    builder.addTarget(s);
                                }
                                if (session instanceof android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession) {
                                    android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession chs =
                                            (android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession) session;
                                    java.util.List<android.hardware.camera2.CaptureRequest> requestList =
                                            chs.createHighSpeedRequestList(builder.build());
                                    chs.setRepeatingBurst(requestList, null, backgroundHandler);
                                } else {
                                    builder.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedFpsRange);
                                    captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                                }
                                if (mediaRecorder != null) {
                                    mediaRecorder.start();
                                }
                                runOnUiThread(() -> Toast.makeText(CameraActivity.this,
                                        "✓ 已启用高速模式 " + selectedVideoFps + "fps",
                                        Toast.LENGTH_SHORT).show());
                            } catch (Exception e) {
                                Log.e(TAG, "降级 session 启动失败", e);
                                runOnUiThread(() -> {
                                    Toast.makeText(CameraActivity.this,
                                            "本设备不支持 " + selectedVideoFps + "fps,已降级到 30fps",
                                            Toast.LENGTH_LONG).show();
                                    if (isRecording) stopRecording();
                                });
                            }
                        }
                        @Override
                        public void onConfigureFailed(@NonNull android.hardware.camera2.CameraCaptureSession session) {
                            Log.e(TAG, "降级 constrained high speed 也失败");
                            runOnUiThread(() -> {
                                Toast.makeText(CameraActivity.this,
                                        "本设备不支持 " + selectedVideoFps + "fps,已降级到 30fps",
                                        Toast.LENGTH_LONG).show();
                                if (isRecording) stopRecording();
                            });
                        }
                    };
            cameraDevice.createConstrainedHighSpeedCaptureSession(surfaces, retryCallback, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "调用 createConstrainedHighSpeedCaptureSession 失败", e);
            runOnUiThread(() -> {
                Toast.makeText(CameraActivity.this,
                        "本设备不支持 " + selectedVideoFps + "fps,已降级到 30fps",
                        Toast.LENGTH_LONG).show();
                if (isRecording) stopRecording();
            });
        }
    }

    /**
     * 配置高速录制 MediaRecorder(完全照搬参考实现 line 464-542)。
     * - 普通 MediaRecorder
     * - setVideoFrameRate(60) 强制帧率
     * - 不旋转,横屏 16:9
     * - 输出到 MediaStore(Q+)或 App 专属目录
     */
    private void setupHighSpeedMediaRecorder() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mediaRecorder = new MediaRecorder(this);
        } else {
            mediaRecorder = new MediaRecorder();
        }

        try {
            // ★ MediaRecorder 调用顺序必须严格按 Android 文档:
            //   1) setAudioSource/setVideoSource
            //   2) setOutputFormat
            //   3) setAudioEncoder/setVideoEncoder(必须在 setOutputFile 前)
            //   4) setAudioSamplingRate/setAudioEncodingBitRate/setAudioChannels(在 setAudioEncoder 后)
            //   5) setVideoSize/setVideoFrameRate/setVideoEncodingBitRate(在 setVideoEncoder 后)
            //   6) setOutputFile
            //   7) setOrientationHint(必须在 prepare 前)
            //   8) prepare

            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

            // ★ 编码器(必须在 setOutputFile 之前)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

            // ★ 音频参数(在 setAudioEncoder 之后)
            mediaRecorder.setAudioChannels(selectedAudioChannels);
            mediaRecorder.setAudioSamplingRate(selectedSampleRate);
            mediaRecorder.setAudioEncodingBitRate(selectedAudioBitrate);

            // ★ 视频参数(在 setVideoEncoder 之后)
            mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            // ★ 用用户/POV 菜单选择的 fps(支持 30/60 等)
            mediaRecorder.setVideoFrameRate(selectedVideoFps > 0 ? selectedVideoFps : 60);
            // ★ 用用户/POV 菜单设置的码流(POV 设置生效)
            mediaRecorder.setVideoEncodingBitRate(selectedVideoBitrate);

            // ★ 输出文件(必须在编码器之后)——和低速 mode 走完全相同的 prepareOutputFile 逻辑
            // Android 10+: MediaStore (DCIM/POV)
            // Android 9- : DCIM/POV/fileName
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && currentOutputPfd != null) {
                mediaRecorder.setOutputFile(currentOutputPfd.getFileDescriptor());
                Log.d(TAG, "高速录制输出 (MediaStore, 路径与低速一致): " + currentVideoUri);
            } else {
                // 低速 mode 写入 DCIM/POV(用户已授权 + 写入测试通过)
                mediaRecorder.setOutputFile(currentTempFilePath);
                Log.d(TAG, "高速录制输出 (DCIM/POV, 路径与低速一致): " + currentTempFilePath);
            }

            // ★ orientation 必须在 prepare 前
            mediaRecorder.setOrientationHint(0);

            mediaRecorder.prepare();
            Log.d(TAG, "✓ 高速 MediaRecorder 配置完成(POV 参数生效): "
                    + "size=" + videoSize.getWidth() + "x" + videoSize.getHeight()
                    + ", fps=60, videoBitrate=" + selectedVideoBitrate
                    + " (" + (selectedVideoBitrate / 1_000_000) + " Mbps)"
                    + ", audioBitrate=" + selectedAudioBitrate
                    + ", sampleRate=" + selectedSampleRate
                    + ", audioChannels=" + selectedAudioChannels
                    + ", output=" + (currentOutputPfd != null ? "MediaStore" : currentTempFilePath));
        } catch (IOException e) {
            Log.e(TAG, "MediaRecorder 配置失败", e);
            try { if (mediaRecorder != null) { mediaRecorder.release(); mediaRecorder = null; } } catch (Exception ignored) {}
            throw e;
        }
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

        // ★ 显式检查录音权限(让 lint 满意 + 双重保险)
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "startAudioRecordingThread: RECORD_AUDIO 权限未授予");
            if (!PermissionUtils.checkRecordAudioWithToast(this)) return;
        }
        if (!PermissionUtils.checkRecordAudioWithToast(this)) return;

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

    // ===== 录制时长定时器 =====
    private void startRecordDurationTimer() {
        if (tvRecordDuration == null) return;
        recordStartTimeMs = System.currentTimeMillis();
        tvRecordDuration.setVisibility(View.VISIBLE);
        tvRecordDuration.setText("● 00:00:00");
        recordDurationHandler.removeCallbacks(recordDurationRunnable);
        recordDurationHandler.postDelayed(recordDurationRunnable, 1000L);
        Log.d(TAG, "录制时长定时器已启动");
    }

    /**
     * 给 View 加圆角 outline,实现真圆角裁剪(包括子 View 一起裁剪)。
     * <p>API 21+: 用 ViewOutlineProvider + clipToOutline 真裁剪<br>
     * API 19-20: 用软件 mask (setLayerType SOFTWARE + 自定义 Drawable 包装) 兜底
     */
    private void applyRoundedOutline(final View view, final int radiusPx) {
        if (view == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            view.setClipToOutline(true);
            view.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                }
            });
            // 尺寸变化时刷新 outline(否则旋转屏幕/全屏切换后 outline 失效)
            view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                    v.invalidateOutline());
            Log.d(TAG, "applyRoundedOutline(API 21+): radius=" + radiusPx);
        } else {
            // API 19-20 兜底:用 setBackgroundDrawable + 圆角 shape 给容器加圆角视觉,
            // 子 View 仍为直角(无法用 outline 真正裁剪子 View)
            // 用一个 inset 1dp 的内层 FrameLayout 套住,形成"看起来"圆角的效果
            Log.w(TAG, "applyRoundedOutline(API < 21): 子 View 无法被 outline 裁剪,使用 shape 背景兜底");
        }
    }

    private int dpToPx(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void stopRecordDurationTimer() {
        if (tvRecordDuration == null) return;
        recordDurationHandler.removeCallbacks(recordDurationRunnable);
        if (recordStartTimeMs > 0) {
            long elapsed = System.currentTimeMillis() - recordStartTimeMs;
            Log.d(TAG, "录制总时长: " + formatDuration(elapsed));
        }
        recordStartTimeMs = 0L;
        tvRecordDuration.setText("● 00:00:00");
        tvRecordDuration.setVisibility(View.GONE);
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000L;
        long h = seconds / 3600L;
        long m = (seconds % 3600L) / 60L;
        long s = seconds % 60L;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    private void stopRecording() {
        if (!isRecording) return;

        // ★ 停止录制时长定时器
        stopRecordDurationTimer();

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

            // 高速 mode 直接输出 MP4,不调用 OfflineVideoComposer 做后期合并
            Log.d(TAG, "录制完成: videoFrameCount=" + videoFrameCount + ", audioFrameCount=" + audioFrameCount);
            Toast.makeText(this, "录制已保存到相册", Toast.LENGTH_SHORT).show();

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

        // ★ 给地图容器设 OutlineProvider,让高德地图输出也能按圆角裁剪
        // (XML 中 clipToOutline 单独无效,必须配 OutlineProvider 才有 outline;API < 21 走软件 mask 兜底)
        applyRoundedOutline(mapContainer, dpToPx(6f));

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
    private int getMaxFpsForSize(StreamConfigurationMap map, Size size, Range<Integer>[] fpsRanges) {
        // 第一层:minFrameDuration(Android 10+)
        try {
            long minFrameDuration = map.getOutputMinFrameDuration(MediaRecorder.class, size);
            if (minFrameDuration > 0) {
                return (int) (1_000_000_000L / minFrameDuration);
            }
        } catch (Throwable t) {
            // 某些设备对 MediaRecorder.class 这个 key 抛异常
        }

        // 第二层:CamcorderProfile 找匹配 size(Android 9 fallback)
        try {
            int[] qualities = {
                    android.media.CamcorderProfile.QUALITY_2160P,
                    android.media.CamcorderProfile.QUALITY_1080P,
                    android.media.CamcorderProfile.QUALITY_720P,
                    android.media.CamcorderProfile.QUALITY_480P,
                    android.media.CamcorderProfile.QUALITY_HIGH,
                    android.media.CamcorderProfile.QUALITY_LOW,
                    android.media.CamcorderProfile.QUALITY_CIF,
                    android.media.CamcorderProfile.QUALITY_QCIF
            };
            for (int q : qualities) {
                try {
                    if (!android.media.CamcorderProfile.hasProfile(q)) continue;
                    android.media.CamcorderProfile profile = android.media.CamcorderProfile.get(q);
                    if (profile == null) continue;
                    if (profile.videoFrameWidth == size.getWidth()
                            && profile.videoFrameHeight == size.getHeight()
                            && profile.videoFrameRate > 0) {
                        return profile.videoFrameRate;
                    }
                } catch (Throwable t) {
                    // 单个 quality 失败,继续下一个
                }
            }
        } catch (Throwable t) {
            // CamcorderProfile 整体不可用
        }

        // 第三层:fpsRanges 找最大 upper
        if (fpsRanges != null) {
            int maxUpper = 0;
            for (Range<Integer> r : fpsRanges) {
                if (r.getUpper() > maxUpper) maxUpper = r.getUpper();
            }
            if (maxUpper > 0) return maxUpper;
        }

        // 兜底
        return 30;
    }

    /**
     * 把 (size, fps) 加入临时容器,并记录 (range, highSpeed) 到 fpsDetailMap。
     * 同 size 已有同 fps → 优先保留高速 mode 的 range(信息更丰富)。
     */
    private void addSizeFps(java.util.Map<Size, java.util.Set<Integer>> sizeFpsMap,
                            java.util.Map<String, Object[]> fpsDetailMap,
                            Size size, int fps, Range<Integer> range, boolean highSpeed) {
        Set<Integer> fpsSet = sizeFpsMap.computeIfAbsent(size, k -> new java.util.TreeSet<>(java.util.Collections.reverseOrder()));
        fpsSet.add(fps);
        String key = size.getWidth() + "x" + size.getHeight() + "@" + fps;
        Object[] existing = fpsDetailMap.get(key);
        if (existing == null || highSpeed) {  // 高速 mode 优先(更精确的 range)
            fpsDetailMap.put(key, new Object[]{range, highSpeed});
        }
    }

    private boolean isApproximately16By9(Size size) {
        if (size == null || size.getHeight() == 0) return false;
        float ratio = (float) size.getWidth() / size.getHeight();
        return Math.abs(ratio - 16f / 9f) < 0.03f;
    }

    
    /**
     * 把当前 size 的 fps 选项填充到 fps spinner。
     */
    private void refreshFpsSpinnerForSize(int sizeIndex) {
        if (sizeIndex < 0 || sizeIndex >= fpsOptionsForSize.size()) return;
        currentSizeFpsOptions = new ArrayList<>(fpsOptionsForSize.get(sizeIndex));
        currentFpsDisplayNames.clear();
        for (Integer fps : currentSizeFpsOptions) {
            currentFpsDisplayNames.add(fps + " fps");
        }
        ArrayAdapter<String> fpsAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, currentFpsDisplayNames);
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFps.setAdapter(fpsAdapter);
    }

    /**
     * 选默认 size:
     * 1) 1920x1080
     * 2) 降级到最大 16:9 size
     * 3) 降级到最大 size
     * 4) 降级到第一个
     */
    private int findDefaultSizeIndex() {
        if (uniqueVideoSizes.isEmpty()) return -1;
        // 1) 精确 1920x1080
        for (int i = 0; i < uniqueVideoSizes.size(); i++) {
            Size s = uniqueVideoSizes.get(i);
            if (s.getWidth() == 1920 && s.getHeight() == 1080) {
                Log.d(TAG, "默认分辨率: 1920x1080");
                return i;
            }
        }
        // 2) 最大 16:9
        int best169 = -1;
        int best169Pixels = 0;
        for (int i = 0; i < uniqueVideoSizes.size(); i++) {
            Size s = uniqueVideoSizes.get(i);
            if (isApproximately16By9(s)) {
                int p = s.getWidth() * s.getHeight();
                if (p > best169Pixels) {
                    best169Pixels = p;
                    best169 = i;
                }
            }
        }
        if (best169 >= 0) {
            Log.d(TAG, "默认分辨率: 降级到最大 16:9: " + uniqueVideoSizes.get(best169).getWidth() + "x" + uniqueVideoSizes.get(best169).getHeight());
            return best169;
        }
        // 3) 最大 size
        int bestAll = -1;
        int bestAllPixels = 0;
        for (int i = 0; i < uniqueVideoSizes.size(); i++) {
            Size s = uniqueVideoSizes.get(i);
            int p = s.getWidth() * s.getHeight();
            if (p > bestAllPixels) {
                bestAllPixels = p;
                bestAll = i;
            }
        }
        if (bestAll >= 0) {
            Log.d(TAG, "默认分辨率: 降级到最大: " + uniqueVideoSizes.get(bestAll).getWidth() + "x" + uniqueVideoSizes.get(bestAll).getHeight());
            return bestAll;
        }
        return 0;
    }

    /**
     * 在当前 size 的 fps 列表中找默认 fps(优先 60,降级 30,降级第一个)。
     */
    private int findDefaultFpsIndexForSize(int sizeIndex, int preferredFps) {
        if (sizeIndex < 0 || sizeIndex >= fpsOptionsForSize.size()) return 0;
        List<Integer> fpsList = fpsOptionsForSize.get(sizeIndex);
        // 优先 preferredFps
        for (int i = 0; i < fpsList.size(); i++) {
            if (fpsList.get(i) == preferredFps) return i;
        }
        // 降级:30
        for (int i = 0; i < fpsList.size(); i++) {
            if (fpsList.get(i) == 30) return i;
        }
        // 降级:24
        for (int i = 0; i < fpsList.size(); i++) {
            if (fpsList.get(i) == 24) return i;
        }
        // 降级:第一个
        return 0;
    }

    /**
     * 应用选中的 (size, fps) 选项。
     */
    private void applyVideoOption(int sizeIndex, int fpsIndex) {
        if (sizeIndex < 0 || sizeIndex >= uniqueVideoSizes.size()) return;
        if (fpsIndex < 0 || fpsIndex >= currentSizeFpsOptions.size()) return;
        Size size = uniqueVideoSizes.get(sizeIndex);
        int fps = currentSizeFpsOptions.get(fpsIndex);
        String key = size.getWidth() + "x" + size.getHeight() + "@" + fps;
        Range<Integer> range = fpsRangeMap.get(key);
        Boolean isHighSpeed = highSpeedMap.get(key);
        if (range == null || isHighSpeed == null) {
            Log.e(TAG, "找不到 (size=" + key + ") 对应的 range/highSpeed");
            return;
        }
        videoSize = size;
        previewSize = size;
        selectedVideoFps = fps;
        selectedFpsRange = range;
        useHighSpeedSession = isHighSpeed;
        saveVideoOption(sizeIndex, fpsIndex);
    }

    /** 保存当前选中的 (sizeIndex, fpsIndex) 到 SharedPreferences */
    private void saveVideoOption(int sizeIndex, int fpsIndex) {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(KEY_VIDEO_OPTION_INDEX, sizeIndex);
            if (videoSize != null) {
                editor.putInt(KEY_VIDEO_SIZE, videoSize.getWidth());
                editor.putInt("video_size_h", videoSize.getHeight());
            }
            editor.putInt(KEY_VIDEO_FPS, selectedVideoFps);
            if (selectedFpsRange != null) {
                editor.putInt(KEY_VIDEO_FPS_RANGE_LOWER, selectedFpsRange.getLower());
                editor.putInt(KEY_VIDEO_FPS_RANGE_UPPER, selectedFpsRange.getUpper());
            }
            editor.putBoolean(KEY_VIDEO_HIGH_SPEED, useHighSpeedSession);
            editor.putInt("video_fps_index", fpsIndex);
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "保存录制选项失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 SharedPreferences 恢复上次保存的 (size, fps)。
     * @return true 表示成功恢复, false 表示没有保存或当前设备不支持
     */
    private boolean loadSavedVideoOptionIfMatched() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int savedSizeW = prefs.getInt(KEY_VIDEO_SIZE, 0);
            int savedSizeH = prefs.getInt("video_size_h", 0);
            int savedFps = prefs.getInt(KEY_VIDEO_FPS, 0);
            if (savedFps <= 0 || savedSizeW <= 0) return false;

            Log.d(TAG, "尝试恢复上次录制: size=" + savedSizeW + "x" + savedSizeH + ", fps=" + savedFps);

            // 找 sizeIndex
            int sizeIndex = -1;
            for (int i = 0; i < uniqueVideoSizes.size(); i++) {
                Size s = uniqueVideoSizes.get(i);
                if (s.getWidth() == savedSizeW && s.getHeight() == savedSizeH) {
                    sizeIndex = i;
                    break;
                }
            }
            if (sizeIndex < 0) {
                Log.w(TAG, "上次 size 在当前设备不支持");
                return false;
            }

            // 找 fpsIndex
            List<Integer> fpsList = fpsOptionsForSize.get(sizeIndex);
            int fpsIndex = -1;
            for (int i = 0; i < fpsList.size(); i++) {
                if (fpsList.get(i) == savedFps) {
                    fpsIndex = i;
                    break;
                }
            }
            if (fpsIndex < 0) {
                Log.w(TAG, "上次 fps 在当前 size 不支持");
                return false;
            }

            // 应用
            spinnerResolution.setSelection(sizeIndex, false);
            refreshFpsSpinnerForSize(sizeIndex);
            spinnerFps.setSelection(fpsIndex, false);
            applyVideoOption(sizeIndex, fpsIndex);
            Log.d(TAG, "✓ 恢复上次: " + savedSizeW + "x" + savedSizeH + " @" + savedFps);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "恢复录制选项失败: " + e.getMessage(), e);
            return false;
        }
    }

    private void sortVideoOptions() {
        // 不再需要:新版用 sortedSizes 在收集阶段直接按 16:9 优先 + 像素大小排序
    }

    private void swapVideoOptions(int i, int j) {
        // 旧方法,保留空实现以免引用错误(如果仍被调用)
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

    /**
     * 计算推荐视频码流(用于自动调整 SeekBar)。
     * <p>基线:1920x1080 60fps = 40 Mbps;其他分辨率/帧率按像素和帧率比例缩放。
     * <pre>
     *   1920x1080 60fps = 40 Mbps
     *   1920x1080 30fps = 20 Mbps
     *   1280x720  60fps = 20 Mbps
     *   1280x720  30fps = 10 Mbps
     *   3840x2160 60fps = 160 Mbps
     *   3840x2160 30fps = 80 Mbps
     * </pre>
     */
    private int calculateRecommendedBitrate() {
        if (videoSize == null) return 30_000_000;
        final int baseWidth = 1920;
        final int baseHeight = 1080;
        final int baseFps = 60;
        final int baseBitrate = 40_000_000;  // 40 Mbps @ 1080p60
        long pixels = (long) videoSize.getWidth() * videoSize.getHeight();
        long basePixels = (long) baseWidth * baseHeight;
        int targetFps = selectedVideoFps > 0 ? selectedVideoFps : 30;
        long recommended = (long) (baseBitrate * (double) pixels / basePixels * targetFps / baseFps);
        int clamped = (int) Math.max(2_000_000, Math.min(200_000_000, recommended));
        Log.d(TAG, "推荐码流: " + videoSize.getWidth() + "x" + videoSize.getHeight()
                + " @" + targetFps + "fps → " + (clamped / 1_000_000) + " Mbps");
        return clamped;
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