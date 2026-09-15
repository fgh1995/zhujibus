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
    private Set<String> announcedVehicles = new HashSet<>();
    // 已播报过的「计划发车提醒」：lineId_stationId_HH:mm，用于同一分钟内不重复播报
    private Set<String> announcedDepartures = new HashSet<>();
    private Handler refreshHandler;
    private Runnable refreshRunnable;
    private static final long REFRESH_INTERVAL = 10000;
    // 报站距离阈值：车辆距本站 < 400m 才语音报站
    private static final int ANNOUNCE_MAX_DISTANCE = 400;
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
    // 标记模式逐条线路处理的进度：当前正在处理的线路下标（-1 表示未在处理）
    private int markerLineIndex = -1;
    // 标记模式一轮线路取数是否仍在进行：进行中时刷新节拍不再发起新一轮，
    // 避免新一轮把上一轮还没取完的线路请求作废（表现为列表迟迟不出车辆、迟迟不报站）
    private boolean markerLoadingInProgress = false;
    // 标记模式本轮播报状态：首条立即播，其余排队依次播
    private boolean markerAnnouncementStarted = false;
    // 单条线路最长等待时间：超时不再等待该线路，直接处理下一条，避免一条慢线路拖住整轮刷新
    private static final long MARKER_LINE_TIMEOUT_MS = 5000;
    private Handler scheduleHandler;

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
                    currentSelectedMarker = marker;
                    queryWithMarker(marker);
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

    private void clearMarkerSelection() {
        currentSelectedMarker = null;
        markerRequestGeneration++;
        markerLineIndex = -1;
        markerLoadingInProgress = false;
        markerAnnouncementStarted = false;
        if (scheduleHandler != null) {
            scheduleHandler.removeCallbacksAndMessages(null);
        }
        lineDetailCache.clear();
        announcedVehicles.clear();
        announcedDepartures.clear();
        adapter.clearHighlightAndGray();
        adapter.resetAllViewPagersToZero();
    }

    /**
     * 点击自定义标记后的取数逻辑。
     * <p>
     * 先走原站点接口拿到本站的线路/首末班等基础信息（保证与其它场景一致），
     * 裁剪出标记对应的单向列表后，所有方向（含本站接口查不到的跨站台线路）都由
     * {@link #startMarkerVehicleLoading()} 走线路详情自行匹配离标记站点最近的那辆车。
     */
    private void queryWithMarker(DirectionMarker marker) {
        announcedVehicles.clear();
        announcedDepartures.clear();
        lineDetailCache.clear();
        if (marker.lineIds.isEmpty()) {
            Toast.makeText(requireContext(), "标记中没有线路", Toast.LENGTH_SHORT).show();
            return;
        }
        loadStationData();
    }

    private void initRefreshHandler() {
        refreshHandler = new Handler(Looper.getMainLooper());
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentSelectedMarker != null) {
                    if (markerLoadingInProgress) {
                        // 上一轮线路取数还没结束：不发起新一轮（否则会作废还没取完的线路请求），
                        // 只复查一次「计划发车提醒」，避免这一节拍正好跨过发车时刻而漏播
                        recheckDepartureAnnouncements();
                    } else {
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
    public void onDestroy() {
        super.onDestroy();
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
                        currentBusLineItems = response.data;
                        // 新一轮数据：本轮首条播报立即播，其余排队
                        markerAnnouncementStarted = false;

                        if (currentSelectedMarker != null && buildMarkerFilteredList()) {
                            // 标记选中：已裁剪出单向列表，接着按“线路车辆详情”取最近车辆
                            startMarkerVehicleLoading();
                            return;
                        }

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
                            fetchVehicleDynamicData(lineIdsBuilder.toString(), stationIdsBuilder.toString(), currentBusLineItems);
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
     */
    private void fetchVehicleDynamicData(String lineIds, String stationIds, List<BusApiClient.StationLineInfo> busLineItems) {
        fetchVehicleDynamicData(lineIds, stationIds, busLineItems, null);
    }

    /**
     * @param onFinished 为空时保持默认行为（取完车辆直接走计划发车时间并刷新）；
     *                   标记模式下传入自己的完成回调，由标记流程统一收尾，避免提前刷新导致数据被覆盖。
     */
    private void fetchVehicleDynamicData(String lineIds, String stationIds,
                                         List<BusApiClient.StationLineInfo> busLineItems,
                                         Runnable onFinished) {
        Runnable finishAction = (onFinished != null)
                ? onFinished
                : () -> fetchPlanTimeForEmptyLines(busLineItems);
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

    private void fetchPlanTimeForEmptyLines(List<BusApiClient.StationLineInfo> busLineItems) {
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
                                if (currentSelectedMarker != null) {
                                    // 兜底：标记方向未匹配到、退回全量展示时，按整表判断报站与发车预报
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
                    adapter.setData(busLineItems);
                    if (currentSelectedMarker != null) {
                        announceMarkerItems(busLineItems);
                    }
                });
            }
        } catch (Exception e) {
            Log.e("-BusInfo-", "查询计划发车时间异常", e);
        }
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

        currentBusLineItems = filtered;
        adapter.setData(filtered);
        return true;
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
        markerAnnouncementStarted = false;

        List<BusApiClient.StationLineInfo> items = currentBusLineItems;
        if (items == null || items.isEmpty()) {
            markerLoadingInProgress = false;
            return;
        }

        List<BusApiClient.LineDirection> dirs = new ArrayList<>();
        for (BusApiClient.StationLineInfo item : items) {
            BusApiClient.LineDirection dir = item.up;
            if (dir == null || dir.lineId == null) continue;
            // 一律清掉站点接口返回的、可能方向错配的车辆数据，改由线路详情重新匹配
            dir.vehicleInfo = null;
            dirs.add(dir);
        }

        if (dirs.isEmpty()) {
            markerLoadingInProgress = false;
            return;
        }

        markerLoadingInProgress = true;
        advanceMarkerLine(dirs, 0, markerRequestGeneration);
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
     * 单条线路数据就绪：先刷新这条线路的卡片，再立刻判断「进站报站」与「发车预报」；
     * 该线路没有在线车辆时补取它的计划发车时间（界面显示「下一班发车时间」+ 发车预报）。
     */
    private void onMarkerLineReady(BusApiClient.LineDirection dir) {
        if (currentSelectedMarker == null || dir == null || !isUiAlive()) return;
        notifyMarkerLineChanged(dir);
        announceForMarkerLine(dir);
        if (dir.vehicleInfo == null) {
            fetchMarkerPlanTime(dir);
        } else {
            announceDepartureForMarkerLine(dir);
        }
    }

    /** 取单条线路的计划发车时间（发车预报依赖它，取到后立即刷新该卡片并判断发车预报） */
    private void fetchMarkerPlanTime(BusApiClient.LineDirection dir) {
        if (dir == null || dir.lineId == null) return;
        final String lineId = dir.lineId;
        final String stationId = dir.stationId;
        try {
            busApiClient.queryBusVehiclePlan(lineId, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.BusVehiclePlanResponse response) {
                    if (response == null || response.data == null || response.data.isEmpty()) {
                        Log.w("-BusInfo-", "计划发车时间数据为空：" + lineId);
                        return;
                    }
                    String startTime = null;
                    for (BusApiClient.BusPlanTime planTime : response.data) {
                        if (planTime != null && lineId.equals(planTime.lineId)) {
                            startTime = planTime.startTime;
                            break;
                        }
                    }
                    if (startTime == null && response.data.get(0) != null) {
                        startTime = response.data.get(0).startTime;
                    }
                    applyMarkerPlanTime(lineId, stationId, startTime);
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e("-BusInfo-", "获取计划发车时间失败: " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            Log.e("-BusInfo-", "查询计划发车时间异常", e);
        }
    }

    /**
     * 把计划发车时间写到当前列表对应方向上并立刻刷新、判断发车预报。
     * 按 (lineId, stationId) 在当前列表里重新定位（而不是直接写回调捕获的对象），
     * 这样即使响应晚于新一轮刷新返回也能正确生效，不会丢播报。
     */
    private void applyMarkerPlanTime(String lineId, String stationId, String planTime) {
        if (currentSelectedMarker == null || !isUiAlive()) return;
        BusApiClient.LineDirection target = findMarkerDirection(lineId, stationId);
        if (target == null) return;
        target.planTime = planTime;
        notifyMarkerLineChanged(target);
        announceDepartureForMarkerLine(target);
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
        for (BusApiClient.LineDirection dir : dirs) {
            if (dir == null || dir.lineId == null || dir.stationId == null) continue;
            appendIds(lineIds, stationIds, dir.lineId, dir.stationId);
        }
        if (lineIds.length() == 0) {
            onDone.run();
            return;
        }
        fetchVehicleDynamicData(lineIds.toString(), stationIds.toString(), currentBusLineItems,
                () -> {
                    if (gen != markerRequestGeneration) return;
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
                            onDone.run();
                            return;
                        }
                        BusApiClient.StationVehicleInfo nearest = findNearestVehicleToStation(
                                response.data.list, stations, target, dir);
                        if (nearest != null) {
                            dir.vehicleInfo = nearest;
                        }
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
        // 兜底：同一站台可能存在多个 id，按站名再找一次
        if (currentStationName != null) {
            for (int i = 0; i < stations.size(); i++) {
                BusApiClient.BusLineStation s = stations.get(i);
                if (s != null && currentStationName.equals(s.stationName)) return i;
            }
        }
        return -1;
    }

    /**
     * 从这条线的全部车辆里挑出离标记站点最近的一辆：先比“还差几站”，同站数再比剩余距离。
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
            int stopsRemaining = targetIndex - passedIndex;  // 0 表示车辆已在标记站点
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

    /** 只复查一次「计划发车提醒」（用于上一轮取数还没结束、本轮跳过刷新的节拍） */
    private void recheckDepartureAnnouncements() {
        List<BusApiClient.StationLineInfo> items = currentBusLineItems;
        if (items == null) return;
        for (BusApiClient.StationLineInfo item : items) {
            if (item == null) continue;
            announceDepartureForMarkerLine(item.up);
            announceDepartureForMarkerLine(item.down);
        }
    }

    /**
     * 标记选中时的「进站报站」判断（按单条线路调用）：
     * 车辆距本站 < {@link #ANNOUNCE_MAX_DISTANCE}(400m) 且为下一班才播报，
     * 方向匹配已精确到 (lineId, stationId)，避免同一 lineId 的反向车辆被误当成该方向。
     * <p>
     * 该线路的车辆离开进站范围后会清掉记录，下次再进站可以重新播报。
     */
    private void announceForMarkerLine(BusApiClient.LineDirection dir) {
        if (currentSelectedMarker == null || dir == null || dir.lineId == null) return;

        String vehicleKey = dir.lineId + "_" + dir.stationId;
        BusApiClient.StationVehicleInfo vi = dir.vehicleInfo;
        boolean inAnnounceRange = vi != null
                && vi.nextNumber == 0            // 为本站的最近一班
                && vi.isArriveStation == 0       // 尚未到站
                && vi.distance > 0 && vi.distance < ANNOUNCE_MAX_DISTANCE;

        if (!inAnnounceRange) {
            announcedVehicles.remove(vehicleKey);
            return;
        }
        if (!announcedVehicles.add(vehicleKey)) return; // 该车已在范围内播报过

        if (markerAnnouncementStarted) {
            ttsUtils.queueArrivalAnnouncement(dir.lineName, dir.startStation, dir.endStation, currentStationName);
        } else {
            markerAnnouncementStarted = true;
            ttsUtils.playArrivalAnnouncement(dir.lineName, dir.startStation, dir.endStation, currentStationName);
        }
    }

    /**
     * 标记选中时的「计划发车提醒」判断（按单条线路调用）：
     * 线路的计划发车时间（planTime，形如 HH:mm）与当前时间一致时播报，
     * 播报内容由 {@link TTSUtils#buildDepartureAnnouncementText} 生成（现阶段整句纯 TTS）。
     * <p>
     * 键 lineId_stationId_HH:mm 保证同一分钟内多次轮询只播一次
     * （刷新间隔 10s，同一分钟会命中多次）；多条线路同时发车时首条立即播、其余排队。
     */
    private void announceDepartureForMarkerLine(BusApiClient.LineDirection dir) {
        if (currentSelectedMarker == null || dir == null
                || dir.lineId == null || dir.stationId == null) return;
        if (dir.startStation == null || dir.endStation == null) return;

        String planHm = normalizePlanTime(dir.planTime);
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

        if (markerAnnouncementStarted) {
            ttsUtils.queueDepartureAnnouncement(dir.lineName, dir.startStation, dir.endStation, planHm);
        } else {
            markerAnnouncementStarted = true;
            ttsUtils.playDepartureAnnouncement(dir.lineName, dir.startStation, dir.endStation, planHm);
        }
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
