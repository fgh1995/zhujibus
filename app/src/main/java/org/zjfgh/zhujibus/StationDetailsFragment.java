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

    private void queryWithMarker(DirectionMarker marker) {
        announcedVehicles.clear();
        if (marker.lineIds.isEmpty()) {
            Toast.makeText(requireContext(), "标记中没有线路", Toast.LENGTH_SHORT).show();
            return;
        }

        currentBusLineItems = new java.util.ArrayList<>();

        for (int i = 0; i < marker.lineIds.size(); i++) {
            String lineId = marker.lineIds.get(i);
            String stationId = marker.stationIds.get(i);

            BusApiClient.LineDirection dir = new BusApiClient.LineDirection();
            dir.lineId = lineId;
            dir.lineName = marker.getLineName(i);
            dir.startStation = marker.getStartStation(i);
            dir.endStation = marker.getEndStation(i);
            dir.stationId = stationId;
            dir.lineTypeName = i < marker.lineTypes.size() ? marker.lineTypes.get(i) : "";
            dir.departureTime = marker.getDepartureTime(i);
            dir.collectTime = marker.getCollectTime(i);

            BusApiClient.StationLineInfo lineInfo = new BusApiClient.StationLineInfo();
            lineInfo.lineName = dir.lineName;
            lineInfo.up = dir;

            currentBusLineItems.add(lineInfo);
        }

        adapter.setData(currentBusLineItems);

        fetchVehicleDynamicDataForMarker(marker);
    }

    private void fetchVehicleDynamicDataForMarker(DirectionMarker marker) {
        String lineIdsStr = String.join(",", marker.lineIds);
        String stationIdsStr = String.join(",", marker.stationIds);

        busApiClient.queryStationVehicleDynamic(lineIdsStr, stationIdsStr,
                new BusApiClient.ApiCallback<>() {
                    @Override
                    public void onSuccess(BusApiClient.StationVehicleDynamicResponse response) {
                        if (response == null || response.data == null || response.data.isEmpty()) {
                            runOnUiThreadSafe(() -> {
                                if (isUiAlive()) {
                                    fetchNextBusTimeForMarker(marker);
                                }
                            });
                            return;
                        }

                        runOnUiThreadSafe(() -> {
                            if (!isUiAlive()) return;
                            Set<String> linesWithVehicle = new HashSet<>();
                            Set<String> linesWithPassedVehicle = new HashSet<>();
                            if (currentBusLineItems != null) {
                                for (BusApiClient.StationLineInfo lineInfo : currentBusLineItems) {
                                    if (lineInfo.up != null) {
                                        lineInfo.up.vehicleInfo = null;
                                        lineInfo.up.isPassed = true;
                                    }
                                    if (lineInfo.down != null) {
                                        lineInfo.down.vehicleInfo = null;
                                        lineInfo.down.isPassed = true;
                                    }
                                }
                            }
                            for (BusApiClient.StationVehicleInfo vehicleInfo : response.data) {
                                if (vehicleInfo != null) {
                                    if (vehicleInfo.distance == 0) {
                                        linesWithPassedVehicle.add(vehicleInfo.lineId);
                                        continue;
                                    }
                                    linesWithVehicle.add(vehicleInfo.lineId);
                                    if (currentBusLineItems != null) {
                                        for (BusApiClient.StationLineInfo lineInfo : currentBusLineItems) {
                                            if (lineInfo.up != null && lineInfo.up.lineId.equals(vehicleInfo.lineId)
                                                    && lineInfo.up.stationId != null && lineInfo.up.stationId.equals(vehicleInfo.stationId)) {
                                                lineInfo.up.vehicleInfo = vehicleInfo;
                                                lineInfo.up.isPassed = false;
                                            }
                                            if (lineInfo.down != null && lineInfo.down.lineId.equals(vehicleInfo.lineId)
                                                    && lineInfo.down.stationId != null && lineInfo.down.stationId.equals(vehicleInfo.stationId)) {
                                                lineInfo.down.vehicleInfo = vehicleInfo;
                                                lineInfo.down.isPassed = false;
                                            }
                                        }
                                    }
                                }
                            }

                            // 调试：检测 lineId 匹配但 stationId 不匹配的错配数据（服务端可能把反向车辆标成本方向 lineId）
                            reportMarkerMismatch(marker, response.data);
                            adapter.setData(currentBusLineItems);

                            List<String> linesWithoutVehicle = new ArrayList<>();
                            for (String lineId : marker.lineIds) {
                                if (!linesWithVehicle.contains(lineId) || linesWithPassedVehicle.contains(lineId)) {
                                    linesWithoutVehicle.add(lineId);
                                }
                            }

                            if (!linesWithoutVehicle.isEmpty()) {
                                fetchPlanTimeForLines(linesWithoutVehicle);
                            }

                            List<BusApiClient.StationVehicleInfo> validVehicleInfos = new ArrayList<>();
                            for (BusApiClient.StationVehicleInfo vi : response.data) {
                                if (vi != null && vi.distance > 0 && vi.isArriveStation == 0) {
                                    validVehicleInfos.add(vi);
                                }
                            }
                            handleTTSAnnouncementForMarker(validVehicleInfos);
                        });
                    }

                    @Override
                    public void onError(BusApiClient.BusApiException e) {
                        runOnUiThreadSafe(() -> {
                            if (!isUiAlive()) return;
                            Toast.makeText(getContext(), "查询失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                            fetchNextBusTimeForMarker(marker);
                        });
                    }
                });
    }

    private void fetchPlanTimeForLines(List<String> lineIds) {
        if (lineIds.isEmpty()) return;

        String lineIdsStr = String.join(",", lineIds);
        busApiClient.queryBusVehiclePlan(lineIdsStr, new BusApiClient.ApiCallback<>() {
            @Override
            public void onSuccess(BusApiClient.BusVehiclePlanResponse response) {
                runOnUiThreadSafe(() -> {
                    if (!isUiAlive()) return;
                    if (response != null && response.data != null) {
                        for (BusApiClient.BusPlanTime planTime : response.data) {
                            if (planTime == null) continue;
                            if (currentBusLineItems != null) {
                                for (BusApiClient.StationLineInfo lineInfo : currentBusLineItems) {
                                    if (lineInfo.up != null && lineInfo.up.lineId.equals(planTime.lineId)) {
                                        lineInfo.up.planTime = planTime.startTime;
                                    }
                                    if (lineInfo.down != null && lineInfo.down.lineId.equals(planTime.lineId)) {
                                        lineInfo.down.planTime = planTime.startTime;
                                    }
                                }
                            }
                        }
                    }
                    adapter.setData(currentBusLineItems);
                });
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                runOnUiThreadSafe(() -> {
                    if (!isUiAlive()) return;
                    adapter.setData(currentBusLineItems);
                });
            }
        });
    }

    /**
     * 调试辅助：在标记模式下检测返回的实时车辆是否存在 lineId 匹配但 stationId 不匹配的错配。
     * 仅用于排查，不影响显示逻辑。
     */
    private void reportMarkerMismatch(DirectionMarker marker, List<BusApiClient.StationVehicleInfo> data) {
        if (marker == null || data == null) return;
        StringBuilder mismatchSb = new StringBuilder();
        for (BusApiClient.StationVehicleInfo vi : data) {
            if (vi == null) continue;
            int idx = marker.lineIds.indexOf(vi.lineId);
            if (idx >= 0 && idx < marker.stationIds.size()) {
                String expected = marker.stationIds.get(idx);
                if (!expected.equals(vi.stationId)) {
                    mismatchSb.append(String.format(
                            "[lineId=%s 期望station=%s 实际=%s dist=%d arrived=%d] ",
                            vi.lineId, expected, vi.stationId, vi.distance, vi.isArriveStation));
                }
            }
        }
        if (mismatchSb.length() > 0) {
            String msg = "方向标记错配(已忽略): " + mismatchSb.toString();
            Log.e("-BusInfo-", msg);
            android.content.Context ctx = getContext();
            if (ctx != null) {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void fetchNextBusTimeForMarker(DirectionMarker marker) {
        if (marker.lineIds.isEmpty()) {
            adapter.setData(currentBusLineItems);
            return;
        }
        fetchPlanTimeForLines(marker.lineIds);
    }

    private void handleTTSAnnouncementForMarker(List<BusApiClient.StationVehicleInfo> vehicleInfos) {
        Set<String> currentVehicleKeys = new HashSet<>();
        boolean isFirstAnnouncement = true;
        for (BusApiClient.StationVehicleInfo vi : vehicleInfos) {
            if (vi != null && vi.nextNumber == 0) {
                String vehicleKey = vi.lineId + "_" + vi.stationId;
                currentVehicleKeys.add(vehicleKey);
                if (!announcedVehicles.contains(vehicleKey)) {
                    for (BusApiClient.StationLineInfo lineInfo : currentBusLineItems) {
                        if (lineInfo.up != null && lineInfo.up.lineId.equals(vi.lineId)) {
                            if (isFirstAnnouncement) {
                                ttsUtils.playArrivalAnnouncement(
                                        lineInfo.up.lineName,
                                        lineInfo.up.startStation,
                                        lineInfo.up.endStation,
                                        currentStationName
                                );
                                isFirstAnnouncement = false;
                            } else {
                                ttsUtils.queueArrivalAnnouncement(
                                        lineInfo.up.lineName,
                                        lineInfo.up.startStation,
                                        lineInfo.up.endStation,
                                        currentStationName
                                );
                            }
                            break;
                        }
                        if (lineInfo.down != null && lineInfo.down.lineId.equals(vi.lineId)) {
                            if (isFirstAnnouncement) {
                                ttsUtils.playArrivalAnnouncement(
                                        lineInfo.down.lineName,
                                        lineInfo.down.startStation,
                                        lineInfo.down.endStation,
                                        currentStationName
                                );
                                isFirstAnnouncement = false;
                            } else {
                                ttsUtils.queueArrivalAnnouncement(
                                        lineInfo.down.lineName,
                                        lineInfo.down.startStation,
                                        lineInfo.down.endStation,
                                        currentStationName
                                );
                            }
                            break;
                        }
                    }
                }
            }
        }
        announcedVehicles = currentVehicleKeys;
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

    private void refreshWithMarker(DirectionMarker marker) {
        fetchVehicleDynamicDataForMarker(marker);
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
                        adapter.setData(currentBusLineItems);

                        if (currentSelectedMarker != null) {
                            adapter.switchToMatchingDirection(currentSelectedMarker.lineIds, currentSelectedMarker.stationIds);
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
                });
            }
        } catch (Exception e) {
            Log.e("-BusInfo-", "查询计划发车时间异常", e);
        }
    }
}