package org.zjfgh.zhujibus;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.app.Dialog;
import android.view.ViewGroup;
import android.view.Window;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class StationDetailsFragment extends DialogFragment {
    private static final String ARG_STATION_NAME = "station_name";
    private BusApiClient busApiClient;
    private TTSUtils ttsUtils;
    private RecyclerView recyclerView;
    private BusStationAdapter adapter;
    private String currentStationName;
    // 已播报过的车辆：key = lineId_stationId[_车牌] -> 播报时间。
    // 按「车辆」而不是「线路」记录，并且只在车辆到站/过站（或记录过期）后失效，
    // 这样既不会因为 GPS 抖动、某一轮取不到数据而把同一辆车重复播报，也不会漏掉后面的车。
    private final Map<String, Long> announcedVehicles = new HashMap<>();
    // 已播报过的「计划发车提醒」：lineId_stationId_HH:mm，用于同一分钟内不重复播报
    private Set<String> announcedDepartures = new HashSet<>();
    private Handler refreshHandler;
    private Runnable refreshRunnable;
    private static final long REFRESH_INTERVAL = 10000;
    // 报站距离阈值：车辆距本站 < 400m 才语音报站
    private static final int ANNOUNCE_MAX_DISTANCE = 400;
    // 车辆播报记录的保留时长：超过后允许重新播报（同一辆车很久以后再次驶向本站等场景）
    private static final long ANNOUNCE_VEHICLE_RECORD_TTL_MS = 60 * 60 * 1000L;
    private DirectionMarkerDatabaseHelper dbHelper;
    private LinearLayout markersContainer;
    private LinearLayout markersScrollContent;
    private List<BusApiClient.StationLineInfo> currentBusLineItems;
    private DirectionMarker currentSelectedMarker;
    private MarkerSearchAdapter markerSearchAdapter;
    // 线路详情缓存：lineName -> 线路双向数据（含各方向站点列表），用于把标记站点换算成站点序号
    private final Map<String, BusApiClient.BusLineDetailData> lineDetailCache = new HashMap<>();
    // 标记刷新批次号：切换标记/重新查询时自增，用于丢弃过期回调
    private int markerRequestGeneration = 0;
    // 站点取数批次号：每次 loadStationData 自增，切标记/退出页面时也自增。
    // 非标记链路（车辆动态 → 计划发车时间）的回调都带批次号校验，
    // 避免"点标记前发出的双向数据请求"回来后把双向列表刷上界面、还按错方向播报。
    private int stationDataGeneration = 0;
    // 最近一次站点接口返回的原始数据（双向）：点自定义标记时先用它立刻裁剪出标记方向的列表，
    // 不必等下一轮响应（否则点了标记还会继续显示双向列表）
    private List<BusApiClient.StationLineInfo> latestStationResponse;
    // 标记模式逐条线路处理的进度：当前正在处理的线路下标（-1 表示未在处理）
    private int markerLineIndex = -1;
    // 标记模式一轮线路取数是否仍在进行：进行中时刷新节拍不再发起新一轮，
    // 避免新一轮把上一轮还没取完的线路请求作废（表现为列表迟迟不出车辆、迟迟不报站）
    private boolean markerLoadingInProgress = false;
    // 已记录、尚未到点的计划发车时间：计划发车时间会被服务端滚成下一班
    // （22:00 那班一开走就变成下一班），只靠「取数那一刻」比对很容易正好错过整点而漏播，
    // 所以记录下来交给每轮刷新节拍复查。
    private final List<PendingDeparturePlan> pendingDeparturePlans = new ArrayList<>();
    // 本轮批量取回的计划发车时间：lineId -> HH:mm。
    // /bus/vehicle/plan 支持逗号分隔的多个 lineId（一次请求即可），不必逐条线路轮询。
    private final Map<String, String> markerPlanTimeByLine = new HashMap<>();
    // 卡片展示顺序（键 lineId_stationId）：每轮排序后更新，下一轮就按这个新顺序逐条查询
    private final List<String> markerDisplayOrder = new ArrayList<>();
    // 本轮已完成车辆取数的线路（键 lineId_stationId）：只有取过数的线路才知道它到底有没有车，
    // 发车广播必须等"有车/无车"确定后再判断，避免刚清空车辆数据时误播
    private final Set<String> markerProcessedLines = new HashSet<>();
    // 卡片上仍是"上一轮数据"的线路（键 lineId_stationId）：这些卡片淡化显示，
    // 本轮数据回来后逐条取消淡化，用于区分"新/旧"数据（集合引用交给适配器读取）
    private final Set<String> markerStaleLines = new HashSet<>();
    // 本轮真的取到车辆数据的线路：取数失败/超时的线路保持淡化，不能把旧数据当成新数据展示
    private final Set<String> markerRefreshedLines = new HashSet<>();
    // 当前 currentBusLineItems 是否已经是"标记方向的单向列表"：
    // 是则每轮只需把新数据合并进去（保留旧的车/发车时间），否则要按标记重新裁剪
    private boolean markerListActive = false;
    // 单条线路最长等待时间：超时不再等待该线路，直接处理下一条，避免一条慢线路拖住整轮刷新
    private static final long MARKER_LINE_TIMEOUT_MS = 5000;
    private Handler scheduleHandler;

    /** 已记录、尚未到点的计划发车时间（planHm 形如 HH:mm） */
    private static class PendingDeparturePlan {
        final String lineId;
        final String stationId;
        final String planHm;

        PendingDeparturePlan(String lineId, String stationId, String planHm) {
            this.lineId = lineId;
            this.stationId = stationId;
            this.planHm = planHm;
        }
    }

    public static StationDetailsFragment newInstance(String stationName) {
        StationDetailsFragment fragment = new StationDetailsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_STATION_NAME, stationName);
        fragment.setArguments(args);
        return fragment;
    }

    public StationDetailsFragment() {
        // 无参构造函数，供系统恢复状态时调用
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NORMAL, R.style.TransparentDialog);
        if (getArguments() != null) {
            currentStationName = getArguments().getString(ARG_STATION_NAME);
        }
        busApiClient = new BusApiClient();
        dbHelper = DirectionMarkerDatabaseHelper.getInstance(requireContext());
        ttsUtils = TTSUtils.getInstance(requireContext());
        scheduleHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_station_details, container, false);
        TextView stationTitle = view.findViewById(R.id.station_title);
        stationTitle.setText(this.currentStationName);

        markersContainer = view.findViewById(R.id.markers_container);
        markersScrollContent = view.findViewById(R.id.markers_scroll_content);

        recyclerView = view.findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(
                recyclerView.getContext(),
                LinearLayoutManager.VERTICAL
        );
        Drawable transparentDivider = new ColorDrawable(Color.TRANSPARENT);
        transparentDivider.setBounds(0, 0, 0, 8);
        dividerItemDecoration.setDrawable(transparentDivider);
        recyclerView.addItemDecoration(dividerItemDecoration);
        // 自定义条目动画：交换放慢 + 预动作回位（默认 250ms 太快看不清交换过程）
        recyclerView.setItemAnimator(new BusStationItemAnimator());
        adapter = new BusStationAdapter();
        recyclerView.setAdapter(adapter);

        setupDirectionAdapterListener();
        initRefreshHandler();
        loadStationData();
        loadDirectionMarkers();
        return view;
    }

    private void setupDirectionAdapterListener() {
        adapter.setOnDirectionLongClickListener((direction, anchorView) -> {
            showAddMarkerDialog(direction);
        });
    }

    private void showAddMarkerDialog(BusApiClient.LineDirection direction) {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_search_direction_marker, null);

        TextView titleText = dialogView.findViewById(R.id.dialog_title);
        TextView stationInfoText = dialogView.findViewById(R.id.station_info_text);
        EditText markerNameInput = dialogView.findViewById(R.id.marker_name_input);
        RecyclerView searchResults = dialogView.findViewById(R.id.markers_search_results);
        TextView noMarkersText = dialogView.findViewById(R.id.no_markers_text);

        titleText.setText("添加到方向标记");
        stationInfoText.setText(String.format("线路：%s\n起点：%s\n终点：%s\n站点：%s",
                direction.lineName, direction.startStation, direction.endStation, currentStationName));

        markerSearchAdapter = new MarkerSearchAdapter();
        searchResults.setLayoutManager(new LinearLayoutManager(requireContext()));
        searchResults.setAdapter(markerSearchAdapter);

        List<DirectionMarker> otherMarkers = dbHelper.getMarkersByStationName(currentStationName);
        List<DirectionMarker> allMarkers = dbHelper.getAllMarkers();

        for (DirectionMarker m : allMarkers) {
            if (!m.stationName.equals(currentStationName)) {
                otherMarkers.add(m);
            }
        }

        if (otherMarkers.isEmpty()) {
            noMarkersText.setVisibility(View.VISIBLE);
            searchResults.setVisibility(View.GONE);
        } else {
            noMarkersText.setVisibility(View.GONE);
            searchResults.setVisibility(View.VISIBLE);
            markerSearchAdapter.setData(otherMarkers);
        }

        markerSearchAdapter.setOnMarkerClickListener(selectedMarker -> {
            selectedMarker.addLine(direction.lineId, direction.stationId,
                    direction.lineName, direction.lineTypeName,
                    direction.startStation, direction.endStation,
                    direction.departureTime, direction.collectTime);
            dbHelper.updateMarker(selectedMarker);
            Toast.makeText(requireContext(), "已添加到：" + selectedMarker.markerName, Toast.LENGTH_SHORT).show();
            loadDirectionMarkers();
        });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .setPositiveButton("创建新标记", (dialog, which) -> {
                    String markerName = markerNameInput.getText().toString().trim();
                    if (markerName.isEmpty()) {
                        Toast.makeText(requireContext(), "请输入方向名称", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveDirectionMarker(markerName, direction);
                })
                .setNegativeButton("取消", null);

        Dialog dialog = builder.create();
        dialog.show();
        setDialogFullWidth(dialog);
    }

    private void setDialogFullWidth(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void saveDirectionMarker(String markerName, BusApiClient.LineDirection direction) {
        DirectionMarker existingMarker = dbHelper.getMarkerByStationAndMarkerName(currentStationName, markerName);
        if (existingMarker != null) {
            if (!existingMarker.lineIds.contains(direction.lineId)) {
                existingMarker.addLine(direction.lineId, direction.stationId,
                        direction.lineName, direction.lineTypeName,
                        direction.startStation, direction.endStation,
                        direction.departureTime, direction.collectTime);
                dbHelper.updateMarker(existingMarker);
                Toast.makeText(requireContext(), "已添加到方向标记：" + markerName, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "该方向已添加过此线路", Toast.LENGTH_SHORT).show();
            }
        } else {
            DirectionMarker marker = new DirectionMarker(markerName, currentStationName);
            marker.addLine(direction.lineId, direction.stationId,
                    direction.lineName, direction.lineTypeName,
                    direction.startStation, direction.endStation,
                    direction.departureTime, direction.collectTime);
            dbHelper.insertMarker(marker);
            Toast.makeText(requireContext(), "已保存方向标记：" + markerName, Toast.LENGTH_SHORT).show();
        }
        loadDirectionMarkers();
    }

    private void loadDirectionMarkers() {
        List<DirectionMarker> markers = dbHelper.getMarkersByStationName(currentStationName);
        markersScrollContent.removeAllViews();

        if (markers.isEmpty()) {
            markersContainer.setVisibility(View.GONE);
            return;
        }

        markersContainer.setVisibility(View.VISIBLE);

        for (DirectionMarker marker : markers) {
            View chipView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_direction_marker_chip, markersScrollContent, false);
            TextView chipText = chipView.findViewById(R.id.marker_chip);

            chipText.setText(getSimplifiedMarkerName(marker) + "(" + marker.lineIds.size() + ")");

            if (currentSelectedMarker != null && currentSelectedMarker.id == marker.id) {
                chipView.setBackgroundResource(R.drawable.marker_chip_selected_background);
                chipText.setTextColor(Color.WHITE);
            } else {
                chipView.setBackgroundResource(R.drawable.marker_chip_background);
                chipText.setTextColor(Color.parseColor("#0070FD"));
            }

            chipView.setOnClickListener(v -> {
                if (currentSelectedMarker != null && currentSelectedMarker.id == marker.id) {
                    showMarkerLinesDialog(marker);
                } else {
                    queryWithMarker(marker);   // 内部会设置选中标记并立刻把列表裁成标记方向
                }
                loadDirectionMarkers();
            });
            chipView.setOnLongClickListener(v -> {
                showDeleteMarkerDialog(marker);
                return true;
            });

            markersScrollContent.addView(chipView);
        }
    }

    private String getSimplifiedMarkerName(DirectionMarker marker) {
        if (currentStationName != null && marker.markerName.startsWith(currentStationName)) {
            String suffix = marker.markerName.substring(currentStationName.length());
            suffix = suffix.replaceAll("^[/\\s]+", "");
            if (!suffix.isEmpty()) {
                return suffix;
            }
        }
        return marker.markerName;
    }

    private void showDeleteMarkerDialog(DirectionMarker marker) {
        String displayName = getSimplifiedMarkerName(marker);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除标记")
                .setMessage("确定删除方向标记 \"" + displayName + "\" 吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    if (currentSelectedMarker != null && currentSelectedMarker.id == marker.id) {
                        clearMarkerSelection();
                    }
                    dbHelper.deleteMarker(marker.id);
                    Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show();
                    loadDirectionMarkers();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMarkerLinesDialog(DirectionMarker marker) {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_marker_lines, null);

        TextView markerNameText = dialogView.findViewById(R.id.marker_name_text);
        RecyclerView linesRecyclerView = dialogView.findViewById(R.id.lines_recycler_view);
        TextView emptyText = dialogView.findViewById(R.id.empty_text);

        markerNameText.setText("站点：" + marker.stationName + " | " + marker.lineIds.size() + " 条线路");

        List<DirectionMarker.LineInfo> lines = marker.getLines();
        if (lines.isEmpty()) {
            linesRecyclerView.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
        } else {
            linesRecyclerView.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.GONE);

            MarkerLineAdapter lineAdapter = new MarkerLineAdapter();
            linesRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
            linesRecyclerView.setAdapter(lineAdapter);
            lineAdapter.setData(lines);

            lineAdapter.setOnLineDeleteListener((position, line) -> {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("删除线路")
                        .setMessage("确定从标记中删除线路 \"" + line.lineName + "\" 吗？")
                        .setPositiveButton("删除", (dialog, which) -> {
                            marker.removeLineByIndex(position);
                            if (marker.lineIds.isEmpty()) {
                                dbHelper.deleteMarker(marker.id);
                                Toast.makeText(requireContext(), "标记已为空，已删除", Toast.LENGTH_SHORT).show();
                                if (currentSelectedMarker != null && currentSelectedMarker.id == marker.id) {
                                    clearMarkerSelection();
                                }
                            } else {
                                dbHelper.updateMarker(marker);
                                Toast.makeText(requireContext(), "已删除线路", Toast.LENGTH_SHORT).show();
                            }
                            loadDirectionMarkers();
                            loadMarkerLinesDialog(marker, linesRecyclerView, emptyText);
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        Dialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .setPositiveButton("关闭", null)
                .create();
        dialog.show();
        setDialogFullWidth(dialog);
    }

    private void loadMarkerLinesDialog(DirectionMarker marker, RecyclerView linesRecyclerView, TextView emptyText) {
        List<DirectionMarker.LineInfo> lines = marker.getLines();
        if (lines.isEmpty()) {
            linesRecyclerView.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
        } else {
            linesRecyclerView.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.GONE);
            MarkerLineAdapter lineAdapter = new MarkerLineAdapter();
            linesRecyclerView.setAdapter(lineAdapter);
            lineAdapter.setData(lines);
        }
    }

    /**
     * 彻底清理标记相关状态（不碰界面，退出页面/销毁时也能安全调用）。
     * 批次号一起自增：所有在途的标记/站点回调都会因此作废，不会留下"旧标记"继续播报。
     */
    private void resetMarkerState() {
        currentSelectedMarker = null;
        markerRequestGeneration++;
        stationDataGeneration++;
        markerLineIndex = -1;
        markerLoadingInProgress = false;
        markerListActive = false;
        pendingDeparturePlans.clear();
        markerPlanTimeByLine.clear();
        markerDisplayOrder.clear();
        markerProcessedLines.clear();
        markerRefreshedLines.clear();
        markerStaleLines.clear();
        lineDetailCache.clear();
        announcedVehicles.clear();
        announcedDepartures.clear();
    }

    private void clearMarkerSelection() {
        resetMarkerState();
        adapter.setStaleLineKeys(markerStaleLines);   // 退出标记模式：取消淡化
        if (scheduleHandler != null) {
            scheduleHandler.removeCallbacksAndMessages(null);
        }
        adapter.clearHighlightAndGray();
        adapter.resetAllViewPagersToZero();
        // 立刻回到"全部方向"的展示（否则要等到下一次刷新节拍才恢复，界面会停在标记方向列表上）
        loadStationData();
    }

    /**
     * 点击自定义标记后的取数逻辑。
     * <p>
     * 先把手里的旧状态全部清掉（含批次号自增，让点标记之前发出去的双向数据回调作废），
     * 再用最近一次站点接口的原始数据<b>立刻</b>裁剪出标记方向的单向列表 ——
     * 不等下一轮响应，避免"点了标记还继续显示双向数据"；
     * 随后走原站点接口刷新基础信息，所有方向（含本站接口查不到的跨站台线路）都由
     * {@link #startMarkerVehicleLoading()} 走线路详情自行匹配离标记站点最近的那辆车。
     */
    private void queryWithMarker(DirectionMarker marker) {
        resetMarkerState();
        adapter.setStaleLineKeys(markerStaleLines);
        if (marker.lineIds.isEmpty()) {
            Toast.makeText(requireContext(), "标记中没有线路", Toast.LENGTH_SHORT).show();
            return;
        }
        currentSelectedMarker = marker;
        // 立刻按标记裁剪一次（用手里已有的原始双向数据），界面马上变成标记方向
        if (latestStationResponse != null && !latestStationResponse.isEmpty()) {
            currentBusLineItems = latestStationResponse;
            markerListActive = buildMarkerFilteredList();
        }
        loadStationData();
    }

    private void initRefreshHandler() {
        refreshHandler = new Handler(Looper.getMainLooper());
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentSelectedMarker != null) {
                    // 每轮都复查一次「计划发车提醒」：计划发车时间会被服务端滚成下一班，
                    // 只在取数那一刻比对很容易正好错过整点那一分钟而漏播
                    checkDepartureAnnouncements();
                    if (!markerLoadingInProgress) {
                        // 上一轮线路取数还没结束时不发起新一轮，避免把没取完的线路请求作废
                        refreshWithMarker(currentSelectedMarker);
                    }
                } else {
                    loadStationData();
                }
                refreshHandler.postDelayed(this, REFRESH_INTERVAL);
            }
        };
    }

    /**
     * 标记选中时的刷新：仍走原站点接口刷新基础信息，
     * 车辆部分由 {@link #startMarkerVehicleLoading()} 逐条线路查车辆详情重新匹配最近车辆，
     * 每条线路返回后立即刷新并判断报站/发车预报。
     */
    private void refreshWithMarker(DirectionMarker marker) {
        loadStationData();
    }

    /**
     * 判断 Fragment 是否仍“活着”且可安全操作 UI。
     * 用于拦截已进入/已关闭（或被回收重建）的实例上仍在回调的网络请求，避免 requireActivity()/requireContext() 抛异常闪退。
     */
    private boolean isUiAlive() {
        android.app.Activity a = getActivity();
        return a != null && !a.isFinishing() && isAdded();
    }

    private void runOnUiThreadSafe(Runnable r) {
        android.app.Activity a = getActivity();
        if (a == null || a.isFinishing() || !isAdded()) return;
        a.runOnUiThread(r);
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL);
    }

    @Override
    public void onPause() {
        super.onPause();
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 退出页面：彻底清理旧标记与在途请求。
        // 不清的话，旧标记的取数/播报回调可能还在跑（甚至退出后继续播报），
        // 下次进入页面也容易残留上一次的选中状态。
        resetMarkerState();
        currentBusLineItems = null;
        latestStationResponse = null;
        if (refreshHandler != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
        }
        if (scheduleHandler != null) {
            scheduleHandler.removeCallbacksAndMessages(null);
        }
        if (busApiClient != null) {
            busApiClient.cancelAllRequests();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        resetMarkerState();
        if (refreshHandler != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
        }
        if (scheduleHandler != null) {
            scheduleHandler.removeCallbacksAndMessages(null);
        }
    }

    /**
     * 加载原站点接口数据（双向：up/down）。这是所有取数的唯一来源。
     * 若当前已选中自定义标记，会在数据就绪后切到对应方向并触发按距离阈值的报站。
     */
    public void loadStationData() {
        final int stationGen = ++stationDataGeneration;   // 新一轮：此前发出去的回调作废
        try {
            busApiClient.queryStationInfo(currentStationName, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.StationInfoResponse response) {
                    try {
                        if (response == null || response.data == null) {
                            Log.e("-BusInfo-", "站点信息为空");
                            return;
                        }
                        if (!isUiAlive()) return;
                        if (stationGen != stationDataGeneration) return;   // 已有更新的一轮，结果作废
                        List<BusApiClient.StationLineInfo> freshItems = response.data;
                        latestStationResponse = freshItems;   // 原始双向数据：点标记时可以立刻裁剪

                        if (currentSelectedMarker != null && refreshMarkerItems(freshItems)) {
                            // 标记选中：列表就是标记方向的单向列表，接着按“线路车辆详情”取最近车辆
                            startMarkerVehicleLoading();
                            return;
                        }

                        currentBusLineItems = freshItems;
                        adapter.setData(currentBusLineItems);

                        StringBuilder lineIdsBuilder = new StringBuilder();
                        StringBuilder stationIdsBuilder = new StringBuilder();

                        for (BusApiClient.StationLineInfo item : currentBusLineItems) {
                            if (item.up != null) {
                                appendIds(lineIdsBuilder, stationIdsBuilder, item.up.lineId, item.up.stationId);
                            }
                            if (item.down != null) {
                                appendIds(lineIdsBuilder, stationIdsBuilder, item.down.lineId, item.down.stationId);
                            }
                        }

                        if (lineIdsBuilder.length() > 0) {
                            fetchVehicleDynamicData(lineIdsBuilder.toString(), stationIdsBuilder.toString(),
                                    currentBusLineItems, stationGen);
                        }
                    } catch (Exception e) {
                        Log.e("-BusInfo-", "处理站点数据失败", e);
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e("-BusInfo-", "查询站点信息失败: " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            Log.e("-BusInfo-", "加载站点数据异常", e);
        }
    }

    private void appendIds(StringBuilder lineIds, StringBuilder stationIds, String lineId, String stationId) {
        if (lineIds.length() > 0) {
            lineIds.append(",");
            stationIds.append(",");
        }
        lineIds.append(lineId);
        stationIds.append(stationId);
    }

    /**
     * 用站点接口返回的全部 lineId/stationId 拉取车辆动态，并合并进双向数据。
     * 未选中标记时的主链路；标记模式下仅作为线路详情不可用时的兜底。
     *
     * @param stationGen 本轮站点取数的批次号：中途选了标记 / 来了更新的一轮时，本轮回调直接作废
     */
    private void fetchVehicleDynamicData(String lineIds, String stationIds,
                                        List<BusApiClient.StationLineInfo> busLineItems,
                                        int stationGen) {
        fetchVehicleDynamicData(lineIds, stationIds, busLineItems, () -> {
            if (stationGen != stationDataGeneration) return;   // 这次取数已过期：不刷界面、不播报
            fetchPlanTimeForEmptyLines(busLineItems, stationGen);
        });
    }

    /**
     * @param onFinished 取完车辆后的收尾动作（必传）：非标记链路传"校验批次号 → 取计划发车时间"，
     *                   标记模式传自己的完成回调，由标记流程统一收尾，避免提前刷新导致数据被覆盖。
     */
    private void fetchVehicleDynamicData(String lineIds, String stationIds,
                                         List<BusApiClient.StationLineInfo> busLineItems,
                                         Runnable onFinished) {
        if (onFinished == null) {
            Log.w("-BusInfo-", "车辆动态缺少收尾回调，忽略本次结果");
            return;
        }
        final Runnable finishAction = onFinished;
        try {
            busApiClient.queryStationVehicleDynamic(lineIds, stationIds, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.StationVehicleDynamicResponse response) {
                    try {
                        if (response == null || response.data == null) {
                            Log.w("-BusInfo-", "车辆动态数据为空");
                            runOnUiThreadSafe(() -> {
                                if (isUiAlive()) finishAction.run();
                            });
                            return;
                        }
                        for (BusApiClient.StationVehicleInfo vehicleInfo : response.data) {
                            if (vehicleInfo == null) continue;
                            for (BusApiClient.StationLineInfo lineInfo : busLineItems) {
                                if (lineInfo.up != null && lineInfo.up.lineId.equals(vehicleInfo.lineId)
                                        && lineInfo.up.stationId != null && lineInfo.up.stationId.equals(vehicleInfo.stationId)) {
                                    lineInfo.up.vehicleInfo = vehicleInfo;
                                }
                                if (lineInfo.down != null && lineInfo.down.lineId.equals(vehicleInfo.lineId)
                                        && lineInfo.down.stationId != null && lineInfo.down.stationId.equals(vehicleInfo.stationId)) {
                                    lineInfo.down.vehicleInfo = vehicleInfo;
                                }
                            }
                        }
                        runOnUiThreadSafe(() -> {
                            if (isUiAlive()) finishAction.run();
                        });
                    } catch (Exception e) {
                        Log.e("-BusInfo-", "处理车辆动态数据失败", e);
                        runOnUiThreadSafe(() -> {
                            if (isUiAlive()) finishAction.run();
                        });
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e("-BusInfo-", "获取车辆动态数据失败: " + e.getMessage(), e);
                    runOnUiThreadSafe(() -> {
                        if (isUiAlive()) finishAction.run();
                    });
                }
            });
        } catch (Exception e) {
            Log.e("-BusInfo-", "请求车辆动态数据异常", e);
            runOnUiThreadSafe(() -> {
                if (isUiAlive()) finishAction.run();
            });
        }
    }

    private void fetchPlanTimeForEmptyLines(List<BusApiClient.StationLineInfo> busLineItems, int stationGen) {
        try {
            Set<String> lineIdsWithoutVehicle = new HashSet<>();
            for (BusApiClient.StationLineInfo lineInfo : busLineItems) {
                if (lineInfo.up != null && lineInfo.up.vehicleInfo == null) {
                    lineIdsWithoutVehicle.add(lineInfo.up.lineId);
                }
                if (lineInfo.down != null && lineInfo.down.vehicleInfo == null) {
                    lineIdsWithoutVehicle.add(lineInfo.down.lineId);
                }
            }

            if (!lineIdsWithoutVehicle.isEmpty()) {
                String lineIdsStr = String.join(",", lineIdsWithoutVehicle);

                busApiClient.queryBusVehiclePlan(lineIdsStr, new BusApiClient.ApiCallback<>() {
                    @Override
                    public void onSuccess(BusApiClient.BusVehiclePlanResponse response) {
                        runOnUiThreadSafe(() -> {
                            if (!isUiAlive()) return;
                            if (stationGen != stationDataGeneration) return;   // 这轮取数已过期（如中途选了标记）：丢弃
                            try {
                                if (response == null || response.data == null) {
                                    Log.w("-BusInfo-", "计划发车时间数据为空");
                                    return;
                                }
                                for (BusApiClient.BusPlanTime planTime : response.data) {
                                    if (planTime == null) continue;
                                    for (BusApiClient.StationLineInfo lineInfo : busLineItems) {
                                        if (lineInfo.up != null && lineInfo.up.lineId.equals(planTime.lineId)) {
                                            lineInfo.up.planTime = planTime.startTime;
                                        }
                                        if (lineInfo.down != null && lineInfo.down.lineId.equals(planTime.lineId)) {
                                            lineInfo.down.planTime = planTime.startTime;
                                        }
                                    }
                                }
                                adapter.setData(busLineItems);
                                // 只有当前展示的确实是"标记方向的单向列表"才按标记播报；
                                // 标记方向没匹配到、退回全量双向展示时不能播报（否则会按错方向播错站）
                                if (currentSelectedMarker != null && markerListActive) {
                                    announceMarkerItems(busLineItems);
                                }
                            } catch (Exception e) {
                                Log.e("-BusInfo-", "处理计划发车时间失败", e);
                            }
                        });
                    }

                    @Override
                    public void onError(BusApiClient.BusApiException e) {
                        Log.e("-BusInfo-", "获取计划发车时间失败: " + e.getMessage(), e);
                    }
                });
            } else {
                runOnUiThreadSafe(() -> {
                    if (!isUiAlive()) return;
                    if (stationGen != stationDataGeneration) return;   // 这轮取数已过期：丢弃
                    adapter.setData(busLineItems);
                    if (currentSelectedMarker != null && markerListActive) {
                        announceMarkerItems(busLineItems);
                    }
                });
            }
        } catch (Exception e) {
            Log.e("-BusInfo-", "查询计划发车时间异常", e);
        }
    }

    /**
     * 标记模式下把站点接口的最新数据合并进当前列表（刷新节拍每轮调用一次）。
     * <p>
     * 列表已存在时<b>只更新展示用的静态字段</b>（线路名/起终点/首末班/类型），
     * 上一轮的车辆与发车时间<b>保留</b>下来继续展示，列表对象和顺序都不重建：
     * 一轮开始时卡片仍是上一轮内容（由 {@link #startMarkerVehicleLoading()} 统一置为淡化"待更新"态），
     * 各线路本轮数据回来后逐条恢复，避免每轮整屏闪一次"暂无车辆信息"。
     * 首次进入标记模式（列表为空）或标记方向在接口里一个都没对上时，退回
     * {@link #buildMarkerFilteredList()} 重建（内部按标记方向裁剪，失败会提示并显示全部方向）。
     *
     * @return true 表示当前列表就是标记方向的单向列表
     */
    private boolean refreshMarkerItems(List<BusApiClient.StationLineInfo> freshItems) {
        if (currentSelectedMarker == null) return false;
        if (!markerListActive || currentBusLineItems == null || currentBusLineItems.isEmpty()) {
            // 首次进入该标记 / 上一次没裁出标记方向：按标记方向重新裁剪出单向列表
            currentBusLineItems = freshItems;
            markerListActive = buildMarkerFilteredList();
            return markerListActive;
        }

        // 新数据按 (lineId, stationId) 建索引，便于逐条把静态字段搬到旧对象上
        Map<String, BusApiClient.LineDirection> freshDirections = new HashMap<>();
        Map<String, String> freshNames = new HashMap<>();
        if (freshItems != null) {
            for (BusApiClient.StationLineInfo item : freshItems) {
                if (item == null) continue;
                indexFreshDirection(item.up, item.lineName, freshDirections, freshNames);
                indexFreshDirection(item.down, item.lineName, freshDirections, freshNames);
            }
        }

        int matched = 0;
        for (BusApiClient.StationLineInfo item : currentBusLineItems) {
            BusApiClient.LineDirection dir = markerDirectionOf(item);
            if (dir == null || dir.lineId == null) continue;
            String key = dir.lineId + "_" + dir.stationId;
            BusApiClient.LineDirection fresh = freshDirections.get(key);
            if (fresh == null) continue;   // 跨站台线路等接口里没有的方向：保持原样，车辆仍由线路详情匹配
            matched++;
            copyMarkerDisplayFields(fresh, dir);
            String freshName = freshNames.get(key);
            if (freshName != null) item.lineName = freshName;
        }

        if (matched == 0) {
            // 标记方向在接口数据里一个都没对上（标记里存的 id 可能已失效）：退回重建逻辑
            currentBusLineItems = freshItems;
            markerListActive = buildMarkerFilteredList();
            return markerListActive;
        }
        return true;
    }

    private void indexFreshDirection(BusApiClient.LineDirection dir, String lineName,
                                     Map<String, BusApiClient.LineDirection> outDirections,
                                     Map<String, String> outNames) {
        if (dir == null || dir.lineId == null || dir.stationId == null) return;
        String key = dir.lineId + "_" + dir.stationId;
        outDirections.put(key, dir);
        if (lineName != null) outNames.put(key, lineName);
    }

    /**
     * 只把"展示用"的静态字段从新数据搬到旧对象上。
     * vehicleInfo / planTime 故意不搬：它们由线路详情与批量计划接口在本轮重新匹配，
     * 这样合并的瞬间卡片不会变成"暂无车辆信息"（旧值先留着，数据回来再替换）。
     */
    private void copyMarkerDisplayFields(BusApiClient.LineDirection from, BusApiClient.LineDirection to) {
        if (from == null || to == null) return;
        if (from.startStation != null) to.startStation = from.startStation;
        if (from.endStation != null) to.endStation = from.endStation;
        if (from.departureTime != null) to.departureTime = from.departureTime;
        if (from.collectTime != null) to.collectTime = from.collectTime;
        if (from.lineTypeName != null) to.lineTypeName = from.lineTypeName;
        to.lineType = from.lineType;
        to.price = from.price;
    }

    /**
     * 标记选中时，从原接口返回的双向数据（currentBusLineItems）里按标记方向逐条匹配，
     * 只保留对应方向，构建“单向”展示列表。数据来自原接口刷新、不再单独请求；
     * 标记是单向的，因此每个线路卡片只显示匹配到的那一个方向（up/down 只留其一）。
     *
     * @return true 表示已构建出标记方向的单向列表，false 表示没匹配到任何方向、已退回全量展示
     */
    private boolean buildMarkerFilteredList() {
        if (currentSelectedMarker == null || currentBusLineItems == null) return false;
        DirectionMarker marker = currentSelectedMarker;

        List<BusApiClient.StationLineInfo> filtered = new ArrayList<>();
        for (int i = 0; i < marker.lineIds.size() && i < marker.stationIds.size(); i++) {
            String lineId = marker.lineIds.get(i);
            String stationId = marker.stationIds.get(i);

            BusApiClient.LineDirection matchedDir = null;
            BusApiClient.StationLineInfo matchedFull = null;
            for (BusApiClient.StationLineInfo fullItem : currentBusLineItems) {
                if (fullItem.up != null && lineId.equals(fullItem.up.lineId)
                        && stationId.equals(fullItem.up.stationId)) {
                    matchedDir = fullItem.up;
                    matchedFull = fullItem;
                    break;
                }
                if (fullItem.down != null && lineId.equals(fullItem.down.lineId)
                        && stationId.equals(fullItem.down.stationId)) {
                    matchedDir = fullItem.down;
                    matchedFull = fullItem;
                    break;
                }
            }
            if (matchedDir == null) {
                // 跨站台线路：当前站接口数据里没有该方向（它属于标记里的其它站台），
                // 用标记保存的字段构造展示项并加入列表。它的车辆与其它线路走同一套逻辑：
                // 由线路详情 + 该线路在线车辆匹配出离该站台最近的一辆（见 startMarkerVehicleLoading）。
                BusApiClient.LineDirection crossDir = new BusApiClient.LineDirection();
                crossDir.lineId = lineId;
                crossDir.stationId = stationId;
                String crossName = marker.getLineName(i);
                crossDir.lineName = crossName;
                crossDir.startStation = marker.getStartStation(i);
                crossDir.endStation = marker.getEndStation(i);
                crossDir.departureTime = marker.getDepartureTime(i);
                crossDir.collectTime = marker.getCollectTime(i);
                if (i < marker.lineTypes.size()) {
                    crossDir.lineTypeName = marker.lineTypes.get(i);
                }
                BusApiClient.StationLineInfo crossItem = new BusApiClient.StationLineInfo();
                crossItem.lineName = crossName;
                crossItem.up = crossDir; // 单向展示，down 留空
                filtered.add(crossItem);
                continue;
            }

            // JSON 里 lineName 在 StationLineInfo 父级，up/down 子对象通常为空，需补上，否则卡片显示 null
            String reliableName = (matchedFull != null && matchedFull.lineName != null)
                    ? matchedFull.lineName : marker.getLineName(i);
            matchedDir.lineName = reliableName;
            if (matchedDir.startStation == null) matchedDir.startStation = marker.getStartStation(i);
            if (matchedDir.endStation == null) matchedDir.endStation = marker.getEndStation(i);

            BusApiClient.StationLineInfo filteredItem = new BusApiClient.StationLineInfo();
            filteredItem.lineName = reliableName;
            filteredItem.up = matchedDir; // 单向展示，down 留空
            filtered.add(filteredItem);
        }

        if (filtered.isEmpty()) {
            // 原接口双向数据里没匹配到该标记方向（多半是标记里存的 id 已失效），退回全量并提示
            Toast.makeText(requireContext(), "原接口未找到该标记方向，已显示全部方向", Toast.LENGTH_SHORT).show();
            adapter.setData(currentBusLineItems);
            return false;
        }

        // 先按上一轮排好的名次摆放：本轮逐条查询的顺序也取自这个顺序（见 startMarkerVehicleLoading）
        sortByRememberedMarkerOrder(filtered);
        currentBusLineItems = filtered;
        adapter.setData(filtered);
        return true;
    }

    /** 按上一轮记录的名次重排（没记录过的排在后面，保持原有相对顺序；排序稳定，不会抖动） */
    private void sortByRememberedMarkerOrder(List<BusApiClient.StationLineInfo> items) {
        if (items == null || items.size() < 2 || markerDisplayOrder.isEmpty()) return;
        items.sort((a, b) -> Integer.compare(rememberedOrderIndex(a), rememberedOrderIndex(b)));
    }

    /** 数据在上一轮名次里的下标；没有记录时返回一个很大的值（排到最后） */
    private int rememberedOrderIndex(BusApiClient.StationLineInfo item) {
        BusApiClient.LineDirection dir = markerDirectionOf(item);
        if (dir == null || dir.lineId == null) return Integer.MAX_VALUE;
        int index = markerDisplayOrder.indexOf(dir.lineId + "_" + dir.stationId);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /**
     * 标记模式下每张卡片只展示一个方向（{@link #buildMarkerFilteredList()} 里 up/down 只留其一），
     * 这里取出该卡片当前展示的方向；都没有则返回 null。
     */
    private BusApiClient.LineDirection markerDirectionOf(BusApiClient.StationLineInfo item) {
        if (item == null) return null;
        return item.up != null ? item.up : item.down;
    }

    /**
     * 标记选中后的车辆取数：逐条线路依次处理。
     * <p>
     * 每条线路（含跨站台线路）都走「线路详情 → 该线路在线车辆 → 匹配离标记站点最近的一辆」，
     * 该线路的数据一拿到就立刻刷新它自己的卡片、立刻判断报站与发车预报，然后才继续下一条线路；
     * 不再等所有线路都取完才统一刷新（那会把报站整体拖后，甚至被下一轮刷新作废导致漏播）。
     * 单条线路超过 {@link #MARKER_LINE_TIMEOUT_MS} 仍未返回时直接跳过，避免一条慢线路卡住整轮刷新。
     */
    private void startMarkerVehicleLoading() {
        markerRequestGeneration++;
        markerLineIndex = -1;
        markerPlanTimeByLine.clear();
        markerProcessedLines.clear();
        markerRefreshedLines.clear();

        List<BusApiClient.StationLineInfo> items = currentBusLineItems;
        if (items == null || items.isEmpty()) {
            markerLoadingInProgress = false;
            return;
        }

        List<BusApiClient.LineDirection> dirs = new ArrayList<>();
        for (BusApiClient.StationLineInfo item : items) {
            BusApiClient.LineDirection dir = item.up;
            if (dir == null || dir.lineId == null) continue;
            dirs.add(dir);
        }

        if (dirs.isEmpty()) {
            markerLoadingInProgress = false;
            return;
        }

        // 新一轮开始：上一轮的车辆/发车时间先留着继续显示，只把整表置为淡化"待更新"，
        // 哪条线路本轮的车辆数据回来就恢复它自己（见 onMarkerLineReady）——
        // 不再像以前那样先清空车辆数据，否则一轮开始整屏会闪成"暂无车辆信息"
        markAllMarkerLinesStale(dirs);
        markerLoadingInProgress = true;
        // 计划发车时间用批量接口一次取回（与逐条车辆取数并行），不再每条线路各发一次
        fetchMarkerPlanTimesBatch(dirs, markerRequestGeneration);
        advanceMarkerLine(dirs, 0, markerRequestGeneration);
    }

    /** 新一轮开始：所有线路标记为"仍是上一轮数据"（卡片淡化），各自本轮数据回来后再逐条恢复 */
    private void markAllMarkerLinesStale(List<BusApiClient.LineDirection> dirs) {
        markerStaleLines.clear();
        for (BusApiClient.LineDirection dir : dirs) {
            if (dir == null || dir.lineId == null) continue;
            markerStaleLines.add(markerLineKey(dir));
        }
        adapter.setStaleLineKeys(markerStaleLines);
        adapter.notifyAllLinesChanged();
    }

    /** 逐条线路推进：处理第 index 条线路，完成后自动接着处理下一条 */
    private void advanceMarkerLine(List<BusApiClient.LineDirection> dirs, int index, int gen) {
        if (gen != markerRequestGeneration || !isUiAlive()) return;
        if (index >= dirs.size()) {
            markerLoadingInProgress = false; // 本轮线路全部处理完毕
            return;
        }
        markerLineIndex = index;
        final BusApiClient.LineDirection dir = dirs.get(index);

        // 超时兜底：该线路长时间无响应时，用现有数据收尾并推进到下一条
        scheduleHandler.postDelayed(() -> {
            if (gen != markerRequestGeneration || markerLineIndex != index) return;
            markerLineIndex = index + 1;
            onMarkerLineReady(dir);
            advanceMarkerLine(dirs, index + 1, gen);
        }, MARKER_LINE_TIMEOUT_MS);

        fetchNearestVehicleByLineDetail(dir, gen, () -> {
            if (gen != markerRequestGeneration) return;
            if (markerLineIndex == index) {
                markerLineIndex = index + 1;
                onMarkerLineReady(dir);
                advanceMarkerLine(dirs, index + 1, gen);
            } else {
                // 该线路此前已因超时被跳过、数据现在才回来：补一次刷新与播报判断，不再推进
                onMarkerLineReady(dir);
            }
        });
    }

    /**
     * 单条线路数据就绪：先按最新名次重排列表（带动画）并刷新这条线路的卡片，
     * 再判断「进站报站」；只有这条线路没有在线车辆时才判断「计划发车提醒」。
     */
    private void onMarkerLineReady(BusApiClient.LineDirection dir) {
        if (currentSelectedMarker == null || dir == null || !isUiAlive()) return;
        boolean hasVehicle = dir.vehicleInfo != null;
        String lineKey = markerLineKey(dir);
        // 只有"本轮车辆数据确实取到了"才认定这条线路的"有车/无车"是确定的：
        // 取数失败或超时的情况下卡片保持淡化（数据显示的还是上一轮），也不拿旧数据去判断播报
        boolean vehicleStateFresh = markerRefreshedLines.contains(lineKey);
        if (vehicleStateFresh) {
            markerProcessedLines.add(lineKey);
            markerStaleLines.remove(lineKey);          // 取消淡化（下面刷新卡片时生效）
        }
        // 本轮批量取回的计划发车时间：只对"没有在线车辆"的线路生效
        String planFromBatch = dir.lineId == null ? null : markerPlanTimeByLine.get(dir.lineId);
        if (!hasVehicle && planFromBatch != null) {
            dir.planTime = planFromBatch;
        }
        applyMarkerOrderAndRefresh(dir);
        if (!vehicleStateFresh) return;
        announceForMarkerLine(dir);
        // 有最近车辆不播发车广播；本轮批量数据还没到也不在这里判断（到位后会统一判断）
        if (!hasVehicle && planFromBatch != null) {
            announceDepartureForMarkerLine(dir);
        }
    }

    /** 线路键：lineId_stationId（与播报去重键同源） */
    private String markerLineKey(BusApiClient.LineDirection dir) {
        return dir == null ? "" : dir.lineId + "_" + dir.stationId;
    }

    /**
     * 批量取本轮所有线路的计划发车时间。
     * /bus/vehicle/plan 支持逗号分隔的多个 lineId，一次请求就能把整屏线路的
     * 「下一班发车时间」全部拿回来，线路多时能省下 N-1 次请求（原来每条无车线路各发一次）。
     */
    private void fetchMarkerPlanTimesBatch(List<BusApiClient.LineDirection> dirs, int gen) {
        if (dirs == null || dirs.isEmpty()) return;
        Set<String> lineIds = new LinkedHashSet<>();   // 去重：同一线路的多个方向只查一次
        for (BusApiClient.LineDirection dir : dirs) {
            if (dir == null || dir.lineId == null) continue;
            lineIds.add(dir.lineId);
        }
        if (lineIds.isEmpty()) return;
        final String lineIdsParam = String.join(",", lineIds);
        try {
            busApiClient.queryBusVehiclePlan(lineIdsParam, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.BusVehiclePlanResponse response) {
                    if (gen != markerRequestGeneration) return;   // 标记已切换 / 已开始新一轮
                    if (!isUiAlive()) return;
                    applyMarkerPlanTimes(response);
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e("-BusInfo-", "批量获取计划发车时间失败: " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            Log.e("-BusInfo-", "批量查询计划发车时间异常", e);
        }
    }

    /**
     * 批量计划发车时间返回：写入各路线路。无车线路据此显示「下一班发车时间」并判断发车提醒；
     * 有车线路只留数据（卡片显示的是车辆信息，也不播发车广播）。最后统一重排（发车时间参与名次）。
     */
    private void applyMarkerPlanTimes(BusApiClient.BusVehiclePlanResponse response) {
        if (currentSelectedMarker == null || !isUiAlive()) return;
        if (response == null || response.data == null) {
            Log.w("-BusInfo-", "批量计划发车时间数据为空");
            return;
        }
        for (BusApiClient.BusPlanTime planTime : response.data) {
            if (planTime == null || planTime.lineId == null) continue;
            String startTime = normalizePlanTime(planTime.startTime);
            if (startTime == null) continue;
            markerPlanTimeByLine.put(planTime.lineId, startTime);
        }

        List<BusApiClient.StationLineInfo> items = currentBusLineItems;
        if (items == null) return;
        List<BusApiClient.LineDirection> noVehicleDirs = new ArrayList<>();
        for (BusApiClient.StationLineInfo item : items) {
            BusApiClient.LineDirection dir = markerDirectionOf(item);
            if (dir == null || dir.lineId == null) continue;
            String plan = markerPlanTimeByLine.get(dir.lineId);
            if (plan == null) continue;
            dir.planTime = plan;
            if (dir.vehicleInfo == null) noVehicleDirs.add(dir);
        }

        applyMarkerOrderAndRefresh(null);              // 发车时间影响无车线路的名次：重排 + 动画
        for (BusApiClient.LineDirection dir : noVehicleDirs) {
            notifyMarkerLineChanged(dir);              // 刷新该卡片的「下一班发车时间」
            announceDepartureForMarkerLine(dir);
        }
    }

    /**
     * 标记模式「数据 → 界面」的统一出口：
     * 1) 按最新名次规则（有车按距离由近到远、无车按发车时间由早到晚）重排列表，
     *    用移动动画表现名次变化（{@link BusStationAdapter#applyOrder}）；
     * 2) 刷新指定线路的卡片内容（传 null 表示本次不单独刷新某条）。
     * 同时记下当前顺序，供下一轮按新顺序逐条查询。
     */
    private void applyMarkerOrderAndRefresh(BusApiClient.LineDirection changedDir) {
        List<BusApiClient.StationLineInfo> items = currentBusLineItems;
        if (items == null || items.isEmpty()) return;
        List<BusApiClient.StationLineInfo> sorted = new ArrayList<>(items);
        Collections.sort(sorted, this::compareMarkerItems);
        adapter.applyOrder(sorted);                    // 列表顺序即变为 sorted（内含移动动画）
        if (changedDir != null) {
            notifyMarkerLineChanged(changedDir);       // 名次变化后按新位置刷新这条卡片
        }
        rememberMarkerOrder();
    }

    /** 记录当前列表顺序（lineId_stationId），下一轮就按这个新顺序逐条查询 */
    private void rememberMarkerOrder() {
        markerDisplayOrder.clear();
        List<BusApiClient.StationLineInfo> items = currentBusLineItems;
        if (items == null) return;
        for (BusApiClient.StationLineInfo item : items) {
            BusApiClient.LineDirection dir = markerDirectionOf(item);
            if (dir == null || dir.lineId == null) continue;
            markerDisplayOrder.add(dir.lineId + "_" + dir.stationId);
        }
    }

    /**
     * 卡片名次规则：
     * 1) 有最近车辆的排前面（正在跑的车比"还没发车"更值得先看）；
     * 2) 有车的按距离由近到远（距离未知排最后；距离 0 且是下一班表示已到站，按最近处理）；
     * 3) 无车的按计划发车时间由早到晚（没有发车时间排最后）；
     * 4) 完全并列时沿用上一轮名次，保证刷新过程中不会来回抖动。
     */
    private int compareMarkerItems(BusApiClient.StationLineInfo a, BusApiClient.StationLineInfo b) {
        BusApiClient.LineDirection da = markerDirectionOf(a);
        BusApiClient.LineDirection db = markerDirectionOf(b);
        boolean hasVehicleA = da != null && da.vehicleInfo != null;
        boolean hasVehicleB = db != null && db.vehicleInfo != null;
        if (hasVehicleA != hasVehicleB) return hasVehicleA ? -1 : 1;

        if (hasVehicleA) {
            int distanceA = markerSortDistance(da.vehicleInfo);
            int distanceB = markerSortDistance(db.vehicleInfo);
            if (distanceA != distanceB) return Integer.compare(distanceA, distanceB);
        } else {
            int timeA = markerSortDepartureMinutes(da);
            int timeB = markerSortDepartureMinutes(db);
            if (timeA != timeB) return Integer.compare(timeA, timeB);
        }
        return Integer.compare(rememberedOrderIndex(a), rememberedOrderIndex(b));
    }

    /** 排序用距离：未知按最大值（排最后）；distance==0 且为下一班（nextNumber==0）表示已到站，按最近处理 */
    private int markerSortDistance(BusApiClient.StationVehicleInfo vehicleInfo) {
        if (vehicleInfo == null) return Integer.MAX_VALUE;
        if (vehicleInfo.distance > 0) return vehicleInfo.distance;
        return vehicleInfo.nextNumber == 0 ? 0 : Integer.MAX_VALUE;
    }

    /** 排序用计划发车时间（当天分钟数）：没有/解析不出按最大值（排最后） */
    private int markerSortDepartureMinutes(BusApiClient.LineDirection dir) {
        if (dir == null) return Integer.MAX_VALUE;
        int[] hourMinute = TTSUtils.parseHourMinute(dir.planTime);
        return hourMinute == null ? Integer.MAX_VALUE : hourMinute[0] * 60 + hourMinute[1];
    }

    /** 在当前展示列表里按 (lineId, stationId) 定位方向 */
    private BusApiClient.LineDirection findMarkerDirection(String lineId, String stationId) {
        List<BusApiClient.StationLineInfo> items = currentBusLineItems;
        if (items == null || lineId == null) return null;
        for (BusApiClient.StationLineInfo item : items) {
            if (item == null) continue;
            if (isSameDirection(item.up, lineId, stationId)) return item.up;
            if (isSameDirection(item.down, lineId, stationId)) return item.down;
        }
        return null;
    }

    private boolean isSameDirection(BusApiClient.LineDirection dir, String lineId, String stationId) {
        if (dir == null || !lineId.equals(dir.lineId)) return false;
        return stationId == null || stationId.equals(dir.stationId);
    }

    /** 只刷新单条线路卡片，避免整表重绑导致列表闪烁、方向翻页被重置 */
    private void notifyMarkerLineChanged(BusApiClient.LineDirection dir) {
        List<BusApiClient.StationLineInfo> items = currentBusLineItems;
        if (items == null || dir == null) return;
        for (int i = 0; i < items.size(); i++) {
            BusApiClient.StationLineInfo item = items.get(i);
            if (item != null && (item.up == dir || item.down == dir)) {
                adapter.notifyLineChanged(i);
                return;
            }
        }
    }

    /** 按 (lineId, stationId) 走站点接口取车辆（跨站台线路 / 线路详情不可用时的兜底） */
    private void fetchVehicleDynamicForDirections(List<BusApiClient.LineDirection> dirs, int gen,
                                                  Runnable onDone) {
        StringBuilder lineIds = new StringBuilder();
        StringBuilder stationIds = new StringBuilder();
        // 记下请求前的车辆对象：兜底接口没返回这条线路的车时，要把"上一轮的旧车"清掉，
        // 否则保留了旧数据的卡片会一直挂着一辆已经不存在的车
        final Map<BusApiClient.LineDirection, BusApiClient.StationVehicleInfo> previous =
                new IdentityHashMap<>();
        for (BusApiClient.LineDirection dir : dirs) {
            if (dir == null) continue;
            previous.put(dir, dir.vehicleInfo);
            if (dir.lineId == null || dir.stationId == null) continue;
            appendIds(lineIds, stationIds, dir.lineId, dir.stationId);
        }
        if (lineIds.length() == 0) {
            onDone.run();
            return;
        }
        fetchVehicleDynamicData(lineIds.toString(), stationIds.toString(), currentBusLineItems,
                () -> {
                    if (gen != markerRequestGeneration) return;
                    for (Map.Entry<BusApiClient.LineDirection, BusApiClient.StationVehicleInfo> entry
                            : previous.entrySet()) {
                        BusApiClient.StationVehicleInfo old = entry.getValue();
                        if (old != null && entry.getKey().vehicleInfo == old) {
                            entry.getKey().vehicleInfo = null;   // 本轮没查到 → 清掉旧车
                        }
                    }
                    onDone.run();
                });
    }

    /**
     * 点击标记后的核心改动：查询该方向的线路详情（拿到站点顺序），
     * 再查这条线上正在跑的所有车辆，挑出还没过站、离标记站点最近的那辆。
     */
    private void fetchNearestVehicleByLineDetail(BusApiClient.LineDirection dir, int gen, Runnable onDone) {
        BusApiClient.BusLineDetailData cached = lineDetailCache.get(dir.lineName);
        if (cached != null) {
            fetchLineVehicleDetail(dir, cached, gen, onDone);
            return;
        }
        try {
            busApiClient.queryBusLineDetail(dir.lineName, 0, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.BusLineDetailResponse response) {
                    if (gen != markerRequestGeneration) return;
                    if (response == null || response.data == null) {
                        Log.w("-BusInfo-", "线路详情为空：" + dir.lineName);
                        fetchVehicleDynamicForDirections(Collections.singletonList(dir), gen, onDone);
                        return;
                    }
                    lineDetailCache.put(dir.lineName, response.data);
                    fetchLineVehicleDetail(dir, response.data, gen, onDone);
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e("-BusInfo-", "查询线路详情失败: " + e.getMessage(), e);
                    fetchVehicleDynamicForDirections(Collections.singletonList(dir), gen, onDone);
                }
            });
        } catch (Exception e) {
            Log.e("-BusInfo-", "查询线路详情异常", e);
            fetchVehicleDynamicForDirections(Collections.singletonList(dir), gen, onDone);
        }
    }

    private void fetchLineVehicleDetail(BusApiClient.LineDirection dir,
                                        BusApiClient.BusLineDetailData detail,
                                        int gen,
                                        Runnable onDone) {
        BusApiClient.BusLineDirection lineDir = resolveLineDirection(detail, dir);
        int targetIndex = lineDir == null ? -1 : findTargetStationIndex(lineDir.stationList, dir.stationId);
        if (lineDir == null || targetIndex < 0) {
            // 线路详情里找不到该方向或站点（id 变更等），退回站点接口的老逻辑
            Log.w("-BusInfo-", "线路详情未匹配到标记站点：" + dir.lineName + "/" + dir.stationId);
            fetchVehicleDynamicForDirections(Collections.singletonList(dir), gen, onDone);
            return;
        }

        List<BusApiClient.BusLineStation> stations = lineDir.stationList;
        final int target = targetIndex;
        try {
            busApiClient.queryBusVehicleDynamic(dir.lineId, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.BusVehicleDynamicResponse response) {
                    if (gen != markerRequestGeneration) return;
                    try {
                        if (response == null || response.data == null || response.data.list == null) {
                            // 接口没给出车辆列表（取数异常）：保留上一轮数据继续展示，等下一轮再来
                            Log.w("-BusInfo-", "线路车辆数据为空：" + dir.lineName);
                            onDone.run();
                            return;
                        }
                        // 本轮匹配结果直接覆盖（查不到车就置空）：旧数据只在等待期间显示，
                        // 不能让上一轮的旧车一直挂在卡片上（如该车已出站/已跑完）
                        dir.vehicleInfo = findNearestVehicleToStation(
                                response.data.list, stations, target, dir);
                        markerRefreshedLines.add(markerLineKey(dir));   // 本轮车辆数据有效：这条线路可以取消淡化
                    } catch (Exception e) {
                        Log.e("-BusInfo-", "匹配最近车辆失败", e);
                    }
                    onDone.run();
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e("-BusInfo-", "查询线路车辆详情失败: " + e.getMessage(), e);
                    onDone.run();
                }
            });
        } catch (Exception e) {
            Log.e("-BusInfo-", "查询线路车辆详情异常", e);
            onDone.run();
        }
    }

    /** 按方向 lineId（必要时退回起终点）在上下行里定位该方向 */
    private BusApiClient.BusLineDirection resolveLineDirection(BusApiClient.BusLineDetailData detail,
                                                               BusApiClient.LineDirection dir) {
        if (detail == null) return null;
        if (dir.lineId != null) {
            if (detail.up != null && dir.lineId.equals(detail.up.id)) return detail.up;
            if (detail.down != null && dir.lineId.equals(detail.down.id)) return detail.down;
        }
        if (dir.startStation != null && dir.endStation != null) {
            if (detail.up != null && dir.startStation.equals(detail.up.startStation)
                    && dir.endStation.equals(detail.up.endStation)) return detail.up;
            if (detail.down != null && dir.startStation.equals(detail.down.startStation)
                    && dir.endStation.equals(detail.down.endStation)) return detail.down;
        }
        return null;
    }

    /** 在方向的站点列表里定位标记站点的下标（0 基） */
    private int findTargetStationIndex(List<BusApiClient.BusLineStation> stations, String stationId) {
        if (stations == null) return -1;
        if (stationId != null) {
            for (int i = 0; i < stations.size(); i++) {
                BusApiClient.BusLineStation s = stations.get(i);
                if (s != null && stationId.equals(s.id)) return i;
            }
        }
        // 兜底：同一站台可能存在多个 id，按站名再找一次。
        // 仅当站名在该方向里唯一出现时才敢用：环形线路或同名站台出现多次时无法确定是哪一站，
        // 猜错会导致“还差几站/距离”算错，报站时机跟着错，此时宁可放弃兜底交给站点接口路径。
        if (currentStationName != null) {
            int matchedIndex = -1;
            for (int i = 0; i < stations.size(); i++) {
                BusApiClient.BusLineStation s = stations.get(i);
                if (s != null && currentStationName.equals(s.stationName)) {
                    if (matchedIndex >= 0) return -1;
                    matchedIndex = i;
                }
            }
            return matchedIndex;
        }
        return -1;
    }

    /**
     * 从这条线的全部车辆里挑出离标记站点最近的一辆：先比“还差几站”，同站数再比剩余距离。
     * <p>
     * ⚠️ 「已到站」必须同时满足“车辆停留的站就是标记站点”({@code stopsRemaining == 0}) 且
     * 接口标记它在站上（{@code isArriveStation == 1}）。
     * 车辆停靠本站后一旦出站，接口会把它改成“在途中、往下一站开”（{@code isArriveStation == 0}），
     * 但 {@code vehicleOrder} 仍是本站序号、{@code distance} 变成了“到下一站的距离”，
     * 若仍按 {@code stopsRemaining == 0} 当成“已到站”，卡片就会一直显示“已到站”，
     * 也永远不会去查下一班的发车时间 —— 这里直接把这种“已出站的车”剔除，
     * 于是该线路退回“没有最近车辆”，交给计划发车时间逻辑（卡片显示「下一班发车时间」+ 发车提醒）。
     */
    private BusApiClient.StationVehicleInfo findNearestVehicleToStation(
            List<BusApiClient.VehicleDynamicInfo> vehicles,
            List<BusApiClient.BusLineStation> stations,
            int targetIndex,
            BusApiClient.LineDirection dir) {
        BusApiClient.VehicleDynamicInfo best = null;
        int bestStops = Integer.MAX_VALUE;
        int bestDistance = Integer.MAX_VALUE;

        for (BusApiClient.VehicleDynamicInfo vehicle : vehicles) {
            if (vehicle == null || vehicle.vehicleOrder <= 0) continue;
            int passedIndex = vehicle.vehicleOrder - 1;      // 车辆已越过（或停靠）的站点下标
            if (passedIndex > targetIndex) continue;         // 已越过标记站点，不再考虑
            if (passedIndex >= stations.size()) continue;    // 越界保护
            int stopsRemaining = targetIndex - passedIndex;  // 0 表示车辆停在/刚离开标记站点
            if (stopsRemaining == 0 && vehicle.isArriveStation != 1) continue;  // 已出站（开往下一站），不再算本站的车
            int remainDistance = calcDistanceToStation(stations, vehicle.distance, passedIndex, targetIndex);
            if (stopsRemaining < bestStops
                    || (stopsRemaining == bestStops && remainDistance < bestDistance)) {
                best = vehicle;
                bestStops = stopsRemaining;
                bestDistance = remainDistance;
            }
        }

        if (best == null) return null;

        BusApiClient.StationVehicleInfo vi = new BusApiClient.StationVehicleInfo();
        vi.lineId = dir.lineId;
        vi.stationId = dir.stationId;
        vi.nextNumber = Math.max(0, bestStops - 1);          // 与站点接口口径一致：0=下一站
        vi.distance = bestDistance;
        vi.isArriveStation = (bestStops == 0 && best.isArriveStation == 1) ? 1 : 0;
        vi.gpsTime = best.gpsTime;
        vi.plateNumber = best.plateNumber;                    // 用于按“车辆”去重播报
        return vi;
    }

    /** 沿途剩余距离：车辆到下一站的距离 + 之后各段站间距，累加到标记站点为止 */
    private int calcDistanceToStation(List<BusApiClient.BusLineStation> stations,
                                      int busDistanceToNext,
                                      int passedIndex,
                                      int targetIndex) {
        if (targetIndex <= passedIndex) return 0;
        int total = Math.max(0, busDistanceToNext);
        for (int i = passedIndex + 2; i <= targetIndex && i < stations.size(); i++) {
            total += getSegmentDistance(stations, i);
        }
        return total;
    }

    /** 站点列表中第 index 站与其上一站的距离：优先用服务端字段，缺失时按站点经纬度估算 */
    private int getSegmentDistance(List<BusApiClient.BusLineStation> stations, int index) {
        if (index <= 0 || index >= stations.size()) return 0;
        BusApiClient.BusLineStation curr = stations.get(index);
        BusApiClient.BusLineStation prev = stations.get(index - 1);
        if (curr == null || prev == null) return 0;
        if (curr.lastDistance > 0) return curr.lastDistance;
        if (prev.distanceToNext > 0) return prev.distanceToNext;
        double prevLat = prev.lat != 0 ? prev.lat : prev.poiOriginLat;
        double prevLng = prev.lng != 0 ? prev.lng : prev.poiOriginLon;
        double currLat = curr.lat != 0 ? curr.lat : curr.poiOriginLat;
        double currLng = curr.lng != 0 ? curr.lng : curr.poiOriginLon;
        if (prevLat == 0 || prevLng == 0 || currLat == 0 || currLng == 0) return 0;
        float[] results = new float[1];
        android.location.Location.distanceBetween(prevLat, prevLng, currLat, currLng, results);
        return (int) results[0];
    }

    /**
     * 对一组线路逐条判断报站与发车预报。
     * 标记模式下每条线路的数据一返回就会单独判断（见 {@link #onMarkerLineReady}），
     * 这里用于「标记方向未匹配到、退回全量展示」时按整表兜底判断。
     */
    private void announceMarkerItems(List<BusApiClient.StationLineInfo> items) {
        if (items == null) return;
        for (BusApiClient.StationLineInfo item : items) {
            if (item == null) continue;
            announceForMarkerLine(item.up);
            announceForMarkerLine(item.down);
            announceDepartureForMarkerLine(item.up);
            announceDepartureForMarkerLine(item.down);
        }
    }

    /**
     * 标记选中时的「进站报站」判断（按单条线路调用）。
     * <p>
     * 播报条件：该方向的最近一班车“下一站就是本站”，且距本站 &lt; {@link #ANNOUNCE_MAX_DISTANCE}(400m)。
     * 方向匹配已精确到 (lineId, stationId)，避免同一 lineId 的反向车辆被误当成该方向。<br>
     * 车辆到达本站时若此前没播过（取数间隔偏大时可能整段 400m 窗口都没采到），补播一次，
     * 避免整趟车彻底不报；播报记录按车辆（车牌）保存，之后本趟不再重复播。
     */
    private void announceForMarkerLine(BusApiClient.LineDirection dir) {
        if (currentSelectedMarker == null || dir == null || dir.lineId == null) return;

        BusApiClient.StationVehicleInfo vi = dir.vehicleInfo;
        if (vi == null) {
            // 本轮该线路没有车辆数据（没有车在跑，或本次取数失败/超时）：
            // 不做任何处理，避免把播报记录误清掉，导致同一辆车下一轮被重复播报
            return;
        }

        String vehicleKey = markerVehicleKey(dir, vi);
        boolean arrivedAtThisStation = vi.isArriveStation == 1;
        boolean inAnnounceRange = vi.nextNumber == 0
                && vi.isArriveStation == 0       // 尚未到站（到站走下面的补播分支）
                && vi.distance > 0
                && vi.distance < ANNOUNCE_MAX_DISTANCE;

        if (arrivedAtThisStation || inAnnounceRange) {
            if (markVehicleAnnounced(vehicleKey)) {
                ttsUtils.playArrivalAnnouncement(dir.lineName, dir.startStation, dir.endStation, currentStationName);
            }
            if (arrivedAtThisStation) return;
        }

        if (vi.nextNumber > 0) {
            // 当前最近的一辆车还差多站：说明刚播报过的那辆车已经过站了（若车牌未知，
            // 记录只按线路维度保存，这里清掉，保证“下一辆车”还能再播）
            announcedVehicles.remove(anonymousVehicleKey(dir));
        }
    }

    /** 播报记录 key：优先按车辆（车牌）区分，车牌缺失时退回按线路+站点维度 */
    private String markerVehicleKey(BusApiClient.LineDirection dir, BusApiClient.StationVehicleInfo vi) {
        String plate = vi == null ? null : vi.plateNumber;
        return (plate == null || plate.isEmpty())
                ? anonymousVehicleKey(dir)
                : anonymousVehicleKey(dir) + "_" + plate;
    }

    private String anonymousVehicleKey(BusApiClient.LineDirection dir) {
        return dir.lineId + "_" + dir.stationId;
    }

    /** 记录并返回该车辆本轮是否需要播报（false 表示这辆车已经播报过） */
    private boolean markVehicleAnnounced(String vehicleKey) {
        long now = System.currentTimeMillis();
        purgeExpiredVehicleRecords(now);
        Long lastAnnounced = announcedVehicles.get(vehicleKey);
        if (lastAnnounced != null && now - lastAnnounced < ANNOUNCE_VEHICLE_RECORD_TTL_MS) {
            return false;
        }
        announcedVehicles.put(vehicleKey, now);
        return true;
    }

    /** 清理过期的播报记录，避免集合随运营时间无界增长 */
    private void purgeExpiredVehicleRecords(long now) {
        if (announcedVehicles.isEmpty()) return;
        Iterator<Map.Entry<String, Long>> iterator = announcedVehicles.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue() >= ANNOUNCE_VEHICLE_RECORD_TTL_MS) {
                iterator.remove();
            }
        }
    }

    /**
     * 标记选中时的「计划发车提醒」判断（按单条线路调用）：
     * 读出该线路的计划发车时间（planTime，形如 HH:mm），记录下来并立即判断是否到点。
     * <p>
     * ⚠️ 该线路已经有最近车辆时不播发车广播：卡片显示的是「最近一班/距离X站」，
     * 说明车已经发出并在路上，此时再报「X 路 X 点 X 分 发车」会与报站互相干扰。
     * <p>
     * 计划发车时间会被服务端滚动更新（22:00 那班一开走就变成下一班），因此不能只在
     * 「取数那一刻」比对 —— 记录下来交给 {@link #checkDepartureAnnouncements()} 每轮复查，
     * 否则正好跨过整点那一分钟时会漏播。
     */
    private void announceDepartureForMarkerLine(BusApiClient.LineDirection dir) {
        if (dir == null || dir.vehicleInfo != null) return;
        String planHm = normalizePlanTime(dir.planTime);
        if (planHm == null) return;
        rememberDeparturePlanTime(dir.lineId, dir.stationId, planHm);
        playDepartureIfDue(dir, planHm);
    }

    /** 记录「尚未到点」的计划发车时间；已经过去的时间不记，避免事后误播 */
    private void rememberDeparturePlanTime(String lineId, String stationId, String planHm) {
        if (lineId == null || stationId == null || planHm == null) return;
        if (planHm.compareTo(nowHourMinute()) < 0) return;
        for (PendingDeparturePlan plan : pendingDeparturePlans) {
            if (plan.lineId.equals(lineId) && plan.stationId.equals(stationId)
                    && plan.planHm.equals(planHm)) {
                return;
            }
        }
        pendingDeparturePlans.add(new PendingDeparturePlan(lineId, stationId, planHm));
    }

    /**
     * 刷新节拍里的复查：所有已记录的计划发车时间，到点的播报、已经过点的丢弃。
     * 这样即使服务端在整点前把计划时间滚成了下一班，到点这一分钟也不会漏播。
     */
    private void checkDepartureAnnouncements() {
        if (currentSelectedMarker == null || pendingDeparturePlans.isEmpty()) return;
        String nowHm = nowHourMinute();
        Iterator<PendingDeparturePlan> iterator = pendingDeparturePlans.iterator();
        while (iterator.hasNext()) {
            PendingDeparturePlan plan = iterator.next();
            if (plan.planHm.compareTo(nowHm) < 0) {
                iterator.remove(); // 已经过点：丢弃，避免过一会儿再播
                continue;
            }
            if (!plan.planHm.equals(nowHm)) continue; // 还没到点
            BusApiClient.LineDirection dir = findMarkerDirection(plan.lineId, plan.stationId);
            if (dir != null) playDepartureIfDue(dir, plan.planHm);
        }
    }

    /**
     * 计划发车时间正好是当前分钟就播报。
     * 键 lineId_stationId_HH:mm 保证同一线路同一分钟只播一次（刷新间隔 10s，同一分钟会命中多次）。
     * 播放统一走 {@link TTSUtils#playDepartureAnnouncement}（内部自动处理「忙则排队」），
     * 避免出现「排进队列却没有任何播放被触发」而永远不播的情况。
     */
    private void playDepartureIfDue(BusApiClient.LineDirection dir, String planHm) {
        if (currentSelectedMarker == null || dir == null
                || dir.lineId == null || dir.stationId == null) return;
        if (dir.startStation == null || dir.endStation == null) return;
        // 已有最近车辆（车在路上）不播发车广播；
        // 本轮车辆取数还没跑到这条线路（状态未知）时先不播，等它确定"有车/无车"后再判断
        if (dir.vehicleInfo != null) return;
        if (markerLoadingInProgress && !markerProcessedLines.contains(markerLineKey(dir))) return;
        if (planHm == null || !nowHourMinute().equals(planHm)) return;

        String departureKey = dir.lineId + "_" + dir.stationId + "_" + planHm;
        // 清掉该线路其它分钟的历史记录，避免集合无界增长
        String prefix = dir.lineId + "_" + dir.stationId + "_";
        List<String> staleKeys = new ArrayList<>();
        for (String key : announcedDepartures) {
            if (key.startsWith(prefix) && !key.equals(departureKey)) staleKeys.add(key);
        }
        announcedDepartures.removeAll(staleKeys);

        if (!announcedDepartures.add(departureKey)) return; // 本分钟已播报过

        ttsUtils.playDepartureAnnouncement(dir.lineName, dir.startStation, dir.endStation, planHm);
    }

    /** 当前时间，形如 HH:mm */
    private String nowHourMinute() {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
    }

    /**
     * 把接口返回的计划发车时间归一化为 HH:mm；解析失败（空串/非时间格式）返回 null。
     * 兼容 "06:30"、"06:30:00"、"2026-09-14 06:30:00"。
     */
    private String normalizePlanTime(String planTime) {
        int[] hourMinute = TTSUtils.parseHourMinute(planTime);
        if (hourMinute == null) return null;
        return String.format(Locale.getDefault(), "%02d:%02d", hourMinute[0], hourMinute[1]);
    }
}
