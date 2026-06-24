package org.zjfgh.zhujibus;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import io.sgr.geometry.Coordinate;
import io.sgr.geometry.utils.GeometryUtils;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;
import android.widget.Button;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private ViewFlipper viewFlipper;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            recyclerView = findViewById(R.id.recyclerView);
            tv_search_line = findViewById(R.id.tv_search_line);
            viewFlipper = findViewById(R.id.view_flipper);
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
                        if (response == null) {
                            Log.w("-BusInfo-", "滚动公告请求失败-无数据");
                            return;
                        }
                        if (!"200".equals(response.code)) {
                            Log.w("-BusInfo-", "滚动公告请求失败-状态码异常：" + response.code);
                            return;
                        }
                        if (response.data == null || response.data.isEmpty()) {
                            Log.w("-BusInfo-", "滚动公告数据为空");
                            return;
                        }
                        for (BusApiClient.BusAnnouncement announcement : response.data) {
                            try {
                                TextView textView = getTextView(announcement);
                                viewFlipper.addView(textView);
                            } catch (Exception e) {
                                Log.e("-BusInfo-", "添加公告视图失败", e);
                            }
                        }
                        viewFlipper.startFlipping();
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
                    + " (build " + remoteConfig.remoteVersionCode + ") ，点击查看更新内容";
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
    }

    private int getLocalVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 弹出更新对话框：显示更新日志，用户点击确认后复制下载地址自行下载安装。
     * 已不再在应用内做下载 / 安装调用，规避 Android 各厂商系统安装器兼容性差异。
     */
    private void showUpdateDialog() {
        if (remoteConfig == null) return;
        if (isFinishing() || isDestroyed()) return;
        String versionInfo = "当前版本：v" + getLocalVersionName()
                + "\n最新版本：v" + remoteConfig.remoteVersionName
                + " (build " + remoteConfig.remoteVersionCode + ")";
        String log = TextUtils.isEmpty(remoteConfig.updateLog)
                ? "（未提供更新日志）" : remoteConfig.updateLog;
        String url = getRemoteApkUrl();
        String speedUrl = getSpeedApkUrl();
        if (url == null) {
            Toast.makeText(this, "更新信息丢失，请稍后重试", Toast.LENGTH_SHORT).show();
            return;
        }

        // 构建下载链接信息
        StringBuilder downloadInfo = new StringBuilder();
        downloadInfo.append("\n\n请复制下方链接到浏览器中下载，")
                .append("下载完成后在系统文件管理器或下载列表中点击 APK 手动安装：\n\n");

        // 加速下载链接（如果有，优先显示）
        if (speedUrl != null && !speedUrl.isEmpty()) {
            downloadInfo.append("【加速链接】（推荐，下载更快）\n").append(speedUrl).append("\n\n");
        }

        // 原始下载链接（公益服代理）
        downloadInfo.append("【备用链接】（公益服代理，下载较慢）\n").append(url);

        new AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage(versionInfo
                        + "\n\n更新日志：\n" + log
                        + downloadInfo.toString())
                .setPositiveButton("复制加速链接", (d, w) -> copySpeedUrlToClipboard())
                .setNeutralButton("在浏览器中打开", (d, w) -> {
                    // 优先打开加速链接
                    String openUrl = (speedUrl != null && !speedUrl.isEmpty()) ? speedUrl : url;
                    openInBrowser(openUrl);
                })
                .setNegativeButton("稍后再说", null)
                .setCancelable(true)
                .show();
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
     * 把远程 APK 下载地址复制到系统剪贴板，并 Toast 提示用户
     */
    private void copyDownloadUrlToClipboard() {
        String url = getRemoteApkUrl();
        if (url == null) {
            Toast.makeText(this, "更新信息丢失，无法复制", Toast.LENGTH_SHORT).show();
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
            // Android 13+ 系统会自动显示复制提示；旧版本则通过 Toast 提示
            Toast.makeText(this, "下载地址已复制，请在浏览器中打开下载（公益服下载较慢，请耐心等待）", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "复制下载地址失败", e);
            Toast.makeText(this, "复制失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 把加速 APK 下载地址复制到系统剪贴板，并 Toast 提示用户
     */
    private void copySpeedUrlToClipboard() {
        String url = getSpeedApkUrl();
        if (url == null || url.isEmpty()) {
            // 如果没有加速链接，复制原始链接
            copyDownloadUrlToClipboard();
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) {
                Toast.makeText(this, "剪贴板不可用", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipData clip = ClipData.newPlainText("zhujibus_apk_speed_url", url);
            cm.setPrimaryClip(clip);
            // Android 13+ 系统会自动显示复制提示；旧版本则通过 Toast 提示
            Toast.makeText(this, "加速下载地址已复制，请在浏览器中打开下载", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "复制加速下载地址失败", e);
            Toast.makeText(this, "复制失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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