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
import android.net.Uri;
import android.os.Bundle;
import io.sgr.geometry.Coordinate;
import io.sgr.geometry.utils.GeometryUtils;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.autonavi.amap.mapcore.interfaces.IAMap;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            recyclerView = findViewById(R.id.recyclerView);
            tv_search_line = findViewById(R.id.tv_search_line);
            autoScrollTextView = findViewById(R.id.auto_scroll_text);
            tvNoData = findViewById(R.id.tv_no_data);
            llUpdateNotice = findViewById(R.id.ll_update_notice);
            llNotice = findViewById(R.id.ll_notice);
            hstvUpdateNotice = findViewById(R.id.hstv_update_notice);
            hstvNotice = findViewById(R.id.hstv_notice);
            // ⭐ 关键修复：先挂一个空 adapter，避免首次 layout 时报 "No adapter attached"
            // 数据加载完后会被真正的 StationRouteAdapter 替换
            recyclerView.setAdapter(new BusStationAdapter());
            client = new BusApiClient();
            if (PermissionUtils.hasLocationPermission(this)) {
                startGpsIfNeeded();
            } else {
                PermissionUtils.requestLocationPermission(this, new PermissionUtils.PermissionCallback() {
                    @Override
                    public void onPermissionGranted() {
                        runOnUiThread(() -> startGpsIfNeeded());
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
        } catch (Exception e) {
            Log.e("MainActivity", "初始化失败", e);
            Toast.makeText(this, "应用初始化失败", Toast.LENGTH_LONG).show();
        }
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
        GpsWarmingUp.removeListener(gpsListener);
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
        StationDetailsFragment stationDetailsFragment = new StationDetailsFragment(stationName);
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
                        return;
                    }
                    String text = resp.body().string();
                    RemoteConfig cfg = parseRemoteConfig(text);
                    if (cfg == null) {
                        Log.w(TAG, "远程配置解析失败");
                        return;
                    }
                    remoteConfig = cfg;
                    runOnUiThread(() -> applyRemoteConfig());
                }
            } catch (IOException e) {
                Log.w(TAG, "拉取远程配置异常：" + e.getMessage());
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