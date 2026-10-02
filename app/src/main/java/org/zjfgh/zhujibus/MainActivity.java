package org.zjfgh.zhujibus;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import io.sgr.geometry.Coordinate;
import io.sgr.geometry.utils.GeometryUtils;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.android.material.button.MaterialButton;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.amap.api.services.core.AMapException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.zjfgh.zhujibus.view.AutoScrollTextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import com.amap.api.services.busline.BusLineItem;
import com.amap.api.services.busline.BusLineQuery;
import com.amap.api.services.busline.BusLineResult;
import com.amap.api.services.busline.BusLineSearch;
import com.amap.api.services.busline.BusStationItem;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.core.ServiceSettings;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String REMOTE_CONFIG_URL =
            "https://github.360967.xyz/https://raw.githubusercontent.com/fgh1995/zhujibus/refs/heads/master/app/build.gradle";
    // 原始 GitHub 下载路径（用于拼接加速链接）
    private static final String APK_DOWNLOAD_ORIGINAL =
            "https://github.com/fgh1995/zhujibus/releases/download/Release/zhujibus-";
    // 默认代理下载路径（公益服代理）
    private static final String APK_DOWNLOAD_BASE =
            "https://github.360967.xyz/https://github.com/fgh1995/zhujibus/releases/download/Release/zhujibus-";

    private RecyclerView recyclerView;
    private TextView tv_search_line;
    private AutoScrollTextView autoScrollTextView;
    private BusApiClient client;
    private double currentLatitude = 120.235555;
    private double currentLongitude = 29.713397;
    private TextView tvNoData;
    private boolean locationObtained = false;

    // ===== 地区定位 / 选择 =====
    private RegionManager regionManager;
    private RegionLocator regionLocator;
    private boolean regionLocatorStarted = false;
    private boolean regionPromptShown = false;
    /** 最近一次由定位解析出的「真实地区」（用于上报给 WS 服务端） */
    private BusRegion currentRealRegion;
    private final Handler regionFallbackHandler = new Handler(Looper.getMainLooper());
    /** 兜底：定位迟迟解析不到或失败时，仍提示用户手动选择地区（需求 1） */
    private final Runnable regionFallback = () -> {
        if (regionPromptShown || regionManager.isManualMode()) return;
        if (regionManager.getSelectedRegion() != null) return;
        runOnUiThread(this::showRegionFallbackDialog);
    };
    private TextView tvRegion;          // 左上角地区胶囊文字
    private final ActivityResultLauncher<android.content.Intent> regionSearchLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    BusRegion r = result.getData().getParcelableExtra("region");
                    if (r != null) {
                        regionManager.setSelectedRegion(r);
                        regionManager.setManualMode(false);
                        // 选择地区变化，立即上报 WS（真实地区 + 选择地区）
                        sendWsRegionInfo();
                        // 注意：不更新 promptedAdCode，去重基准仍是「真实定位地区」，
                        // 避免用户手动选了与定位不同的地区后，真实定位地区每次解析都重复弹窗。
                        updateRegionChip(r);
                        onRegionConfirmed(r);
                    }
                }
            });

    private LinearLayout llUpdateNotice;
    private LinearLayout llNotice;
    private HorizontalScrollTextView hstvUpdateNotice;
    private HorizontalScrollTextView hstvNotice;
    private RemoteConfig remoteConfig;
    private volatile boolean remoteConfigFetching = false;

    // ===== WebSocket 相关 =====
    private WebSocketManager webSocketManager;
    private String wsServerAddress = "";
    /** ⭐ 当前在线人数（-1 表示尚未收到广播） */
    private volatile int currentOnlineCount = -1;

    // ===== 语音包全量下载 =====
    private TextView tvVoicepackDownload;
    private TextView tvVoicepackStatus;
    private LinearLayout llVoicepackProgressRow;
    private ProgressBar pbVoicepack;
    private TextView tvVoicepackProgress;
    private volatile boolean voicepackDownloading = false;
    /** 配置状态监听器：统一由 VoicePackManager 推送 LOADING/READY/FAILED，避免本页面自行轮询/重试 */
    private VoicePackManager.ConfigStateListener voicepackConfigListener = null;
    // 过时文件清理通知
    private LinearLayout llVoicepackCleanup;
    private TextView tvVoicepackCleanupText;
    private TextView tvVoicepackCleanupClose;
    private LinearLayout llVoicepackDownload;
    private LinearLayout llNoticeBoard;
    private LinearLayout llNearbyBus;
    /** 本页面已展示过的清理事件时间戳，避免同一事件重复提示 */
    private long lastDisplayedCleanupTime = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            // Android 15+ 强制 Edge-to-Edge，动态避让状态栏，防止顶部 UI 重叠
            SystemBarUtils.fitSystemBars(this);
            // 注入 Context，供 BusApiClient / DeviceIdUtil 生成设备标识
            BusApiClient.init(this);
            recyclerView = findViewById(R.id.recyclerView);
            tv_search_line = findViewById(R.id.tv_search_line);
            autoScrollTextView = findViewById(R.id.auto_scroll_text);
            tvNoData = findViewById(R.id.tv_no_data);
            llUpdateNotice = findViewById(R.id.ll_update_notice);
            llNotice = findViewById(R.id.ll_notice);
            hstvUpdateNotice = findViewById(R.id.hstv_update_notice);
            hstvNotice = findViewById(R.id.hstv_notice);
            llVoicepackDownload = findViewById(R.id.ll_voicepack_download);
            llNoticeBoard = findViewById(R.id.ll_notice_board);
            llNearbyBus = findViewById(R.id.ll_nearby_bus);
            setupRegionUi();
            // ⭐ 关键修复：先挂一个空 adapter，避免首次 layout 时报 "No adapter attached"
            // 数据加载完后会被真正的 StationRouteAdapter 替换
            recyclerView.setAdapter(new BusStationAdapter());
            client = new BusApiClient();
            if (PermissionUtils.hasLocationPermission(this)) {
                startGpsIfNeeded();
                ensureRegionLocating();
            } else {
                PermissionUtils.requestLocationPermission(this, new PermissionUtils.PermissionCallback() {
                    @Override
                    public void onPermissionGranted() {
                        runOnUiThread(() -> {
                            startGpsIfNeeded();
                            ensureRegionLocating();
                        });
                    }

                    @Override
                    public void onPermissionDenied() {
                        runOnUiThread(() -> {
                            if (tvNoData != null) {
                                tvNoData.setText("需要位置权限才能获取附近站点~");
                            }
                        });
                    }
                });
            }
            TTSUtils.getInstance(this);
            setupVoicepackDownload();
            tv_search_line.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(MainActivity.this, BusRouteSearchActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e("MainActivity", "跳转搜索页面失败", e);
                    Toast.makeText(MainActivity.this, "页面跳转失败", Toast.LENGTH_SHORT).show();
                }
            });
            autoScrollTextView.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(MainActivity.this, NoticeListActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e("MainActivity", "跳转公告列表失败", e);
                    Toast.makeText(MainActivity.this, "页面跳转失败", Toast.LENGTH_SHORT).show();
                }
            });
            loadAnnouncements();
            loadRemoteConfig();
            // 临时验证：搜索 35 路并打印日志（确认搜索 SDK 可用后删除）
            testSearchBusLine();
        } catch (Exception e) {
            Log.e("MainActivity", "初始化失败", e);
            Toast.makeText(this, "应用初始化失败", Toast.LENGTH_LONG).show();
        }
    }

    /** 临时验证：搜索 35 路并打印线路名/方向坐标/站点坐标，确认搜索 SDK 可用（验证后可删除本方法及其调用） */
    private void testSearchBusLine() throws AMapException {
        try {
            ServiceSettings.updatePrivacyShow(this, true, true);
            ServiceSettings.updatePrivacyAgree(this, true);
        } catch (Throwable t) {
            Log.e("BusLineTest", "搜索隐私协议设置失败", t);
        }
        BusRegion sel = regionManager.getSelectedRegion();
        String searchCity = (sel != null && sel.adCode != null && !sel.adCode.isEmpty()) ? sel.adCode : "绍兴市";
        Log.i("BusLineTest", "使用 adCode 查询: " + searchCity);
        final String city = searchCity;
        BusLineQuery query = new BusLineQuery("10路", BusLineQuery.SearchType.BY_LINE_NAME, city);
        query.setPageSize(10);
        // 高德搜索 SDK 自 5.2.1 起页码从 1 开始（当前 9.5.0），传 0 会返回空结果
        query.setPageNumber(1);
        BusLineSearch search = new BusLineSearch(this, query);
        search.setOnBusLineSearchListener(new BusLineSearch.OnBusLineSearchListener() {
            @Override
            public void onBusLineSearched(BusLineResult result, int rCode) {
                if (rCode != 1000 || result == null) {
                    Log.w("BusLineTest", "公交线路搜索失败 rCode=" + rCode + " city=" + city);
                    return;
                }
                List<BusLineItem> lines = result.getBusLines();
                if (lines == null || lines.isEmpty()) {
                    Log.w("BusLineTest", "未搜索到 35 路（city=" + city + "）");
                    return;
                }
                Log.i("BusLineTest", "搜索到 " + lines.size() + " 条 35 路，city=" + city);
                // 按线路名搜索只返回概要(站点数为0)，需用 lineId 二次查询拿完整站点/坐标
                for (BusLineItem line : lines) {
                    final String lineId = line.getBusLineId();
                    Log.i("BusLineTest", "概要: " + line.getBusLineName()
                            + " 公司=" + line.getBusCompany()
                            + " 方向=" + line.getOriginatingStation() + " -> " + line.getTerminalStation()
                            + " city=" + city
                            + " lineId=" + lineId);
                    BusLineQuery detailQuery = new BusLineQuery(lineId, BusLineQuery.SearchType.BY_LINE_ID, city);
                    detailQuery.setPageSize(10);
                    // 高德搜索 SDK 自 5.2.1 起页码从 1 开始（当前 9.5.0），传 0 会返回空结果
                    detailQuery.setPageNumber(1);
                    // extensions="all" 才会返回站点列表（getBusStations），默认 base 只返回基础数据
                    detailQuery.setExtensions("all");
                    BusLineSearch detailSearch = null;
                    try {
                        detailSearch = new BusLineSearch(MainActivity.this, detailQuery);
                    } catch (AMapException e) {
                        throw new RuntimeException(e);
                    }
                    detailSearch.setOnBusLineSearchListener(new BusLineSearch.OnBusLineSearchListener() {
                        @Override
                        public void onBusLineSearched(BusLineResult detailResult, int dRCode) {
                            if (dRCode != 1000 || detailResult == null) {
                                Log.w("BusLineTest", "线路详情查询失败 rCode=" + dRCode + " lineId=" + lineId);
                                return;
                            }
                            List<BusLineItem> detailLines = detailResult.getBusLines();
                            if (detailLines == null || detailLines.isEmpty()) {
                                Log.w("BusLineTest", "线路详情为空 lineId=" + lineId);
                                return;
                            }
                            BusLineItem d = detailLines.get(0);
                            Log.i("BusLineTest", "详情 " + d.getBusLineName()
                                    + " 距离=" + d.getDistance() + "km"
                                    + " 首班=" + d.getFirstBusTime() + " 末班=" + d.getLastBusTime()
                                    + " 站点数=" + (d.getBusStations() == null ? 0 : d.getBusStations().size()));
                            List<BusStationItem> stations = d.getBusStations();
                            if (stations != null) {
                                for (int i = 0; i < stations.size(); i++) {
                                    BusStationItem st = stations.get(i);
                                    LatLonPoint p = st.getLatLonPoint();
                                    String coord = p == null ? "null" : (p.getLongitude() + "," + p.getLatitude());
                                    Log.i("BusLineTest", "  站点[" + i + "] " + st.getBusStationName() + " 坐标=" + coord);
                                }
                            }
                            List<LatLonPoint> dirs = d.getDirectionsCoordinates();
                            if (dirs != null) {
                                for (int i = 0; i < dirs.size(); i++) {
                                    LatLonPoint p = dirs.get(i);
                                    Log.i("BusLineTest", "  方向点[" + i + "] " + p.getLongitude() + "," + p.getLatitude());
                                }
                            }
                        }
                    });
                    detailSearch.searchBusLineAsyn();
                }
            }
        });
        search.searchBusLineAsyn();
        Log.i("BusLineTest", "已发起 35 路搜索，city=" + city);
    }

    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (!locationObtained) {
                locationObtained = true;
                Coordinate wgsCoord = new Coordinate(location.getLatitude(), location.getLongitude());
                Coordinate gcjCoord = GeometryUtils.wgs2gcj(wgsCoord);
                currentLatitude = gcjCoord.getLat();
                currentLongitude = gcjCoord.getLng();
                Log.d("MainActivity", String.format("GPS定位成功: WGS(%.6f,%.6f) -> GCJ(%.6f,%.6f)",
                        location.getLatitude(), location.getLongitude(), currentLatitude, currentLongitude));

                GpsWarmingUp.removeListener(this);
                GpsWarmingUp.stopWarmingUp();
                runOnUiThread(() -> loadNearbyStations());
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 反注册语音包配置状态监听，避免 Activity 泄漏
        if (voicepackConfigListener != null) {
            VoicePackManager.getInstance(this).removeConfigStateListener(voicepackConfigListener);
            voicepackConfigListener = null;
        }
        GpsWarmingUp.removeListener(gpsListener);
        // 取消地区提示兜底计时
        regionFallbackHandler.removeCallbacks(regionFallback);
        // 关闭地区定位
        if (regionLocator != null) {
            regionLocator.stop();
            regionLocator = null;
        }
        // 关闭 WebSocket 连接
        if (webSocketManager != null) {
            webSocketManager.close();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (locationObtained) {
            runOnUiThread(() -> loadNearbyStations());
        } else if (PermissionUtils.hasLocationPermission(this)) {
            startGpsIfNeeded();
        }
        // 回到首页时刷新语音包状态（可能在线路详情页下载过）
        if (!voicepackDownloading) {
            refreshVoicepackStatus();
        }
        // 回到首页确保地区定位在运行（用于检测城市变化）
        ensureRegionLocating();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionUtils.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void startGpsIfNeeded() {
        if (!GpsWarmingUp.isWarmingUp()) {
            try {
                GpsWarmingUp.startWarmingUp(this);
                GpsWarmingUp.addListener(gpsListener);
            } catch (Exception e) {
                Log.e("MainActivity", "GPS初始化失败", e);
            }
        }
    }

    // ==================== 地区定位 / 选择 ====================

    /**
     * 初始化左上角地区胶囊（展示已选地区 + 作为人工搜索入口）。
     */
    private void setupRegionUi() {
        regionManager = new RegionManager(this);
        View regionView = findViewById(R.id.ll_region);
        tvRegion = findViewById(R.id.tv_region);
        if (regionView != null) {
            regionView.setOnClickListener(v -> openRegionSearch());
            // 长按：重置地区提示，下次定位自动重新弹窗（置强制提示标志，不立即弹窗）
            regionView.setOnLongClickListener(v -> {
                regionManager.setPromptedAdCode("");
                regionManager.setManualMode(false);
                regionManager.setForcePromptNext(true);
                Toast.makeText(MainActivity.this, "已重置地区提示，下次将重新询问", Toast.LENGTH_SHORT).show();
                return true;
            });
        }
        updateRegionChip(regionManager.getSelectedRegion());
    }

    /**
     * 启动地区定位（幂等）。仅在尚未启动且已授予定位权限时执行。
     */
    private void ensureRegionLocating() {
        if (regionLocatorStarted) return;
        if (!PermissionUtils.hasLocationPermission(this)) return;
        regionLocatorStarted = true;
        regionLocator = new RegionLocator();
        regionLocator.start(this, new RegionLocator.Callback() {
            @Override
            public void onRegionResolved(BusRegion region) {
                runOnUiThread(() -> handleLocatedRegion(region));
            }

            @Override
            public void onError(int code, String msg) {
                Log.w("MainActivity", "地区定位失败: " + msg);
                // 定位失败且尚未选择地区：立即给出手动选择提示（需求 1 的兜底）
                if (!regionPromptShown && !regionManager.isManualMode()
                        && regionManager.getSelectedRegion() == null) {
                    runOnUiThread(() -> showRegionFallbackDialog());
                }
            }
        });
        // 12s 内仍未解析到地区，也给出提示，避免首屏永远无提示
        regionFallbackHandler.removeCallbacks(regionFallback);
        regionFallbackHandler.postDelayed(regionFallback, 12_000);
    }

    /**
     * 处理一次定位到的地区：仅当「真实定位地区」与上次提示过的不同才弹窗。
     * - 已选地区与定位一致：不提示，并记录当前真实定位地区；
     * - 真实定位地区与上次提示过的相同（用户已自行选过其它地区也不打扰）：不重复弹窗；
     * - 真实地区发生变化：弹窗询问。
     * 不再按 manualMode 永久屏蔽，避免「手动选择后每次进入都弹窗」或「换城市却不提示」。
     */
    private void handleLocatedRegion(BusRegion located) {
        // 记录真实地区，并在已连接 WS 时实时上报（真实地区 + 选择地区）
        currentRealRegion = located;
        sendWsRegionInfo();

        BusRegion selected = regionManager.getSelectedRegion();

        // 长按重置后置位：下次定位检测强制重新弹窗（跨重启生效）
        if (regionManager.isForcePromptNext()) {
            regionManager.setForcePromptNext(false);
            regionManager.setPromptedAdCode(located.adCode);
            showRegionConfirmDialog(located);
            updateRegionChip(selected);
            return;
        }

        // 已选地区与定位一致：无需提示，记录当前真实定位地区
        if (selected != null && selected.adCode.equals(located.adCode)) {
            regionManager.setPromptedAdCode(located.adCode);
            updateRegionChip(selected);
            return;
        }

        // 真实定位地区与上次提示过的相同：不重复弹窗（无论是否手动模式、用户是否另选了地区）
        if (located.adCode.equals(regionManager.getPromptedAdCode())) {
            updateRegionChip(selected);
            return;
        }

        // 真实地区发生变化：弹窗询问，并记录本次已提示（防止连续/重复弹窗）
        regionManager.setPromptedAdCode(located.adCode);
        showRegionConfirmDialog(located);
        updateRegionChip(selected);
    }

    /**
     * 弹窗：询问用户是否使用当前定位地区作为公交查询地区（需求 1、2）。
     * 采用现代化居中弹窗（icon + 标题 + 正文 + 两个按钮）。
     */
    private void showRegionConfirmDialog(BusRegion r) {
        if (isFinishing() || isDestroyed()) return;
        regionPromptShown = true;
        regionFallbackHandler.removeCallbacks(regionFallback);
        String msg = "检测到您位于 " + r.toShortString()
                + "，是否使用「" + r.regionName + "」作为公交查询地区？";
        showModernRegionDialog("定位到新地区", msg,
                "使用该地区", v -> {
                    regionManager.setSelectedRegion(r);
                    regionManager.setPromptedAdCode(r.adCode);
                    regionManager.setManualMode(false);
                    updateRegionChip(r);
                    onRegionConfirmed(r);
                },
                "手动选择", v -> {
                    // 需求 3：取消后切换为人工搜索模式，不再自动弹窗
                    regionManager.setManualMode(true);
                    regionManager.setPromptedAdCode(r.adCode);
                    updateRegionChip(regionManager.getSelectedRegion());
                    Toast.makeText(MainActivity.this, "已切换为手动选择地区", Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * 兜底提示：未能自动定位到所在地区时，引导用户手动选择（需求 1 的兜底）。
     */
    private void showRegionFallbackDialog() {
        if (isFinishing() || isDestroyed()) return;
        regionPromptShown = true;
        regionFallbackHandler.removeCallbacks(regionFallback);
        showModernRegionDialog("选择公交查询地区",
                "未能自动定位到所在地区，您可以手动选择，或稍后重试。",
                "手动选择", v -> openRegionSearch(),
                "稍后", v -> {});
    }

    /**
     * 现代化「居中」弹窗：顶部定位图标 + 标题 + 正文 + 主/次按钮（非底部弹窗）。
     */
    private void showModernRegionDialog(String title, String msg,
            String positiveText, View.OnClickListener positiveAction,
            String negativeText, View.OnClickListener negativeAction) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_region_confirm, null);
        ((TextView) view.findViewById(R.id.tv_region_title)).setText(title);
        ((TextView) view.findViewById(R.id.tv_region_msg)).setText(msg);
        MaterialButton btnPositive = view.findViewById(R.id.btn_positive);
        MaterialButton btnNegative = view.findViewById(R.id.btn_negative);
        btnPositive.setText(positiveText);
        btnNegative.setText(negativeText);
        btnPositive.setOnClickListener(v -> {
            if (positiveAction != null) positiveAction.onClick(v);
            dialog.dismiss();
        });
        btnNegative.setOnClickListener(v -> {
            if (negativeAction != null) negativeAction.onClick(v);
            dialog.dismiss();
        });
        dialog.setContentView(view);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.86);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    /**
     * 打开人工搜索地区页面（需求 3：左上角人工搜索入口）。
     */
    private void openRegionSearch() {
        try {
            regionSearchLauncher.launch(new android.content.Intent(this, RegionSearchActivity.class));
        } catch (Exception e) {
            Log.e("MainActivity", "打开地区搜索失败", e);
            Toast.makeText(this, "打开搜索失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 刷新左上角地区胶囊文字。
     */
    private void updateRegionChip(BusRegion selected) {
        if (tvRegion == null) return;
        if (selected != null) {
            tvRegion.setText(selected.toShortString());
        } else {
            tvRegion.setText("选择地区");
        }
        applyRegionModules(selected);
    }

    /** 非诸暨市（adCode 非 330681 开头）：首页隐藏 语音包下载 / 通知公告 / 附近公交 三个模块 */
    private void applyRegionModules(BusRegion region) {
        boolean zhuji = region != null && region.adCode != null && region.adCode.startsWith("330681");
        int v = zhuji ? android.view.View.VISIBLE : android.view.View.GONE;
        if (llVoicepackDownload != null) llVoicepackDownload.setVisibility(v);
        if (llNoticeBoard != null) llNoticeBoard.setVisibility(v);
        if (llNearbyBus != null) llNearbyBus.setVisibility(v);
    }

    /**
     * 地区确认后的回调（需求 5 的扩展点）。
     * 后续可根据 {@link BusRegion#regionName}（区县级名称，如"诸暨市"）
     * 调用高德 BusLineSearch 搜索该地区对应公交；
     * 务必使用区县级名称而非地级市名，才能正确命中诸暨/上虞等区县的公交公司线路。
     */
    private void onRegionConfirmed(BusRegion region) {
        Log.i("MainActivity", "已确认公交查询地区：" + region.toShortString()
                + " adCode=" + region.adCode + " 检索名=" + region.regionName);
        Toast.makeText(this, "公交查询地区：" + region.toShortString(), Toast.LENGTH_SHORT).show();
    }

    private void loadNearbyStations() {
        try {
            client.getNearbyStations(currentLongitude, currentLatitude, "2", 3, 5, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.StationLineAroundResponse response) {
                    try {
                        if (response == null || response.data == null) {
                            Log.w("MainActivity", "附近站点数据为空");
                            return;
                        }
                        List<StationItem> stations = new ArrayList<>();
                        for (int i = 0; i < response.data.size(); i++) {
                            BusApiClient.NearbyStationInfo stationInfo = response.data.get(i);
                            if (stationInfo == null) continue;

                            List<RouteItem> routes1 = new ArrayList<>();
                            List<BusApiClient.DistanceData> distanceDataList = stationInfo.distanceData;
                            if (distanceDataList != null) {
                                for (int j = 0; j < distanceDataList.size(); j++) {
                                    BusApiClient.DistanceData distanceData = distanceDataList.get(j);
                                    if (distanceData != null) {
                                        String distanceStr = (distanceData.nextNumber == -1 || distanceData.distance == -1)
                                                ? "" : "距离" + distanceData.nextNumber + "站/" + DistanceUtils.formatDistance(distanceData.distance);
                                        int arrivalTimeInt = (distanceData.arrivalTime <= 0) ? 0 : distanceData.arrivalTime;
                                        routes1.add(new RouteItem(
                                                distanceData.lineName,
                                                distanceStr,
                                                distanceData.startStation,
                                                distanceData.endStation,
                                                arrivalTimeInt
                                        ));
                                    }
                                }
                            }
                            stations.add(new StationItem(
                                    stationInfo.stationName,
                                    DistanceUtils.formatDistance(stationInfo.distance),
                                    routes1
                            ));
                        }

                        runOnUiThread(() -> {
                            try {
                                StationRouteAdapter adapter = new StationRouteAdapter(stations);
                                recyclerView.setLayoutManager(new LinearLayoutManager(MainActivity.this));
                                recyclerView.setAdapter(adapter);
                                recyclerView.addItemDecoration(new DividerItemDecoration(MainActivity.this, DividerItemDecoration.VERTICAL));
                                if (tvNoData != null) {
                                    tvNoData.setVisibility(View.GONE);
                                }

                                adapter.setOnItemClickListener(new StationRouteAdapter.OnItemClickListener() {
                                    @Override
                                    public void onStationClick(StationItem station) {
                                        try {
                                            showStationDetailsDialog(station.getStationName());
                                        } catch (Exception e) {
                                            Log.e("MainActivity", "显示站点详情失败", e);
                                        }
                                    }

                                    @Override
                                    public void onRouteClick(RouteItem route) {
                                    }
                                });
                            } catch (Exception e) {
                                Log.e("MainActivity", "更新UI失败", e);
                            }
                        });
                    } catch (Exception e) {
                        Log.e("MainActivity", "处理站点数据失败", e);
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e("MainActivity", "获取附近站点失败: " + e.getMessage(), e);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "获取附近站点失败", Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Exception e) {
            Log.e("MainActivity", "加载附近站点异常", e);
            Toast.makeText(this, "加载附近站点失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void showStationDetailsDialog(String stationName) {
        StationDetailsFragment stationDetailsFragment = StationDetailsFragment.newInstance(stationName);
        stationDetailsFragment.show(getSupportFragmentManager(), "dialog_tag");
    }

    private void loadAnnouncements() {
        try {
            client.getBusAnnouncements(1, 6, new BusApiClient.ApiCallback<BusApiClient.BusAnnouncementResponse>() {
                @Override
                public void onSuccess(BusApiClient.BusAnnouncementResponse response) {
                    try {
                        if (response == null || !"200".equals(response.code) ||
                                response.data == null || response.data.isEmpty()) {
                            Log.w("-BusInfo-", "滚动公告请求失败-无数据");
                            return;
                        }

                        // 收集所有公告标题
                        List<String> announcements = new ArrayList<>();
                        for (BusApiClient.BusAnnouncement announcement : response.data) {
                            announcements.add(announcement.title);
                        }

                        // 使用自定义组件
                        runOnUiThread(() -> {
                            autoScrollTextView.setItems(announcements);
                        });

                    } catch (Exception e) {
                        Log.e("-BusInfo-", "处理公告数据失败", e);
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.w("-BusInfo-", "滚动公告请求失败-异常：" + e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e("MainActivity", "加载公告异常", e);
        }
    }

    @NonNull
    private TextView getTextView(BusApiClient.BusAnnouncement announcement) {
        TextView textView = new TextView(MainActivity.this);
        textView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        textView.setText(announcement.title);
        textView.setTextSize(15);
        textView.setTextColor(Color.parseColor("#000000"));
        textView.setGravity(Gravity.CENTER_VERTICAL);
        return textView;
    }

    // ==================== 语音包全量下载 ====================

    private void setupVoicepackDownload() {
        tvVoicepackDownload = findViewById(R.id.tv_voicepack_download);
        tvVoicepackStatus = findViewById(R.id.tv_voicepack_status);
        llVoicepackProgressRow = findViewById(R.id.ll_voicepack_progress_row);
        pbVoicepack = findViewById(R.id.pb_voicepack);
        tvVoicepackProgress = findViewById(R.id.tv_voicepack_progress);
        llVoicepackCleanup = findViewById(R.id.ll_voicepack_cleanup);
        tvVoicepackCleanupText = findViewById(R.id.tv_voicepack_cleanup_text);
        tvVoicepackCleanupClose = findViewById(R.id.tv_voicepack_cleanup_close);
        if (tvVoicepackDownload == null) return;
        tvVoicepackDownload.setOnClickListener(v -> {
            if (voicepackDownloading) return;
            confirmAndDownloadAllVoicepack();
        });
        // 关闭清理通知
        if (tvVoicepackCleanupClose != null) {
            tvVoicepackCleanupClose.setOnClickListener(v -> {
                if (llVoicepackCleanup != null) llVoicepackCleanup.setVisibility(View.GONE);
            });
        }
        // 首次进入即触发状态刷新（含配置读取状态展示）
        refreshVoicepackStatus();
    }

    /**
     * 刷新首页语音包状态文字，含配置读取状态。
     * 统一由 VoicePackManager 的配置状态机驱动：注册一次状态监听，
     * 收到 LOADING/READY/FAILED 即刷新 UI，不再本页面自行轮询/重试。
     * classifyMissing 涉及磁盘 md5 校验，放后台线程；UI 更新走 runOnUiThread。
     */
    private void refreshVoicepackStatus() {
        if (tvVoicepackStatus == null) return;
        VoicePackManager vpm = VoicePackManager.getInstance(this);
        // 注册统一状态监听（幂等）。注册后立即收到一次当前状态，之后每次状态变更都会刷新。
        ensureVoicepackConfigListener(vpm);
        // 主动触发一轮拉取（若尚未就绪）；状态机的重试/失败判定会在后续事件中驱动刷新
        vpm.ensureConfigLoading();
    }

    /**
     * 注册配置状态监听（幂等）。统一入口：所有状态（LOADING/READY/FAILED）都在此刷新，
     * 覆盖"源遍历中不判失败、成功后即时刷新、重试耗尽才判失败"的全部逻辑。
     */
    private void ensureVoicepackConfigListener(VoicePackManager vpm) {
        if (voicepackConfigListener != null) return;
        voicepackConfigListener = state -> {
            // 回调在后台线程（executor/scheduler/注册线程），切回主线程
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                switch (state) {
                    case LOADING:
                        // 源遍历中/重试等待中：绝不显示失败，一直保持"加载中"
                        if (tvVoicepackStatus != null) {
                            tvVoicepackStatus.setText("🔊 语音包配置加载中...");
                        }
                        break;
                    case READY:
                        // 配置就绪：反注册一次性监听（统计完成后不再需要），走正常统计
                        unregisterVoicepackConfigListener(vpm);
                        renderVoicepackReadyStats(vpm);
                        break;
                    case FAILED:
                        // 所有源遍历完且重试已耗尽：显示失败
                        if (tvVoicepackStatus != null) {
                            tvVoicepackStatus.setText("⚠️ 语音包配置加载失败");
                        }
                        break;
                }
            });
        };
        vpm.addConfigStateListener(voicepackConfigListener);
    }

    /** 反注册配置状态监听 */
    private void unregisterVoicepackConfigListener(VoicePackManager vpm) {
        if (voicepackConfigListener != null) {
            vpm.removeConfigStateListener(voicepackConfigListener);
            voicepackConfigListener = null;
        }
    }

    /** 配置就绪后渲染语音包统计（后台线程做磁盘 md5 校验） */
    private void renderVoicepackReadyStats(VoicePackManager vpm) {
        if (tvVoicepackStatus == null) return;
        tvVoicepackStatus.setText("🔊 语音包：统计中...");
        final List<String> allNames = vpm.getAllStationNames();
        new Thread(() -> {
            VoicePackManager.MissingStat stat = vpm.classifyMissing(allNames);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (stat == null) {
                    tvVoicepackStatus.setText("⚠️ 语音包配置未就绪");
                    return;
                }
                int total = allNames.size();
                int missing = stat.notInRemote.size() + stat.notDownloaded.size() + stat.needUpdate.size();
                int ready = total - stat.notInRemote.size(); // 远程可下发的站点数
                int downloaded = ready - stat.notDownloaded.size() - stat.needUpdate.size();
                if (downloaded < 0) downloaded = 0;
                if (missing == 0) {
                    tvVoicepackStatus.setText("✅ 语音包已就绪（" + total + " 个站点）");
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append("🔊 语音包：已就绪 ").append(downloaded).append("/").append(ready);
                    sb.append("，缺少 ").append(missing).append("\n");
                    sb.append("  · 未下载 ").append(stat.notDownloaded.size());
                    sb.append(" · 更新 ").append(stat.needUpdate.size());
                    sb.append(" · 远程不存在 ").append(stat.notInRemote.size());
                    tvVoicepackStatus.setText(sb.toString());
                }
                // 检查并展示自动清理结果（仅一次）
                showCleanupNoticeIfAny(vpm);
            });
        }).start();
    }

    /**
     * 查询 VoicePackManager 最近一次自动清理结果，若发生过清理且本页面未展示过，
     * 则在首页显示通知。同一清理事件只提示一次（基于时间戳比较）。
     */
    private void showCleanupNoticeIfAny(VoicePackManager vpm) {
        if (llVoicepackCleanup == null || tvVoicepackCleanupText == null) return;
        long[] result = vpm.getLastCleanupResult();
        int count = (int) result[0];
        long time = result[1];
        // 未发生清理 或 已展示过该清理事件 → 隐藏
        if (count <= 0 || time <= lastDisplayedCleanupTime) {
            llVoicepackCleanup.setVisibility(View.GONE);
            return;
        }
        tvVoicepackCleanupText.setText("🧹 已自动清理 " + count + " 个过时语音包文件");
        llVoicepackCleanup.setVisibility(View.VISIBLE);
        lastDisplayedCleanupTime = time;
        // 5 秒后自动隐藏（不重置 lastDisplayedCleanupTime，避免重复提示）
        llVoicepackCleanup.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed() && llVoicepackCleanup != null) {
                llVoicepackCleanup.setVisibility(View.GONE);
            }
        }, 5000);
    }

    private void confirmAndDownloadAllVoicepack() {
        VoicePackManager vpm = VoicePackManager.getInstance(this);
        if (!vpm.isConfigLoaded()) {
            Toast.makeText(this, "语音包配置加载中，请稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        int totalStations = vpm.getAllStationNames().size();
        if (totalStations == 0) {
            Toast.makeText(this, "语音包配置加载中，请稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean isMobile = isMobileNetwork();
        String msg = isMobile
                ? "全量语音包约 358MB，建议在 WiFi 下下载，是否继续？"
                : "将下载全量语音包（约 358MB，共 " + totalStations + " 个站点），是否继续？";
        new AlertDialog.Builder(this)
                .setTitle("下载语音包")
                .setMessage(msg)
                .setPositiveButton("下载", (d, w) -> startDownloadAllVoicepack())
                .setNegativeButton("取消", null)
                .show();
    }

    private void startDownloadAllVoicepack() {
        if (tvVoicepackDownload == null || pbVoicepack == null || tvVoicepackProgress == null) return;
        voicepackDownloading = true;
        tvVoicepackDownload.setEnabled(false);
        tvVoicepackDownload.setTextColor(0xFF999999);
        if (llVoicepackProgressRow != null) llVoicepackProgressRow.setVisibility(View.VISIBLE);
        pbVoicepack.setVisibility(View.VISIBLE);
        tvVoicepackProgress.setVisibility(View.VISIBLE);
        pbVoicepack.setProgress(0);
        tvVoicepackProgress.setText("0/0");
        if (tvVoicepackStatus != null) tvVoicepackStatus.setText("📥 语音包下载中...");

        VoicePackManager.getInstance(this).downloadAllStations(new VoicePackManager.ProgressCallback() {
            @Override
            public void onProgress(int done, int total) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    int p = total > 0 ? done * 100 / total : 0;
                    pbVoicepack.setProgress(p);
                    tvVoicepackProgress.setText(done + "/" + total);
                    if (tvVoicepackStatus != null) {
                        tvVoicepackStatus.setText("📥 语音包下载中... " + p + "%");
                    }
                });
            }

            @Override
            public void onComplete(boolean success) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    voicepackDownloading = false;
                    if (llVoicepackProgressRow != null) llVoicepackProgressRow.setVisibility(View.GONE);
                    pbVoicepack.setVisibility(View.GONE);
                    tvVoicepackProgress.setVisibility(View.GONE);
                    tvVoicepackDownload.setEnabled(true);
                    tvVoicepackDownload.setTextColor(0xFF0D8DFB);
                    Toast.makeText(MainActivity.this,
                            success ? "语音包下载完成" : "语音包下载结束（部分可能失败）",
                            Toast.LENGTH_SHORT).show();
                    // 下载完成后刷新状态
                    refreshVoicepackStatus();
                });
            }
        });
    }

    /** 判断当前是否移动网络 */
    private boolean isMobileNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected()
                    && info.getType() == ConnectivityManager.TYPE_MOBILE;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 远程配置（更新/公告） ====================

    /**
     * 远程配置信息
     */
    private static class RemoteConfig {
        int remoteVersionCode;
        String remoteVersionName = "";
        boolean hasNotice;
        boolean hasUpdate;
        String notice = "";
        String updateLog = "";
        String githubAddSpeed = ""; // GitHub 加速地址
        String wsServerAddress = ""; // WebSocket 服务器地址

        boolean isNewVersion(int localVersionCode) {
            return remoteVersionCode > localVersionCode;
        }
    }

    /**
     * 拉取远程 build.gradle，解析出 versionCode/versionName 和 remote 字段
     */
    private void loadRemoteConfig() {
        new Thread(() -> {
            try {
                OkHttpClient http = new OkHttpClient();
                Request req = new Request.Builder().url(REMOTE_CONFIG_URL).build();
                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        Log.w(TAG, "拉取远程配置失败：http " + resp.code());
                        // 失败也解锁语音包配置拉取，避免其永久依赖本处成功
                        VoicePackManager.getInstance(MainActivity.this).unlockConfigFetch();
                        return;
                    }
                    String text = resp.body().string();
                    RemoteConfig cfg = parseRemoteConfig(text);
                    if (cfg == null) {
                        Log.w(TAG, "远程配置解析失败");
                        // 同上，失败路径解锁，避免语音包配置获取被阻塞
                        VoicePackManager.getInstance(MainActivity.this).unlockConfigFetch();
                        return;
                    }
                    remoteConfig = cfg;
                    runOnUiThread(() -> applyRemoteConfig());
                }
            } catch (IOException e) {
                Log.w(TAG, "拉取远程配置异常：" + e.getMessage());
                // 同上，异常路径解锁
                VoicePackManager.getInstance(MainActivity.this).unlockConfigFetch();
            }
        }).start();
    }

    /**
     * 从 build.gradle 文本中解析 versionCode / versionName / remote JSON
     */
    private RemoteConfig parseRemoteConfig(String gradleText) {
        try {
            RemoteConfig cfg = new RemoteConfig();

            Matcher vcMatcher = Pattern.compile("versionCode\\s+(\\d+)").matcher(gradleText);
            if (vcMatcher.find()) {
                cfg.remoteVersionCode = Integer.parseInt(vcMatcher.group(1));
            }

            Matcher vnMatcher = Pattern.compile("versionName\\s+\"([^\"]*)\"").matcher(gradleText);
            if (vnMatcher.find()) {
                cfg.remoteVersionName = vnMatcher.group(1);
            }

            // 解析 githubAddSpeed 加速地址
            // 格式：//githubAddSpeed=https://gh-proxy.com
            Matcher speedMatcher = Pattern.compile("//githubAddSpeed\\s*=\\s*`?([^`\\n]+)`?").matcher(gradleText);
            if (speedMatcher.find()) {
                cfg.githubAddSpeed = speedMatcher.group(1).trim();
            }

            // 解析 wsServerAddress
            // 格式：//wsServerAddress=http://zhujibus.android.360967.xyz
            Matcher wsMatcher = Pattern.compile("//wsServerAddress\\s*=\\s*`?([^`\\n]+)`?").matcher(gradleText);
            if (wsMatcher.find()) {
                cfg.wsServerAddress = wsMatcher.group(1).trim();
                Log.d(TAG, "解析到 wsServerAddress: " + cfg.wsServerAddress);
            } else {
                Log.w(TAG, "未找到 wsServerAddress 配置");
            }

            // remote={...} 注释里的 JSON（注意：示例中 ture 是笔误，宽松解析）
            Matcher remoteMatcher = Pattern.compile("remote\\s*=\\s*(\\{[\\s\\S]*?\\})").matcher(gradleText);
            if (remoteMatcher.find()) {
                String json = remoteMatcher.group(1)
                        .replace("ture", "true")
                        .replace("Ture", "true")
                        .replace("TURE", "true")
                        .replace("flase", "false")
                        .replace("Flase", "false")
                        .replace("FLASE", "false");
                ObjectMapper mapper = new ObjectMapper();
                JsonNode node = mapper.readTree(json);
                cfg.hasNotice = node.path("has_notice").asBoolean(false);
                cfg.hasUpdate = node.path("has_update").asBoolean(false);
                cfg.notice = node.path("notice").asText("");
                cfg.updateLog = node.path("update_log").asText("");
            }
            return cfg;
        } catch (Exception e) {
            Log.e(TAG, "解析远程配置异常", e);
            return null;
        }
    }

    /**
     * 根据远程配置刷新顶部两行
     */
    private void applyRemoteConfig() {
        if (remoteConfig == null
                || isFinishing() || isDestroyed()) return;

        // 推送 githubAddSpeed 到语音包管理器（作为降级链的配置源）
        VoicePackManager.getInstance(this).setConfigAccelUrl(remoteConfig.githubAddSpeed);

        int localVersionCode = getLocalVersionCode();
        boolean isRemoteNewer = remoteConfig.remoteVersionCode > localVersionCode;

        // ===== 更新公告行 =====
        // 规则：
        //   has_update == false              → 直接忽略，不显示
        //   has_update == true  且 远程版本号 > 本地版本号  → 显示
        //   has_update == true  但 本地版本号 >= 远程版本号 → 不显示
        boolean showUpdate = remoteConfig.hasUpdate && isRemoteNewer;
        if (showUpdate) {
            String text = "发现新版本 v" + remoteConfig.remoteVersionName
                    + " (" + remoteConfig.remoteVersionCode + ") 点击查看";
            if (hstvUpdateNotice != null) hstvUpdateNotice.setText(text);
            if (llUpdateNotice != null) {
                llUpdateNotice.setVisibility(View.VISIBLE);
                hstvUpdateNotice.startScroll();
                llUpdateNotice.setOnClickListener(v -> {
                    if (!isFinishing() && !isDestroyed()) showUpdateDialog();
                });
            }
        } else {
            if (llUpdateNotice != null) llUpdateNotice.setVisibility(View.GONE);
        }

        // ===== 公告行 =====
        // 规则：has_notice == true 且 公告文本非空 → 显示；否则不显示
        if (remoteConfig.hasNotice && !TextUtils.isEmpty(remoteConfig.notice)) {
            if (hstvNotice != null) hstvNotice.setText(remoteConfig.notice);
            if (llNotice != null) {
                llNotice.setVisibility(View.VISIBLE);
                hstvNotice.startScroll();
            }
        } else {
            if (llNotice != null) llNotice.setVisibility(View.GONE);
        }

        // ===== WebSocket 连接 =====
        // 如果配置了 wsServerAddress，则建立 WebSocket 连接
        if (!TextUtils.isEmpty(remoteConfig.wsServerAddress)) {
            wsServerAddress = remoteConfig.wsServerAddress;
            Log.d(TAG, "开始建立 WebSocket 连接，地址: " + wsServerAddress);
            connectWebSocket(wsServerAddress);
        } else {
            Log.w(TAG, "未配置 wsServerAddress，跳过 WebSocket 连接");
        }
    }

    // ==================== WebSocket 连接逻辑 ====================

    /**
     * 建立 WebSocket 连接（先检测重定向）
     * @param httpAddress HTTP/HTTPS 地址
     */
    private void connectWebSocket(String httpAddress) {
        if (TextUtils.isEmpty(httpAddress)) {
            Log.w(TAG, "WebSocket 地址为空，跳过连接");
            return;
        }

        // 先检测重定向
        new Thread(() -> {
            try {
                OkHttpClient httpClient = new OkHttpClient.Builder()
                        .followRedirects(false)  // 不自动跟随重定向，手动检测
                        .build();

                Request request = new Request.Builder()
                        .url(httpAddress)
                        .head()  // HEAD 请求获取响应头
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    int code = response.code();
                    String finalAddress = httpAddress;

                    // 检测是否重定向 (301, 302, 303, 307, 308)
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = response.header("Location");
                        if (!TextUtils.isEmpty(location)) {
                            // 处理相对路径
                            if (location.startsWith("/")) {
                                Uri uri = Uri.parse(httpAddress);
                                location = uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort() + location;
                            }
                            finalAddress = location;
                            Log.d(TAG, "检测到重定向: " + httpAddress + " -> " + finalAddress);
                        }
                    } else if (code == 200) {
                        Log.d(TAG, "无重定向，使用原地址: " + httpAddress);
                    } else {
                        Log.w(TAG, "HTTP 请求返回非预期状态码: " + code + "，尝试使用原地址");
                    }

                    // 创建 WebSocketManager（内部自动处理 http→ws 转换）
                    String finalWsAddress = finalAddress;
                    runOnUiThread(() -> {
                        if (webSocketManager != null) {
                            webSocketManager.close();
                        }
                        webSocketManager = new WebSocketManager(MainActivity.this, finalWsAddress);
                        webSocketManager.setOnWebSocketListener(new WebSocketManager.OnWebSocketListener() {
                            @Override
                            public void onConnected() {
                                Log.d(TAG, "WebSocket 连接成功回调");
                                updateWebSocketStatus(true, "在线");
                                // 连接建立后立即上报地区（真实地区 + 选择地区）
                                sendWsRegionInfo();
                            }

                            @Override
                            public void onDisconnected() {
                                Log.d(TAG, "WebSocket 断开连接回调");
                                updateWebSocketStatus(false, "离线");
                            }

                            @Override
                            public void onDataReceived(String data) {
                                Log.d(TAG, "收到服务端数据: " + data);
                                // TODO: 处理服务端下发的数据
                            }

                            @Override
                            public void onEventReceived(String event) {
                                Log.d(TAG, "收到服务端事件: " + event);
                                // TODO: 处理服务端事件
                            }

                            @Override
                            public void onError(String error) {
                                Log.e(TAG, "WebSocket 错误: " + error);
                                updateWebSocketStatus(false, "错误");
                            }

                            @Override
                            public void onPongReceived() {
                                // 心跳回复，可选：更新状态为"在线"（如果是重连场景）
                                updateWebSocketStatus(true, "在线");
                            }

                            @Override
                            public void onReconnecting(int attempt, long delayMs) {
                                Log.d(TAG, "第 " + attempt + " 次重连，延迟 " + delayMs + "ms");
                                updateWebSocketStatus(false, "重连中 " + attempt);
                            }

                            @Override
                            public void onOnlineCountReceived(int count) {
                                // ⭐ 收到在线人数广播，更新 UI
                                Log.d(TAG, "在线人数: " + count);
                                runOnUiThread(() -> updateWebSocketOnlineCount(count));
                            }
                        });
                        webSocketManager.connect();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "检测重定向失败: " + e.getMessage(), e);
                // 失败时直接尝试连接原地址
                runOnUiThread(() -> {
                    if (webSocketManager != null) {
                        webSocketManager.close();
                    }
                    webSocketManager = new WebSocketManager(MainActivity.this, httpAddress);
                    webSocketManager.connect();
                });
            }
        }).start();
    }

    // ==================== WebSocket 状态 UI 更新 ====================

    private void updateWebSocketStatus(boolean isConnected, String statusText) {
        runOnUiThread(() -> {
            View dot = findViewById(R.id.view_status_dot);
            TextView tvStatus = findViewById(R.id.tv_ws_status);
            LinearLayout llStatus = findViewById(R.id.ll_ws_status);

            if (dot == null || tvStatus == null) return;

            if (isConnected) {
                dot.setBackgroundResource(R.drawable.status_dot_online);
                // ⭐ 已连接：显示"在线 N人使用中"（如果还没有人数数据，先显示"在线"）
                if (currentOnlineCount >= 0) {
                    tvStatus.setText(currentOnlineCount + " 人在线");
                } else {
                    tvStatus.setText("在线");
                }
                if (llStatus != null) {
                    //llStatus.setBackgroundResource(R.drawable.status_pill_bg_online);
                }
            } else {
                dot.setBackgroundResource(R.drawable.status_dot_offline);
                tvStatus.setText(statusText != null ? statusText : "离线");
                if (llStatus != null) {
                    llStatus.setBackgroundResource(R.drawable.status_pill_bg);
                }
            }
        });
    }

    /**
     * ⭐ 收到在线人数广播，更新显示
     */
    private void updateWebSocketOnlineCount(int count) {
        this.currentOnlineCount = count;
        // 强制刷新状态显示（保持"在线"状态）
        updateWebSocketStatus(true, null);
    }

    /**
     * 向 WS 服务端上报当前地区信息：定位出的「真实地区」+ 用户「选择地区」。
     * 若尚未定位到真实地区则真实地区字段为空，服务端可按选择地区处理。
     */
    private void sendWsRegionInfo() {
        if (webSocketManager == null || !webSocketManager.isConnected()) return;
        BusRegion selected = regionManager != null ? regionManager.getSelectedRegion() : null;
        webSocketManager.sendRegionInfo(currentRealRegion, selected);
    }

    private int getLocalVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 弹出更新对话框：显示更新日志，提供主下载链接和备用下载链接。
     * 使用自定义UI，美观的升级界面。
     */
    private void showUpdateDialog() {
        if (remoteConfig == null) return;
        if (isFinishing() || isDestroyed()) return;

        // 创建自定义对话框
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_update);
        dialog.setCancelable(true);

        // 设置对话框宽度
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.horizontalMargin = dpToPx(32);
            window.setAttributes(lp);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        // 绑定视图
        TextView tvVersionInfo = dialog.findViewById(R.id.tv_version_info);
        TextView tvUpdateLog = dialog.findViewById(R.id.tv_update_log);
        CardView cardPrimaryDownload = dialog.findViewById(R.id.card_primary_download);
        TextView tvPrimaryDownloadTitle = dialog.findViewById(R.id.tv_primary_download_title);
        TextView tvPrimaryDownloadDesc = dialog.findViewById(R.id.tv_primary_download_desc);
        TextView tvBackupDownloadDesc = dialog.findViewById(R.id.tv_backup_download_desc);
        CardView cardBackupDownload = dialog.findViewById(R.id.card_backup_download);
        ImageView ivLater = dialog.findViewById(R.id.iv_later);

        // 设置版本信息
        String currentVersionInfo = "当前版本：v" + getLocalVersionName()
                + " (" + getLocalVersionCode() + ")";
        String latestVersionInfo = "最新版本：v" + remoteConfig.remoteVersionName
                + " (" + remoteConfig.remoteVersionCode + ")";
        SpannableString versionInfo = new SpannableString(currentVersionInfo + "\n" + latestVersionInfo);
        int latestStart = currentVersionInfo.length() + 1;
        versionInfo.setSpan(new ForegroundColorSpan(Color.parseColor("#0d8dfb")), latestStart, versionInfo.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        versionInfo.setSpan(new StyleSpan(Typeface.BOLD), latestStart, versionInfo.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvVersionInfo.setText(versionInfo);

        // 设置更新日志
        String log = TextUtils.isEmpty(remoteConfig.updateLog)
                ? "（未提供更新日志）" : remoteConfig.updateLog;
        tvUpdateLog.setText(log);

        // 获取下载链接
        String primaryUrl = getSpeedApkUrl();
        String backupUrl = getRemoteApkUrl();

        // 设置主下载按钮
        if (primaryUrl != null && !primaryUrl.isEmpty()) {
            // 有加速链接
            tvPrimaryDownloadTitle.setText("主下载链接");
            tvPrimaryDownloadDesc.setText("推荐使用，下载更快");
            cardPrimaryDownload.setCardBackgroundColor(Color.parseColor("#4CAF50"));
            cardPrimaryDownload.setOnClickListener(v -> {
                copyUrlToClipboard(primaryUrl, "下载地址已复制，请在浏览器中打开下载");
                openInBrowser(primaryUrl);
                dialog.dismiss();
            });
        } else {
            // 没有加速链接，使用备用链接作为主链接
            tvPrimaryDownloadTitle.setText("下载链接");
            tvPrimaryDownloadDesc.setText("点击复制下载地址");
            cardPrimaryDownload.setCardBackgroundColor(Color.parseColor("#2196F3"));
            cardPrimaryDownload.setOnClickListener(v -> {
                if (backupUrl != null) {
                    copyUrlToClipboard(backupUrl, "下载地址已复制，请在浏览器中打开下载");
                    openInBrowser(backupUrl);
                }
                dialog.dismiss();
            });
        }

        // 设置备用下载按钮
        if (primaryUrl != null && !primaryUrl.isEmpty() && backupUrl != null) {
            // 有加速链接时，显示备用链接
            cardBackupDownload.setVisibility(View.VISIBLE);
            tvBackupDownloadDesc.setText("公益服代理，下载较慢");
            cardBackupDownload.setOnClickListener(v -> {
                copyUrlToClipboard(backupUrl, "备用下载地址已复制，请在浏览器中打开下载");
                openInBrowser(backupUrl);
                dialog.dismiss();
            });
        } else {
            // 没有加速链接时，隐藏备用按钮
            cardBackupDownload.setVisibility(View.GONE);
        }
        // 稍后再说按钮
        ivLater.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * dp 转 px
     */
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    /**
     * 复制链接到剪贴板
     */
    private void copyUrlToClipboard(String url, String toastMessage) {
        if (url == null) {
            Toast.makeText(this, "下载地址不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) {
                Toast.makeText(this, "剪贴板不可用", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipData clip = ClipData.newPlainText("zhujibus_apk_url", url);
            cm.setPrimaryClip(clip);
            Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "复制下载地址失败", e);
            Toast.makeText(this, "复制失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String getLocalVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 拼接远程 APK 的下载地址（原始链接）
     */
    private String getRemoteApkUrl() {
        if (remoteConfig == null) return null;
        return APK_DOWNLOAD_BASE + remoteConfig.remoteVersionCode + "-release.apk";
    }

    /**
     * 拼接远程 APK 的加速下载地址
     * 格式：githubAddSpeed + "/" + 原始 GitHub 路径 + 版本号 + "-release.apk"
     * 例如：https://gh-proxy.com/https://github.com/fgh1995/zhujibus/releases/download/Release/zhujibus-102025-release.apk
     */
    private String getSpeedApkUrl() {
        if (remoteConfig == null) return null;
        if (TextUtils.isEmpty(remoteConfig.githubAddSpeed)) return null;
        // 使用原始 GitHub 路径拼接，避免双重代理
        return remoteConfig.githubAddSpeed + "/" + APK_DOWNLOAD_ORIGINAL + remoteConfig.remoteVersionCode + "-release.apk";
    }

    /**
     * 用浏览器打开 APK 下载地址
     */
    private void openInBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "打开浏览器失败", e);
            Toast.makeText(this, "无法打开浏览器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

}