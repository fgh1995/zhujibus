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
        busApiClient.queryStationInfo(currentStationName, new BusApiClient.ApiCallback<>() {
            @Override
            public void onSuccess(BusApiClient.StationInfoResponse response) {
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
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Log.e("-BusInfo-", Objects.requireNonNull(e.getMessage()));
            }
        });
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
        busApiClient.queryStationVehicleDynamic(lineIds, stationIds, new BusApiClient.ApiCallback<>() {
            @Override
            public void onSuccess(BusApiClient.StationVehicleDynamicResponse response) {
                // 将车辆动态数据与线路信息匹配
                for (BusApiClient.StationVehicleInfo vehicleInfo : response.data) {
                    for (BusApiClient.StationLineInfo lineInfo : busLineItems) {
                        // 检查上行方向
                        if (lineInfo.up != null && lineInfo.up.lineId.equals(vehicleInfo.lineId)) {
                            lineInfo.up.vehicleInfo = vehicleInfo;
                        }
                        // 检查下行方向
                        if (lineInfo.down != null && lineInfo.down.lineId.equals(vehicleInfo.lineId)) {
                            lineInfo.down.vehicleInfo = vehicleInfo;
                        }
                    }
                }

                // 查询没有车辆信息的线路的计划发车时间
                fetchPlanTimeForEmptyLines(busLineItems);
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Log.e("-BusInfo-", "获取车辆动态数据失败: " + e.getMessage());
                // 即使动态数据获取失败，也尝试获取计划时间
                fetchPlanTimeForEmptyLines(busLineItems);
            }
        });
    }

    private void fetchPlanTimeForEmptyLines(List<BusApiClient.StationLineInfo> busLineItems) {
        // 收集没有车辆信息的线路ID
        Set<String> lineIdsWithoutVehicle = new HashSet<>();
        for (BusApiClient.StationLineInfo lineInfo : busLineItems) {
            // 检查上行方向
            if (lineInfo.up != null && lineInfo.up.vehicleInfo == null) {
                lineIdsWithoutVehicle.add(lineInfo.up.lineId);
            }
            // 检查下行方向
            if (lineInfo.down != null && lineInfo.down.vehicleInfo == null) {
                lineIdsWithoutVehicle.add(lineInfo.down.lineId);
            }
        }

        if (!lineIdsWithoutVehicle.isEmpty()) {
            // 将Set转换为逗号分隔的字符串
            String lineIdsStr = String.join(",", lineIdsWithoutVehicle);

            busApiClient.queryBusVehiclePlan(lineIdsStr, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.BusVehiclePlanResponse response) {
                    // 将计划时间与线路匹配
                    for (BusApiClient.BusPlanTime planTime : response.data) {
                        for (BusApiClient.StationLineInfo lineInfo : busLineItems) {
                            // 更新上行方向
                            if (lineInfo.up != null && lineInfo.up.lineId.equals(planTime.lineId)) {
                                lineInfo.up.planTime = planTime.startTime;
                            }
                            // 更新下行方向
                            if (lineInfo.down != null && lineInfo.down.lineId.equals(planTime.lineId)) {
                                lineInfo.down.planTime = planTime.startTime;
                            }
                        }
                    }
                    // 更新适配器数据
                    adapter.setData(busLineItems);
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e("-BusInfo-", "获取计划发车时间失败: " + e.getMessage());
                }
            });
        } else {
            // 如果没有需要查询的线路，直接更新UI
            adapter.setData(busLineItems);
        }
    }
}