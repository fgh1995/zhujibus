package org.zjfgh.zhujibus;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.List;
import java.util.Locale;

/**
 * 排班/时刻表页面
 */
public class ScheduleFragment extends Fragment {

    private static final String TAG = "ScheduleFragment";
    private static final String REQUEST_TAG_PREFIX = "schedule_";

    private LinearLayout tvTitle;
    private TextView tvLoading;
    private LinearLayout scheduleTableContainer;

    // 请求取消相关变量
    private String currentRequestTag = null;
    private boolean isLoadingData = false;

    public static ScheduleFragment newInstance() {
        return new ScheduleFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_schedule, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        scheduleTableContainer = view.findViewById(R.id.schedule_table_container);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 取消请求
        cancelPendingRequest();
        tvTitle = null;
        tvLoading = null;
        scheduleTableContainer = null;
    }

    /**
     * 显示加载中
     */
    public void showLoading() {
        if (!isAdded()) return;
        if (tvTitle != null) tvTitle.setVisibility(View.GONE);
        if (tvLoading != null) {
            tvLoading.setVisibility(View.VISIBLE);
            tvLoading.setText("加载中...");
        }
        if (scheduleTableContainer != null) scheduleTableContainer.setVisibility(View.GONE);
    }

    /**
     * 显示错误信息
     */
    public void showError(String message) {
        if (!isAdded()) return;
        if (tvTitle != null) tvTitle.setVisibility(View.GONE);
        if (tvLoading != null) tvLoading.setVisibility(View.GONE);
        if (scheduleTableContainer != null) {
            scheduleTableContainer.setVisibility(View.VISIBLE);
            scheduleTableContainer.removeAllViews();
            TextView errorText = new TextView(requireContext());
            errorText.setText(message);
            errorText.setTextColor(0xFFCCCCCC);
            errorText.setTextSize(14);
            errorText.setGravity(Gravity.CENTER);
            errorText.setPadding(0, 48, 0, 48);
            scheduleTableContainer.addView(errorText);
        }
    }

    private String getCurrentRequestTag() {
        return currentRequestTag;
    }

    private void cancelPendingRequest() {
        isLoadingData = false;
        currentRequestTag = null;
    }

    /**
     * 加载时刻表数据
     * 由 Activity 在 Fragment 视图创建完成后调用
     */
    public void loadData(BusApiClient busApiClient, BusApiClient.BusLineDirection lineDirection, String lineName) {
        // 防止重复加载
        if (isLoadingData) {
            Log.d(TAG, "loadData: already loading, skip");
            return;
        }

        if (lineDirection == null) {
            Log.e(TAG, "加载时刻表失败: 线路方向数据为空");
            if (isAdded()) {
                showError("暂无时刻表数据");
            }
            return;
        }
        if (lineDirection.id == null || lineDirection.id.isEmpty()) {
            Log.e(TAG, "加载时刻表失败: 线路ID为空");
            if (isAdded()) {
                showError("暂无时刻表数据");
            }
            return;
        }

        // 取消之前的请求
        cancelPendingRequest();
        isLoadingData = true;
        currentRequestTag = REQUEST_TAG_PREFIX + System.currentTimeMillis();

        if (isAdded()) {
            showLoading();
        }

        busApiClient.getBusLinePlanTime(lineDirection.id, new BusApiClient.ApiCallback<BusApiClient.BusLinePlanTimeResponse>() {
            @Override
            public void onSuccess(BusApiClient.BusLinePlanTimeResponse response) {
                isLoadingData = false;

                // 检查 Fragment 是否还附着
                if (!isAdded() || getActivity() == null) {
                    Log.w(TAG, "Fragment not attached, skip onSuccess");
                    return;
                }

                // 检查请求是否已被取消或被新请求覆盖
                if (!currentRequestTag.equals(getCurrentRequestTag())) {
                    Log.d(TAG, "Request has been superseded, skip");
                    return;
                }

                try {
                    if (response == null || response.data == null) {
                        Log.e(TAG + "-BusInfo-", "时刻表-无数据");
                        requireActivity().runOnUiThread(() -> {
                            if (isAdded()) {
                                showError("时刻表数据为空");
                            }
                        });
                        return;
                    }
                    if (!"200".equals(response.code)) {
                        Log.e(TAG + "-BusInfo-", "时刻表-状态码错误：" + response.code);
                        requireActivity().runOnUiThread(() -> {
                            if (isAdded()) {
                                showError("时刻表获取失败：" + response.code);
                            }
                        });
                        return;
                    }
                    final List<String> scheduleData = response.data;
                    final String lineNameParam = lineName;
                    requireActivity().runOnUiThread(() -> {
                        if (isAdded() && currentRequestTag.equals(getCurrentRequestTag())) {
                            showScheduleData(scheduleData, lineDirection, lineNameParam);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "处理时刻表数据失败", e);
                    requireActivity().runOnUiThread(() -> {
                        if (isAdded()) {
                            showError("处理时刻表数据失败");
                        }
                    });
                }
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                isLoadingData = false;

                if (!isAdded() || getActivity() == null) {
                    Log.w(TAG, "Fragment not attached, skip onError");
                    return;
                }

                if (!currentRequestTag.equals(getCurrentRequestTag())) {
                    Log.d(TAG, "Request has been superseded, skip");
                    return;
                }

                Log.e(TAG + "-BusInfo-", "时刻表-请求失败：" + e.getMessage(), e);
                requireActivity().runOnUiThread(() -> {
                    if (isAdded()) {
                        showError("时刻表请求失败：" + e.getMessage());
                    }
                });
            }
        });
    }

    /**
     * 显示时刻表数据 - 表格样式
     *
     * 格式规则：
     * 1. 出场行：序号"出场"，车辆留空，发车=最早班-3分钟，到达=最早班，线路=当前线路，调度留空，起点站留空，终点站=当前方向起点
     * 2. 中间班次：序号递增，车辆留空，发车时间按序显示，到达留空，线路=当前线路，调度=全程，起点=起点站，终点=终点站
     * 3. 进场行：序号"进场"，车辆留空，发车留空，到达留空，线路=当前线路，调度留空，起点=当前方向终点，终点留空
     */
    public void showScheduleData(List<String> scheduleTimes, BusApiClient.BusLineDirection lineDirection, String lineName) {
        if (!isAdded()) return;

        if (scheduleTimes == null || scheduleTimes.isEmpty()) {
            showError("暂无时刻表数据");
            return;
        }

        if (tvTitle != null) tvTitle.setVisibility(View.GONE);
        if (tvLoading != null) tvLoading.setVisibility(View.GONE);
        if (scheduleTableContainer != null) {
            scheduleTableContainer.setVisibility(View.VISIBLE);
            scheduleTableContainer.removeAllViews();
        }

        try {
            final String firstBusTime = scheduleTimes.get(0);
            final String startStation = lineDirection.startStation;
            final String endStation = lineDirection.endStation;
            // 根据线路长度(公里)和367米/分钟计算运行时间(分钟)
            final int travelMinutes = (int) Math.ceil(lineDirection.lineLength * 1000.0 / 367.0);

            // 1. 出场行
            String[] outBound = computeOutboundTime(firstBusTime);
            addTableRow(1, true, false, outBound[0], outBound[1], lineName, "", "", startStation);

            // 2. 中间班次（普通行）
            int scheduleIndex = 1;
            String lastArrivalTime = firstBusTime;
            for (int i = 0; i < scheduleTimes.size(); i++) {
                String departure = scheduleTimes.get(i);
                String arrival = computeArrivalTime(departure, travelMinutes);
                lastArrivalTime = arrival;
                addTableRow(scheduleIndex++, false, false, departure, arrival, lineName, "全程", startStation, endStation);
            }

            // 3. 进场行：发车=末班车到达时间，到达=发车+3分钟
            String inboundArrival = computeInboundArrivalTime(lastArrivalTime);
            addTableRow(0, false, true, lastArrivalTime, inboundArrival, lineName, "", endStation, "");

        } catch (Exception e) {
            Log.e(TAG, "渲染时刻表失败", e);
            showError("时刻表渲染失败: " + e.getMessage());
        }
    }

    /**
     * 计算出场时间：发车=最早班-3分钟，到达=最早班
     */
    private String[] computeOutboundTime(String firstBusTime) {
        try {
            String[] parts = firstBusTime.split(":");
            if (parts.length != 2) {
                return new String[]{firstBusTime, firstBusTime};
            }
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            int totalMinutes = hour * 60 + minute - 3;
            if (totalMinutes < 0) totalMinutes += 24 * 60;
            String outTime = String.format(Locale.getDefault(), "%02d:%02d", totalMinutes / 60, totalMinutes % 60);
            return new String[]{outTime, firstBusTime};
        } catch (Exception e) {
            Log.e(TAG, "计算出场时间失败", e);
            return new String[]{firstBusTime, firstBusTime};
        }
    }

    /**
     * 计算到达时间：发车时间 + 运行时间（线路长度/367米每分钟）
     */
    private String computeArrivalTime(String departureTime, int travelMinutes) {
        try {
            String[] parts = departureTime.split(":");
            if (parts.length != 2) {
                return departureTime;
            }
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            int totalMinutes = hour * 60 + minute + travelMinutes + 5;
            if (totalMinutes >= 24 * 60) totalMinutes -= 24 * 60;
            return String.format(Locale.getDefault(), "%02d:%02d", totalMinutes / 60, totalMinutes % 60);
        } catch (Exception e) {
            Log.e(TAG, "计算到达时间失败", e);
            return departureTime;
        }
    }

    /**
     * 计算进场到达时间：发车时间 + 3分钟
     */
    private String computeInboundArrivalTime(String departureTime) {
        try {
            String[] parts = departureTime.split(":");
            if (parts.length != 2) {
                return departureTime;
            }
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            int totalMinutes = hour * 60 + minute + 3;
            if (totalMinutes >= 24 * 60) totalMinutes -= 24 * 60;
            return String.format(Locale.getDefault(), "%02d:%02d", totalMinutes / 60, totalMinutes % 60);
        } catch (Exception e) {
            Log.e(TAG, "计算进场到达时间失败", e);
            return departureTime;
        }
    }

    /**
     * 添加一行表格数据
     * @param sequence 序号
     * @param isOutbound 是否为出场行
     * @param isInbound 是否为进场行
     * @param departure 发车时间
     * @param arrival 到达时间
     * @param line 线路
     * @param dispatch 调度
     * @param startPoint 起点
     * @param endPoint 终点
     */
    private void addTableRow(int sequence, boolean isOutbound, boolean isInbound,
                             String departure, String arrival,
                             String line, String dispatch, String startPoint, String endPoint) {
        if (scheduleTableContainer == null || !isAdded()) return;

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 6, 0, 6);

        String[] values = new String[]{
                String.valueOf(sequence),
                departure,
                arrival,
                line,
                dispatch,
                startPoint,
                endPoint
        };

        for (int i = 0; i < 7; i++) {
            TextView cell = new TextView(requireContext());
            cell.setGravity(Gravity.CENTER);
            cell.setTextSize(12);
            cell.setIncludeFontPadding(false);
            cell.setPadding(0, 0, 0, 0);
            cell.setSingleLine(true);
            cell.setEllipsize(TextUtils.TruncateAt.END);

            String val = values[i];
            if (i == 0) {
                // 序号列
                if (isOutbound) {
                    cell.setText("出场");
                } else if (isInbound) {
                    cell.setText("进场");
                } else {
                    cell.setText(val);
                }
                cell.setTextColor(0xFFFFFFFF);
            } else {
                cell.setText(val);
                cell.setTextColor(0xFFFFFFFF);
            }

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.weight = 1;
            cell.setLayoutParams(params);

            row.addView(cell);
        }

        scheduleTableContainer.addView(row);
    }
}