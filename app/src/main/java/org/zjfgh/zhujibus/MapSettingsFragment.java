package org.zjfgh.zhujibus;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.amap.api.maps.offlinemap.OfflineMapCity;
import com.amap.api.maps.offlinemap.OfflineMapManager;
import com.amap.api.maps.offlinemap.OfflineMapProvince;
import com.amap.api.maps.offlinemap.OfflineMapStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 地图设置页：离线地图数据
 * <p>
 * UI 风格（高德风格）：
 *   - 搜索框
 *   - 省份分组列表（可折叠/展开）
 *   - 城市行：名称 + 大小 + 圆形下载按钮 / 已下载 状态
 *   - 下载中显示进度条
 * <p>
 * 生命周期：
 *   - 进入：按需创建（每次进入都是新实例）
 *   - 退出：随 Fragment.onDestroyView() 自动销毁
 */
public class MapSettingsFragment extends Fragment implements OfflineMapManager.OfflineMapDownloadListener {

    private static final String TAG = "MapSettingsFragment";

    private OfflineMapManager offlineMapManager;
    private EditText searchInput;
    private TextView loadingHint;
    private RecyclerView cityList;
    private TextView tabCities;
    private TextView tabDownloaded;

    /** Tab 常量：城市列表 / 已下载 */
    private static final int TAB_CITIES = 0;
    private static final int TAB_DOWNLOADED = 1;
    private int currentTab = TAB_CITIES;

    /** 所有省份（首次从 SDK 加载） */
    private final List<OfflineMapProvince> allProvinces = new ArrayList<>();
    /** 省份名 -> 城市代码集合 */
    private final Map<String, Set<String>> provinceCityCodesMap = new HashMap<>();
    /** 城市代码 -> 所属省份名（用于反查） */
    private final Map<String, String> cityCodeToProvince = new HashMap<>();

    /** 城市全量数据 */
    private final List<OfflineCityAdapter.CityItem> allCityItems = new ArrayList<>();
    /** 当前显示的扁平行（ProvinceHeader 或 CityItem） */
    private final List<Object> flatRows = new ArrayList<>();
    /** 已展开的省份名集合（搜索时清空） */
    private final Set<String> expandedProvinces = new HashSet<>();
    /** 当前搜索关键词 */
    private String currentKeyword = "";

    private OfflineCityAdapter adapter;

    /** 后台 IO 线程池（高德 SDK 的方法都会做 I/O / DB 查询，必须丢到子线程） */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    /** 主线程 Handler，用于把后台结果切回 UI */
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // 进度刷新节流：高德 onDownload 回调非常频繁（每秒可达几十次），
    // 每次都 notifyDataSetChanged 会导致 RecyclerView 反复重建引起卡顿
    private static final long PROGRESS_REFRESH_INTERVAL_MS = 200;
    private long lastProgressRefreshTime = 0;
    private boolean pendingProgressRefresh = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_map_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {

            searchInput = view.findViewById(R.id.offline_search_input);
            loadingHint = view.findViewById(R.id.offline_loading_hint);
            cityList = view.findViewById(R.id.offline_city_list);
            tabCities = view.findViewById(R.id.offline_tab_cities);
            tabDownloaded = view.findViewById(R.id.offline_tab_downloaded);
            // Tab 切换
            if (tabCities != null) {
                tabCities.setOnClickListener(v -> switchTab(TAB_CITIES));
            }
            if (tabDownloaded != null) {
                tabDownloaded.setOnClickListener(v -> switchTab(TAB_DOWNLOADED));
            }

            // 初始化 RecyclerView
            adapter = new OfflineCityAdapter(flatRows, this);
            cityList.setLayoutManager(new LinearLayoutManager(requireContext()));
            cityList.setAdapter(adapter);

            // 搜索框
            searchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    currentKeyword = s.toString().trim();
                    refreshFlatRows();
                }
            });

            // 初始化离线地图管理器
            try {
                offlineMapManager = new OfflineMapManager(requireContext(), this);
                Log.d(TAG, "OfflineMapManager 构造完成");

                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (offlineMapManager == null || !isAdded()) return;
                    refreshAllCityData();
                    loadProvinces();
                }, 200);
            } catch (Throwable t) {
                Log.e(TAG, "OfflineMapManager 初始化失败", t);
                loadingHint.setText("离线地图模块不可用: " + t.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "onViewCreated 失败", e);
        }
    }

    /**
     * 刷新所有数据（从 SDK 重新查询，IO 放后台）
     */
    private void refreshAllCityData() {
        if (offlineMapManager == null) return;
        ioExecutor.execute(() -> {
            // 后台：调用 SDK（DB / 文件 I/O）
            List<OfflineCityAdapter.CityItem> newAllItems;
            try {
                ArrayList<OfflineMapCity> allCities = offlineMapManager.getOfflineMapCityList();
                List<OfflineMapCity> downloadedCities = offlineMapManager.getDownloadOfflineMapCityList();
                ArrayList<OfflineMapCity> downloadingCities = offlineMapManager.getDownloadingCityList();

                int allN = allCities == null ? 0 : allCities.size();
                int dlN = downloadedCities == null ? 0 : downloadedCities.size();
                int ingN = downloadingCities == null ? 0 : downloadingCities.size();
                Set<String> downloadedSet = new HashSet<>();
                if (downloadedCities != null) {
                    for (OfflineMapCity c : downloadedCities) {
                        if (c != null && c.getCode() != null) downloadedSet.add(c.getCode());
                    }
                }
                Set<String> downloadingSet = new HashSet<>();
                if (downloadingCities != null) {
                    for (OfflineMapCity c : downloadingCities) {
                        if (c != null && c.getCode() != null) downloadingSet.add(c.getCode());
                    }
                }

                newAllItems = new ArrayList<>();

                if (allCities != null) {
                    for (OfflineMapCity city : allCities) {
                        if (city == null) continue;
                        String name = city.getCity();
                        String code = city.getCode();
                        if (name == null || name.isEmpty()) continue;
                        if (code == null || code.isEmpty()) continue;
                        if (name.equals("全国") || name.equals("中华人民共和国")) continue;
                        double sizeBytes = city.getSize();
                        if (sizeBytes <= 0 || sizeBytes > 2L * 1024 * 1024 * 1024) continue;
                        double sizeMb = sizeBytes / (1024.0 * 1024.0);

                        int downloadedState = 0;
                        long localSize = 0;
                        int phase = OfflineCityAdapter.PHASE_NOT_DOWNLOADED;
                        int progress = 0;

                        if (downloadedSet.contains(code)) {
                            downloadedState = 1;
                            localSize = (long) sizeBytes;
                            phase = OfflineCityAdapter.PHASE_DOWNLOADED;
                        } else if (downloadingSet.contains(code)) {
                            int sdkState = -1;
                            try {
                                sdkState = city.getState();
                            } catch (Throwable ignore) {}
                            if (sdkState == OfflineMapStatus.LOADING) {
                                phase = OfflineCityAdapter.PHASE_DOWNLOADING;
                            } else {
                                phase = OfflineCityAdapter.PHASE_WAITING;
                            }
                        }

                        // 所属省份（必须在 loadProvinces 后才有值）
                        String province = cityCodeToProvince.get(code);

                        OfflineCityAdapter.CityItem item = new OfflineCityAdapter.CityItem(
                                name, code, province, sizeMb, localSize,
                                downloadedState, phase, progress
                        );
                        newAllItems.add(item);
                    }
                }

                Collections.sort(newAllItems, (a, b) -> a.name.compareTo(b.name));
            } catch (Throwable t) {
                Log.e(TAG, "refreshAllCityData 失败", t);
                final String errMsg = t.getMessage();
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    if (loadingHint != null) {
                        loadingHint.setVisibility(View.VISIBLE);
                        loadingHint.setText("加载失败: " + errMsg);
                    }
                });
                return;
            }

            // 切回主线程：合并 + 刷新 UI
            mainHandler.post(() -> {
                if (!isAdded()) return;
                preserveAndMerge(allCityItems, newAllItems);
                refreshFlatRows();
            });
        });
    }

    /**
     * 用 newList 替换 oldList 里的内容，保留实时下载状态
     */
    private void preserveAndMerge(List<OfflineCityAdapter.CityItem> oldList,
                                   List<OfflineCityAdapter.CityItem> newList) {
        Map<String, OfflineCityAdapter.CityItem> oldMap = new HashMap<>();
        for (OfflineCityAdapter.CityItem item : oldList) {
            if (item != null && item.cityCode != null) oldMap.put(item.cityCode, item);
        }
        for (OfflineCityAdapter.CityItem newItem : newList) {
            OfflineCityAdapter.CityItem oldItem = oldMap.get(newItem.cityCode);
            if (oldItem == null) continue;

            if (newItem.phase == OfflineCityAdapter.PHASE_DOWNLOADED) {
                newItem.progress = 100;
            } else if (oldItem.phase == OfflineCityAdapter.PHASE_WAITING
                    || oldItem.phase == OfflineCityAdapter.PHASE_DOWNLOADING
                    || oldItem.phase == OfflineCityAdapter.PHASE_PAUSED) {
                newItem.phase = oldItem.phase;
                newItem.progress = oldItem.progress;
            }
        }
        oldList.clear();
        oldList.addAll(newList);
    }

    /**
     * 从 SDK 加载省份列表，构建"省份->城市代码"映射
     */
    private void loadProvinces() {
        if (offlineMapManager == null) return;
        ioExecutor.execute(() -> {
            // 后台：调用 SDK（DB 查询 + 内部数据准备）
            final Map<String, Set<String>> newProvinceCodes = new HashMap<>();
            final Map<String, String> newCodeToProvince = new HashMap<>();
            final List<OfflineMapProvince> newProvinces = new ArrayList<>();
            try {
                ArrayList<OfflineMapProvince> provinces = offlineMapManager.getOfflineMapProvinceList();
                Log.d(TAG, "loadProvinces: count=" + (provinces == null ? 0 : provinces.size()));
                if (provinces != null) {
                    for (OfflineMapProvince p : provinces) {
                        if (p == null) continue;
                        String pname = p.getProvinceName();
                        if (pname == null || pname.isEmpty()) continue;
                        if (pname.equals("全国") || pname.equals("中华人民共和国")) continue;
                        newProvinces.add(p);
                        Set<String> codes = new HashSet<>();
                        ArrayList<OfflineMapCity> cities = p.getCityList();
                        if (cities != null) {
                            for (OfflineMapCity c : cities) {
                                if (c != null && c.getCode() != null) {
                                    codes.add(c.getCode());
                                    newCodeToProvince.put(c.getCode(), pname);
                                }
                            }
                        }
                        newProvinceCodes.put(pname, codes);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "loadProvinces 失败", t);
                return;
            }

            // 切回主线程：写回字段 + 补全 cityItems.province + 刷表
            mainHandler.post(() -> {
                if (!isAdded()) return;
                allProvinces.clear();
                allProvinces.addAll(newProvinces);
                provinceCityCodesMap.clear();
                provinceCityCodesMap.putAll(newProvinceCodes);
                cityCodeToProvince.clear();
                cityCodeToProvince.putAll(newCodeToProvince);

                // 给已存在的 allCityItems 补上 provinceName 字段
                for (OfflineCityAdapter.CityItem item : allCityItems) {
                    if (item != null && item.cityCode != null && item.province == null) {
                        item.province = cityCodeToProvince.get(item.cityCode);
                    }
                }
                refreshFlatRows();
            });
        });
    }

    private List<OfflineMapProvince> sortedProvinces() {
        List<OfflineMapProvince> sorted = new ArrayList<>(allProvinces);
        Collections.sort(sorted, (a, b) -> {
            String an = a.getProvinceName();
            String bn = b.getProvinceName();
            if (an == null) return -1;
            if (bn == null) return 1;
            return an.compareTo(bn);
        });
        return sorted;
    }

    /**
     * 构造 flatRows：省份头 + 已展开省份下的城市
     * 搜索时：只保留匹配的城市，自动展开其父省份
     * 已下载 Tab：只显示已下载的城市，按省份分组
     */
    private void refreshFlatRows() {
        flatRows.clear();

        boolean searching = !currentKeyword.isEmpty();
        boolean onlyDownloaded = (currentTab == TAB_DOWNLOADED);

        // 按省份分组的城市
        Map<String, List<OfflineCityAdapter.CityItem>> grouped = new HashMap<>();
        for (String pname : provinceCityCodesMap.keySet()) {
            grouped.put(pname, new ArrayList<>());
        }
        for (OfflineCityAdapter.CityItem item : allCityItems) {
            if (item.province == null) continue;
            if (onlyDownloaded && item.phase != OfflineCityAdapter.PHASE_DOWNLOADED) continue;
            List<OfflineCityAdapter.CityItem> list = grouped.get(item.province);
            if (list != null) list.add(item);
        }

        List<OfflineMapProvince> sorted = sortedProvinces();
        boolean hasAny = false;
        for (OfflineMapProvince p : sorted) {
            String pname = p.getProvinceName();
            if (pname == null) continue;
            List<OfflineCityAdapter.CityItem> cities = grouped.get(pname);
            if (cities == null || cities.isEmpty()) continue;

            if (searching) {
                // 过滤：省份名匹配 或 城市名匹配
                List<OfflineCityAdapter.CityItem> matches = new ArrayList<>();
                String kw = currentKeyword.toLowerCase();
                if (pname.toLowerCase().contains(kw)) {
                    matches.addAll(cities);
                } else {
                    for (OfflineCityAdapter.CityItem c : cities) {
                        if (c.name != null && c.name.toLowerCase().contains(kw)) {
                            matches.add(c);
                        }
                    }
                }
                if (matches.isEmpty()) continue;
                // 强制展开命中省份（仅当用户没主动折叠时）
                expandedProvinces.add(pname);
                int dlCount = countDownloaded(matches);
                flatRows.add(new ProvinceRow(pname, dlCount, matches.size(), true));
                flatRows.addAll(matches);
                hasAny = true;
            } else {
                int dlCount = countDownloaded(cities);
                boolean expanded = expandedProvinces.contains(pname);
                flatRows.add(new ProvinceRow(pname, dlCount, cities.size(), expanded));
                if (expanded) {
                    flatRows.addAll(cities);
                }
                hasAny = true;
            }
        }

        if (loadingHint != null) {
            if (allCityItems.isEmpty() && allProvinces.isEmpty()) {
                loadingHint.setVisibility(View.VISIBLE);
                loadingHint.setText("正在加载城市列表...");
            } else if (!hasAny) {
                loadingHint.setVisibility(View.VISIBLE);
                if (onlyDownloaded) {
                    loadingHint.setText("暂无已下载的离线地图");
                } else if (searching) {
                    loadingHint.setText("未找到匹配的城市");
                } else {
                    loadingHint.setText("暂无城市数据");
                }
            } else {
                loadingHint.setVisibility(View.GONE);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private int countDownloaded(List<OfflineCityAdapter.CityItem> items) {
        int n = 0;
        for (OfflineCityAdapter.CityItem it : items) {
            if (it.phase == OfflineCityAdapter.PHASE_DOWNLOADED) n++;
        }
        return n;
    }

    /**
     * 切换省份展开/折叠
     */
    public void toggleProvince(String provinceName) {
        if (provinceName == null) return;
        if (expandedProvinces.contains(provinceName)) {
            expandedProvinces.remove(provinceName);
        } else {
            expandedProvinces.add(provinceName);
        }
        refreshFlatRows();
    }

    /**
     * 切换 Tab
     */
    public void switchTab(int tab) {
        if (currentTab == tab) return;
        currentTab = tab;

        if (tabCities != null) {
            boolean active = (tab == TAB_CITIES);
            tabCities.setBackgroundResource(active ? R.drawable.nav_tab_active_bg : R.drawable.nav_tab_inactive_bg);
            tabCities.setTextColor(active ? 0xFFFFFFFF : 0xFF9AA0BC);
            tabCities.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        if (tabDownloaded != null) {
            boolean active = (tab == TAB_DOWNLOADED);
            tabDownloaded.setBackgroundResource(active ? R.drawable.nav_tab_active_bg : R.drawable.nav_tab_inactive_bg);
            tabDownloaded.setTextColor(active ? 0xFFFFFFFF : 0xFF9AA0BC);
            tabDownloaded.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        refreshFlatRows();
    }

    // ============= 操作：按钮点击（下载/暂停/继续/删除） =============

    public void onDownloadClick(OfflineCityAdapter.CityItem item) {
        if (offlineMapManager == null || item == null) return;
        switch (item.phase) {
            case OfflineCityAdapter.PHASE_DOWNLOADED: {
                // 已下载：点击整行弹出底部自定义删除确认框
                showDeleteConfirmDialogAtViewView(item);
                break;
            }
            case OfflineCityAdapter.PHASE_DOWNLOADING: {
                final String name = item.name;
                ioExecutor.execute(() -> {
                    try {
                        offlineMapManager.pause();
                        Log.d(TAG, "暂停: " + name);
                        mainHandler.post(() -> {
                            if (!isAdded()) return;
                            item.phase = OfflineCityAdapter.PHASE_PAUSED;
                            if (adapter != null) adapter.notifyDataSetChanged();
                        });
                    } catch (Throwable t) {
                        Log.e(TAG, "暂停失败: " + name, t);
                    }
                });
                break;
            }
            case OfflineCityAdapter.PHASE_WAITING: {
                final String name = item.name;
                ioExecutor.execute(() -> {
                    try {
                        offlineMapManager.stop();
                        Log.d(TAG, "取消等待: " + name);
                        mainHandler.post(() -> {
                            if (!isAdded()) return;
                            item.phase = OfflineCityAdapter.PHASE_NOT_DOWNLOADED;
                            item.progress = 0;
                            if (adapter != null) adapter.notifyDataSetChanged();
                        });
                    } catch (Throwable t) {
                        Log.e(TAG, "取消失败: " + name, t);
                    }
                });
                break;
            }
            case OfflineCityAdapter.PHASE_PAUSED: {
                final String code = item.cityCode;
                final String name = item.name;
                ioExecutor.execute(() -> {
                    try {
                        offlineMapManager.downloadByCityCode(code);
                        Log.d(TAG, "继续: " + name);
                        mainHandler.post(() -> {
                            if (!isAdded()) return;
                            item.phase = OfflineCityAdapter.PHASE_WAITING;
                            if (adapter != null) adapter.notifyDataSetChanged();
                        });
                    } catch (Throwable t) {
                        Log.e(TAG, "继续失败: " + name, t);
                    }
                });
                break;
            }
            case OfflineCityAdapter.PHASE_NOT_DOWNLOADED:
            default: {
                final String code = item.cityCode;
                final String name = item.name;
                ioExecutor.execute(() -> {
                    try {
                        offlineMapManager.downloadByCityCode(code);
                        Log.d(TAG, "启动下载: " + name + " code=" + code);
                        mainHandler.post(() -> {
                            if (!isAdded()) return;
                            item.phase = OfflineCityAdapter.PHASE_WAITING;
                            item.progress = 0;
                            if (adapter != null) adapter.notifyDataSetChanged();
                        });
                    } catch (Throwable t) {
                        Log.e(TAG, "下载启动失败: " + name, t);
                    }
                });
                break;
            }
        }
    }

    private void doRemoveOnMain(String code, String name) {
        if (offlineMapManager == null) return;
        // 高德 SDK 的 remove(String) 接收的是【城市名称】，不是 city code
        // 参考：https://a.amap.com/lbs/static/unzip/AMap_HarmonyOS_API_3DMap_Doc/com/amap/api/maps/offlinemap/OfflineMapManager.html
        try {
            // 立即把列表状态切回"未下载"，避免用户重复点击
            resetItemToNotDownloaded(code);
            // 真正删除（传城市名，不是城市编码）
            offlineMapManager.remove(name);
            Log.d(TAG, "删除: " + name + " (code=" + code + ")");
        } catch (Throwable t) {
            Log.e(TAG, "删除失败: " + name, t);
            Toast.makeText(requireContext(), "删除失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void resetItemToNotDownloaded(String code) {
        for (OfflineCityAdapter.CityItem it : allCityItems) {
            if (it.cityCode != null && it.cityCode.equals(code)) {
                it.downloadedState = 0;
                it.localSize = 0;
                it.phase = OfflineCityAdapter.PHASE_NOT_DOWNLOADED;
                it.progress = 0;
            }
        }
        refreshFlatRows();
    }

    private void showDeleteConfirmDialogAtViewView (OfflineCityAdapter.CityItem item) {
        if (!isAdded()) return;

        final String code = item.cityCode;
        final String name = item.name;
        double localMb = item.localSize / (1024.0 * 1024.0);
        double showMb = Math.max(item.sizeMb, localMb);
        final String sizeText = String.format("%.2f MB", showMb);

        View fragmentRoot = getView();
        if (fragmentRoot == null) return;

        FrameLayout container = fragmentRoot.findViewById(R.id.dialog_container);
        if (container == null) return;

        container.removeAllViews();
        container.setVisibility(View.VISIBLE);

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_offline_delete, container, false);

        TextView message = dialogView.findViewById(R.id.dialog_delete_message);
        message.setText("确定要删除「" + name + "」的离线地图包吗？\n（" + sizeText + "）");

        // 设置居中
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.CENTER;

        int marginHorizontal = (int) (12 * getResources().getDisplayMetrics().density);
        params.leftMargin = marginHorizontal;
        params.rightMargin = marginHorizontal;

        dialogView.setLayoutParams(params);
        container.addView(dialogView);

        // 点击外部关闭
        container.setOnClickListener(v -> {
            dialogView.animate()
                    .alpha(0)
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .setDuration(200)
                    .withEndAction(() -> {
                        container.removeAllViews();
                        container.setVisibility(View.GONE);
                        container.setAlpha(1f);
                        container.setScaleX(1f);
                        container.setScaleY(1f);
                    })
                    .start();
        });

        dialogView.setOnClickListener(v -> {});

        dialogView.findViewById(R.id.dialog_delete_cancel).setOnClickListener(v -> {
            dialogView.animate()
                    .alpha(0)
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .setDuration(200)
                    .withEndAction(() -> {
                        container.removeAllViews();
                        container.setVisibility(View.GONE);
                        container.setAlpha(1f);
                        container.setScaleX(1f);
                        container.setScaleY(1f);
                    })
                    .start();
        });

        dialogView.findViewById(R.id.dialog_delete_confirm).setOnClickListener(v -> {
            dialogView.animate()
                    .alpha(0)
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .setDuration(200)
                    .withEndAction(() -> {
                        container.removeAllViews();
                        container.setVisibility(View.GONE);
                        container.setAlpha(1f);
                        container.setScaleX(1f);
                        container.setScaleY(1f);
                        doRemoveOnMain(code, name);
                    })
                    .start();
        });

        // 入场动画
        dialogView.setAlpha(0f);
        dialogView.setScaleX(0.9f);
        dialogView.setScaleY(0.9f);
        dialogView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    // ============= OfflineMapDownloadListener =============

    @Override
    public void onDownload(int status, int completeCode, String downName) {
        Log.d(TAG, "onDownload: status=" + status + " code=" + completeCode + " name=" + downName);
        // SDK 回调可能在非主线程，统一切回主线程更新 UI / 共享列表
        mainHandler.post(() -> {
            if (!isAdded()) return;

            if (status == OfflineMapStatus.SUCCESS) {
                Log.d(TAG, "下载完成: code=" + completeCode + " name=" + downName);
                // 下载完成是状态变化，不节流，立即全量刷新
                refreshAllCityData();
            } else if (status == OfflineMapStatus.LOADING) {
                // 进度更新：只改数据，UI 刷新走节流，避免高频回调卡 UI
                updateItemPhase(completeCode, OfflineCityAdapter.PHASE_DOWNLOADING, completeCode, false);
                notifyProgressThrottled();
            } else if (status == OfflineMapStatus.WAITING) {
                updateItemPhase(completeCode, OfflineCityAdapter.PHASE_WAITING, 0, false);
                if (adapter != null) adapter.notifyDataSetChanged();
            } else if (status == OfflineMapStatus.PAUSE) {
                updateItemPhaseByName(downName, OfflineCityAdapter.PHASE_PAUSED, -1, true);
                if (adapter != null) adapter.notifyDataSetChanged();
            } else if (status == OfflineMapStatus.UNZIP) {
                updateItemPhase(completeCode, OfflineCityAdapter.PHASE_DOWNLOADING, 99, false);
                if (adapter != null) adapter.notifyDataSetChanged();
            } else if (status == OfflineMapStatus.ERROR || status < 0) {
                updateItemPhase(completeCode, OfflineCityAdapter.PHASE_NOT_DOWNLOADED, 0, false);
                if (adapter != null) adapter.notifyDataSetChanged();
            } else {
                updateItemPhase(completeCode, OfflineCityAdapter.PHASE_NOT_DOWNLOADED, 0, false);
                if (adapter != null) adapter.notifyDataSetChanged();
            }
        });
    }

    /**
     * 进度刷新节流：
     * - 高德 onDownload LOADING 回调非常频繁（每秒可达几十次）
     * - 每次都 notifyDataSetChanged 会让 RecyclerView 全表重建，主线程卡顿
     * - 改成：200ms 内最多刷一次，刷新被吞掉时再补一次延迟刷新保证最终值上屏
     */
    private void notifyProgressThrottled() {
        long now = android.os.SystemClock.uptimeMillis();
        long elapsed = now - lastProgressRefreshTime;
        if (elapsed >= PROGRESS_REFRESH_INTERVAL_MS) {
            lastProgressRefreshTime = now;
            pendingProgressRefresh = false;
            if (adapter != null) adapter.notifyDataSetChanged();
        } else if (!pendingProgressRefresh) {
            pendingProgressRefresh = true;
            long delay = PROGRESS_REFRESH_INTERVAL_MS - elapsed;
            mainHandler.postDelayed(() -> {
                if (!isAdded()) return;
                pendingProgressRefresh = false;
                lastProgressRefreshTime = android.os.SystemClock.uptimeMillis();
                if (adapter != null) adapter.notifyDataSetChanged();
            }, delay);
        }
    }

    @Override
    public void onCheckUpdate(boolean hasNew, String name) {
        Log.d(TAG, "onCheckUpdate: hasNew=" + hasNew + " name=" + name);
    }

    @Override
    public void onRemove(boolean success, String name, String size) {
        Log.d(TAG, "onRemove: success=" + success + " name=" + name + " size=" + size);
        mainHandler.post(() -> {
            if (!isAdded()) return;
            refreshAllCityData();
        });
    }

    private void updateItemPhase(int code, int phase, int progress, boolean byName) {
        String raw = String.valueOf(code);
        String p3 = String.format("%03d", code);
        String p4 = String.format("%04d", code);
        String p5 = String.format("%05d", code);
        for (OfflineCityAdapter.CityItem item : allCityItems) {
            if (item.cityCode == null) continue;
            if (item.cityCode.equals(raw)
                    || item.cityCode.equals(p3)
                    || item.cityCode.equals(p4)
                    || item.cityCode.equals(p5)) {
                if (item.phase == OfflineCityAdapter.PHASE_DOWNLOADED
                        && phase != OfflineCityAdapter.PHASE_DOWNLOADED) {
                    return;
                }
                item.phase = phase;
                if (progress >= 0) item.progress = progress;
                return;
            }
        }
    }

    private void updateItemPhaseByName(String name, int phase, int progress, boolean keepProgress) {
        if (name == null) return;
        for (OfflineCityAdapter.CityItem item : allCityItems) {
            if (name.equals(item.name)) {
                if (item.phase == OfflineCityAdapter.PHASE_DOWNLOADED
                        && phase != OfflineCityAdapter.PHASE_DOWNLOADED) {
                    return;
                }
                item.phase = phase;
                if (!keepProgress && progress >= 0) item.progress = progress;
                return;
            }
        }
    }

    // ============= lifecycle =============

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (offlineMapManager != null) {
            try {
                offlineMapManager.stop();
            } catch (Throwable t) {
                Log.w(TAG, "stop 异常", t);
            }
            offlineMapManager.destroy();
            offlineMapManager = null;
        }
        if (cityList != null) {
            cityList.setAdapter(null);
        }
        // 关闭后台线程池
        try {
            ioExecutor.shutdown();
        } catch (Throwable t) {
            Log.w(TAG, "executor shutdown 异常", t);
        }
        // 清空主线程 Handler 上挂着的回调（包含节流的 postDelayed）
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        // 重置节流状态，避免下次进入残留
        lastProgressRefreshTime = 0;
        pendingProgressRefresh = false;
        cityList = null;
        searchInput = null;
        loadingHint = null;
        tabCities = null;
        tabDownloaded = null;
        adapter = null;
    }

    // ============= Row 模型 =============

    /** 省份行（RecyclerView type 0） */
    public static class ProvinceRow {
        public final String provinceName;
        public int downloaded;
        public int total;
        public boolean expanded;

        public ProvinceRow(String provinceName, int downloaded, int total, boolean expanded) {
            this.provinceName = provinceName;
            this.downloaded = downloaded;
            this.total = total;
            this.expanded = expanded;
        }
    }

    // ============= Adapter（多类型：省份头 + 城市行） =============

    public static class OfflineCityAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        public static final int TYPE_PROVINCE = 0;
        public static final int TYPE_CITY = 1;

        public static final int PHASE_NOT_DOWNLOADED = 0;
        public static final int PHASE_WAITING       = 1;
        public static final int PHASE_DOWNLOADING   = 2;
        public static final int PHASE_PAUSED        = 3;
        public static final int PHASE_DOWNLOADED    = 4;

        public static class CityItem {
            public String name;
            public String cityCode;
            /** 所属省份名（用于分组） */
            public String province;
            public double sizeMb;
            public long localSize;
            public int downloadedState;
            public int phase = PHASE_NOT_DOWNLOADED;
            public int progress;

            public CityItem(String name, String cityCode, String province, double sizeMb, long localSize,
                            int downloadedState, int phase, int progress) {
                this.name = name;
                this.cityCode = cityCode;
                this.province = province;
                this.sizeMb = sizeMb;
                this.localSize = localSize;
                this.downloadedState = downloadedState;
                this.phase = phase;
                this.progress = progress;
            }

            public boolean isDownloaded() { return downloadedState == 1; }
        }

        private final List<Object> rows;
        private final MapSettingsFragment fragment;

        public OfflineCityAdapter(List<Object> rows, MapSettingsFragment fragment) {
            this.rows = rows;
            this.fragment = fragment;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof ProvinceRow ? TYPE_PROVINCE : TYPE_CITY;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_PROVINCE) {
                View v = inflater.inflate(R.layout.item_offline_province_row, parent, false);
                return new ProvinceVH(v);
            } else {
                View v = inflater.inflate(R.layout.item_offline_city, parent, false);
                return new CityVH(v);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object row = rows.get(position);
            if (holder instanceof ProvinceVH) {
                bindProvince((ProvinceVH) holder, (ProvinceRow) row);
            } else if (holder instanceof CityVH) {
                bindCity((CityVH) holder, (CityItem) row);
            }
        }

        private void bindProvince(ProvinceVH h, ProvinceRow pr) {
            h.provinceName.setText(pr.provinceName);
            h.provinceCount.setText(pr.downloaded + "/" + pr.total);
            h.provinceArrow.setImageResource(pr.expanded ? R.drawable.nav_arrow_up : R.drawable.nav_arrow_down);
            h.provinceRow.setOnClickListener(v -> {
                if (fragment != null) fragment.toggleProvince(pr.provinceName);
            });
        }

        private void bindCity(CityVH h, CityItem item) {
            h.cityName.setText(item.name);

            // 大小文字（"X.XX MB" 风格）
            if (item.phase == PHASE_DOWNLOADED) {
                double localMb = item.localSize / (1024.0 * 1024.0);
                double showMb = Math.max(item.sizeMb, localMb);
                h.citySize.setText(String.format("%.2f MB", showMb));
                h.citySize.setTextColor(0xFF6A6F90);
            } else {
                h.citySize.setText(String.format("%.2f MB", item.sizeMb));
                h.citySize.setTextColor(0xFF9AA0BC);
            }

            // 状态文字：未下载显示「下载」作为可点击操作提示
            switch (item.phase) {
                case PHASE_DOWNLOADED: {
                    h.cityStatus.setText("已下载");
                    h.cityStatus.setTextColor(0xFF6ACA6A);
                    h.progress.setVisibility(ProgressBar.GONE);
                    break;
                }
                case PHASE_DOWNLOADING: {
                    int p = Math.max(0, Math.min(100, item.progress));
                    h.cityStatus.setText("下载中 " + p + "%");
                    h.cityStatus.setTextColor(0xFF37D4F4);
                    h.progress.setVisibility(ProgressBar.VISIBLE);
                    h.progress.setProgress(p);
                    break;
                }
                case PHASE_WAITING: {
                    h.cityStatus.setText("等待中");
                    h.cityStatus.setTextColor(0xFFFFC858);
                    h.progress.setVisibility(ProgressBar.GONE);
                    break;
                }
                case PHASE_PAUSED: {
                    int p = Math.max(0, Math.min(100, item.progress));
                    h.cityStatus.setText("已暂停 " + p + "%");
                    h.cityStatus.setTextColor(0xFFFFC858);
                    h.progress.setVisibility(ProgressBar.VISIBLE);
                    h.progress.setProgress(p);
                    break;
                }
                case PHASE_NOT_DOWNLOADED:
                default: {
                    // 当作可点击的「下载」操作提示，蓝色突出
                    h.cityStatus.setText("下载");
                    h.cityStatus.setTextColor(0xFF37D4F4);
                    h.progress.setVisibility(ProgressBar.GONE);
                    break;
                }
            }

            // 整行可点击：未下载/暂停触发继续下载，下载中触发暂停，等待中触发取消，已下载触发删除
            h.cityRow.setOnClickListener(v -> {
                if (fragment != null) fragment.onDownloadClick(item);
            });
        }

        @Override
        public int getItemCount() {
            return rows == null ? 0 : rows.size();
        }

        static class ProvinceVH extends RecyclerView.ViewHolder {
            final View provinceRow;
            final TextView provinceName;
            final TextView provinceCount;
            final ImageView provinceArrow;

            ProvinceVH(@NonNull View itemView) {
                super(itemView);
                provinceRow = itemView.findViewById(R.id.offline_province_row);
                provinceName = itemView.findViewById(R.id.offline_province_name);
                provinceCount = itemView.findViewById(R.id.offline_province_count);
                provinceArrow = itemView.findViewById(R.id.offline_province_arrow);
            }
        }

        static class CityVH extends RecyclerView.ViewHolder {
            final View cityRow;
            final TextView cityName;
            final TextView citySize;
            final TextView cityStatus;
            final ProgressBar progress;

            CityVH(@NonNull View itemView) {
                super(itemView);
                cityRow = itemView.findViewById(R.id.offline_city_row);
                cityName = itemView.findViewById(R.id.offline_city_name);
                citySize = itemView.findViewById(R.id.offline_city_size);
                cityStatus = itemView.findViewById(R.id.offline_city_status);
                progress = itemView.findViewById(R.id.offline_city_progress);
            }
        }
    }
}
