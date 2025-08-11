package org.zjfgh.zhujibus;

import java.util.Collections;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class LineFragment extends Fragment {
    private final List<BusApiClient.BusLineInfo> busLineInfo = new ArrayList<>();
    private BusLineAdapter busLineAdapter;
    private RecyclerView recyclerView;
    BusApiClient client;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // 1. 先inflate布局
        View view = inflater.inflate(R.layout.line_fragment, container, false);
        // 2. 创建客户端实例
        client = new BusApiClient();
        // 3. 初始化视图（传入inflate得到的view）
        initViews(view);
        return view;
    }

    // 在onCreateView或onCreate中初始化
    private void initViews(View view) {
        recyclerView = view.findViewById(R.id.rv_bus_line_results);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        busLineAdapter = new BusLineAdapter();
        recyclerView.setAdapter(busLineAdapter);

        // 设置点击监听
        busLineAdapter.setOnItemClickListener(line -> {
            // 处理线路点击事件，例如跳转到详情页
            //showBusLineDetails(line);
        });
    }

    public void searchLines(String keyword) {
        if (keyword.isEmpty()) {
            busLineAdapter.setData(Collections.emptyList());
            return;
        }

        Log.e("ZhuJiBus", "搜索线路" + keyword);
        client.searchBusLines(keyword, 123, new BusApiClient.ApiCallback<>() {
            @Override
            public void onSuccess(BusApiClient.BusLineSearchResponse response) {
                if ("200".equals(response.code)) {
                    // 更新UI必须在主线程
                    requireActivity().runOnUiThread(() -> {
                        busLineAdapter.setData(response.data.list);
                        if (response.data.list.isEmpty()) {
                            showEmptyView();
                        }
                    });
                } else {
                    Log.e("BusLine", "搜索失败: " + response.msg);
                    showErrorView(response.msg);
                }
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Log.e("BusLine", "API调用错误", e);
                showErrorView(e.getMessage());
            }
        });
    }
//    private void showBusLineDetails(BusApiClient.BusLineInfo line) {
//        // 实现跳转到线路详情页的逻辑
//        Intent intent = new Intent(getActivity(), BusLineDetailActivity.class);
//        intent.putExtra("line_name", line.lineName);
//        intent.putExtra("start_station", line.startStation);
//        intent.putExtra("end_station", line.endStation);
//        startActivity(intent);
//    }

    private void showEmptyView() {
        // 显示无数据视图
        if (getView() == null) return;

        // 隐藏列表和加载状态
        recyclerView.setVisibility(View.GONE);
        
    }

    private void showErrorView(String message) {
        // 显示错误视图
    }
}

