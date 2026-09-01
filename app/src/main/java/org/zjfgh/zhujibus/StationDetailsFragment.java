package org.zjfgh.zhujibus;

import android.app.AlertDialog;
import android.app.Dialog;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StationDetailsFragment extends DialogFragment {
    private static final String ARG_STATION_NAME = "station_name";
    private BusApiClient busApiClient;
    private TTSUtils ttsUtils;
    private RecyclerView recyclerView;
    private BusStationAdapter adapter;
    private String currentStationName;
    private Set<String> announcedVehicles = new HashSet<>();
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

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
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

        builder.create().show();
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
        new AlertDialog.Builder(requireContext())
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
                new AlertDialog.Builder(requireContext())
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

        new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setPositiveButton("关闭", null)
                .show();
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
        announcedVehicles.clear();
        adapter.clearHighlightAndGray();
        adapter.resetAllViewPagersToZero();
    }

    /**
     * 点击自定义标记后的取数逻辑。
     * 旧逻辑会单独请求后端（按标记里保存的 lineId/stationId 取车辆动态），常因方向错配返回不准确的数据。
     * 现改为：直接走原站点接口刷新双向数据（up/down 都包含，且由服务端正确关联本站），
     * 刷新完成后按标记的方向（lineId + stationId 同索引成对）从双向数据里取对应方向的车辆信息即可，不再单独请求。
     */
    private void queryWithMarker(DirectionMarker marker) {
        announcedVehicles.clear();
        if (marker.lineIds.isEmpty()) {
            Toast.makeText(requireContext(), "标记中没有线路", Toast.LENGTH_SHORT).show();
            return;
        }
        // 通过原接口刷新站点双向数据；标方向的取数 / 报站在刷新完成后从双向数据完成
        loadStationData();
    }

    private void initRefreshHandler() {
        refreshHandler = new Handler(Looper.getMainLooper());
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentSelectedMarker != null) {
                    refreshWithMarker(currentSelectedMarker);
                } else {
                    loadStationData();
                }
                refreshHandler.postDelayed(this, REFRESH_INTERVAL);
            }
        };
    }

    /**
     * 标记选中时的刷新：同样走原站点接口，避免单独请求导致方向错配。
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

                        if (currentSelectedMarker != null) {
                            // 标记选中：从原接口的双向数据里只取标记方向（单向），不再单独请求后端
                            buildMarkerFilteredList();
                        } else {
                            adapter.setData(currentBusLineItems);
                        }

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
     * 标记模式下不会单独请求，取数完全来自这里的双向数据。
     */
    private void fetchVehicleDynamicData(String lineIds, String stationIds, List<BusApiClient.StationLineInfo> busLineItems) {
        try {
            busApiClient.queryStationVehicleDynamic(lineIds, stationIds, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.StationVehicleDynamicResponse response) {
                    try {
                        if (response == null || response.data == null) {
                            Log.w("-BusInfo-", "车辆动态数据为空");
                            runOnUiThreadSafe(() -> {
                                if (isUiAlive()) fetchPlanTimeForEmptyLines(busLineItems);
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
                            if (isUiAlive()) fetchPlanTimeForEmptyLines(busLineItems);
                        });
                    } catch (Exception e) {
                        Log.e("-BusInfo-", "处理车辆动态数据失败", e);
                        runOnUiThreadSafe(() -> {
                            if (isUiAlive()) fetchPlanTimeForEmptyLines(busLineItems);
                        });
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e("-BusInfo-", "获取车辆动态数据失败: " + e.getMessage(), e);
                    runOnUiThreadSafe(() -> {
                        if (isUiAlive()) fetchPlanTimeForEmptyLines(busLineItems);
                    });
                }
            });
        } catch (Exception e) {
            Log.e("-BusInfo-", "请求车辆动态数据异常", e);
            fetchPlanTimeForEmptyLines(busLineItems);
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
                                    announceForMarker();
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
                        announceForMarker();
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
     */
    private void buildMarkerFilteredList() {
        if (currentSelectedMarker == null || currentBusLineItems == null) return;
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
            if (matchedDir == null) continue;

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
            return;
        }

        currentBusLineItems = filtered;
        adapter.setData(filtered);
    }

    /**
     * 标记选中时按过滤后的单向列表报站：车辆距本站 < ANNOUNCE_MAX_DISTANCE(400m) 才播报，
     * 方向匹配已精确到 (lineId, stationId)，避免同一 lineId 的反向车辆被误当成该方向。
     */
    private void announceForMarker() {
        if (currentSelectedMarker == null || currentBusLineItems == null) return;

        Set<String> currentVehicleKeys = new HashSet<>();
        boolean isFirstAnnouncement = true;

        for (BusApiClient.StationLineInfo lineInfo : currentBusLineItems) {
            if (lineInfo.up == null) continue;
            BusApiClient.LineDirection dir = lineInfo.up;
            BusApiClient.StationVehicleInfo vi = dir.vehicleInfo;
            if (vi == null) continue;
            // 过滤：未过站、为下一班、且距离 < 400m 才报站
            if (vi.nextNumber != 0) continue;
            if (vi.distance <= 0 || vi.distance >= ANNOUNCE_MAX_DISTANCE) continue;
            if (vi.isArriveStation != 0) continue;

            String vehicleKey = dir.lineId + "_" + dir.stationId;
            currentVehicleKeys.add(vehicleKey);
            if (!announcedVehicles.contains(vehicleKey)) {
                if (isFirstAnnouncement) {
                    ttsUtils.playArrivalAnnouncement(
                            dir.lineName,
                            dir.startStation,
                            dir.endStation,
                            currentStationName
                    );
                    isFirstAnnouncement = false;
                } else {
                    ttsUtils.queueArrivalAnnouncement(
                            dir.lineName,
                            dir.startStation,
                            dir.endStation,
                            currentStationName
                    );
                }
            }
        }
        announcedVehicles = currentVehicleKeys;
    }
}
