package org.zjfgh.zhujibus;

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
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class StationDetailsFragment extends DialogFragment {
    private BusApiClient busApiClient;
    private RecyclerView recyclerView;
    private BusStationAdapter adapter;
    private final String currentStationName;
    private Handler refreshHandler;
    private Runnable refreshRunnable;
    private static final long REFRESH_INTERVAL = 10000; // 10秒刷新一次

    public StationDetailsFragment(String currentStationName) {
        this.currentStationName = currentStationName;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NORMAL, R.style.TransparentDialog);
        busApiClient = new BusApiClient();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_station_details, container, false);
        TextView stationTitle = view.findViewById(R.id.station_title);
        stationTitle.setText(this.currentStationName);
        recyclerView = view.findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        // 设置透明分割线
        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(
                recyclerView.getContext(),
                LinearLayoutManager.VERTICAL
        );
        // 创建透明 Drawable
        Drawable transparentDivider = new ColorDrawable(Color.TRANSPARENT);
        transparentDivider.setBounds(0, 0, 0, 8); // 左、上、右、下（高度 8px）
        dividerItemDecoration.setDrawable(transparentDivider);
        recyclerView.addItemDecoration(dividerItemDecoration);
        adapter = new BusStationAdapter();
        recyclerView.setAdapter(adapter);
        // 初始化定时刷新
        initRefreshHandler();
        loadStationData();
        return view;
    }

    private void initRefreshHandler() {
        refreshHandler = new Handler(Looper.getMainLooper());
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                loadStationData();
                refreshHandler.postDelayed(this, REFRESH_INTERVAL);
            }
        };
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            Window window = dialog.getWindow();
            if (window != null) {
                // 设置窗口大小和背景
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 开始定时刷新
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL);
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        // 停止定时刷新
        refreshHandler.removeCallbacks(refreshRunnable);
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
                        List<BusApiClient.StationLineInfo> busLineItems = response.data;
                        adapter.setData(busLineItems);

                        StringBuilder lineIdsBuilder = new StringBuilder();
                        StringBuilder stationIdsBuilder = new StringBuilder();

                        for (BusApiClient.StationLineInfo item : busLineItems) {
                            if (item.up != null) {
                                appendIds(lineIdsBuilder, stationIdsBuilder, item.up.lineId, item.up.stationId);
                            }
                            if (item.down != null) {
                                appendIds(lineIdsBuilder, stationIdsBuilder, item.down.lineId, item.down.stationId);
                            }
                        }

                        if (lineIdsBuilder.length() > 0) {
                            fetchVehicleDynamicData(lineIdsBuilder.toString(), stationIdsBuilder.toString(), busLineItems);
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
                            fetchPlanTimeForEmptyLines(busLineItems);
                            return;
                        }
                        for (BusApiClient.StationVehicleInfo vehicleInfo : response.data) {
                            if (vehicleInfo == null) continue;
                            for (BusApiClient.StationLineInfo lineInfo : busLineItems) {
                                if (lineInfo.up != null && lineInfo.up.lineId.equals(vehicleInfo.lineId)) {
                                    lineInfo.up.vehicleInfo = vehicleInfo;
                                }
                                if (lineInfo.down != null && lineInfo.down.lineId.equals(vehicleInfo.lineId)) {
                                    lineInfo.down.vehicleInfo = vehicleInfo;
                                }
                            }
                        }
                        fetchPlanTimeForEmptyLines(busLineItems);
                    } catch (Exception e) {
                        Log.e("-BusInfo-", "处理车辆动态数据失败", e);
                        fetchPlanTimeForEmptyLines(busLineItems);
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e("-BusInfo-", "获取车辆动态数据失败: " + e.getMessage(), e);
                    fetchPlanTimeForEmptyLines(busLineItems);
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
                    }

                    @Override
                    public void onError(BusApiClient.BusApiException e) {
                        Log.e("-BusInfo-", "获取计划发车时间失败: " + e.getMessage(), e);
                    }
                });
            } else {
                adapter.setData(busLineItems);
            }
        } catch (Exception e) {
            Log.e("-BusInfo-", "查询计划发车时间异常", e);
        }
    }
}