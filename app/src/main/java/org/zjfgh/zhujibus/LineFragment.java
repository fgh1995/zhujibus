package org.zjfgh.zhujibus;

import java.util.Collections;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class LineFragment extends Fragment {
    private SearchBusLineAdapter searchBusLineAdapter;
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
        TextView title = view.findViewById(R.id.title);
        title.setText("线路");
        
        recyclerView = view.findViewById(R.id.rv_bus_line_results);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        searchBusLineAdapter = new SearchBusLineAdapter();
        recyclerView.setAdapter(searchBusLineAdapter);
        // 设置点击监听
        searchBusLineAdapter.setOnItemClickListener(line -> {
            // 处理线路点击事件，例如跳转到详情页
            showBusLineDetails(line);
        });
    }

    public void searchLines(String keyword) {
        try {
            if (keyword.isEmpty()) {
                searchBusLineAdapter.setData(Collections.emptyList());
                return;
            }

            Log.e("ZhuJiBus", "搜索线路" + keyword);
            client.searchBusLines(keyword, 123, new BusApiClient.ApiCallback<>() {
                @Override
                public void onSuccess(BusApiClient.BusLineSearchResponse response) {
                    try {
                        if (response == null || response.data == null) {
                            Log.e("BusLine", "搜索结果为空");
                            return;
                        }
                        if ("200".equals(response.code)) {
                            requireActivity().runOnUiThread(() -> {
                                try {
                                    searchBusLineAdapter.setData(response.data.list);
                                    recyclerView.setVisibility(View.VISIBLE);
                                    if (response.data.list.isEmpty()) {
                                        showEmptyView();
                                    }
                                } catch (Exception e) {
                                    Log.e("BusLine", "更新搜索结果失败", e);
                                }
                            });
                        } else {
                            Log.e("BusLine", "搜索失败: " + response.msg);
                            showErrorView(response.msg);
                        }
                    } catch (Exception e) {
                        Log.e("BusLine", "处理搜索结果失败", e);
                    }
                }

                @Override
                public void onError(BusApiClient.BusApiException e) {
                    Log.e("BusLine", "API调用错误: " + e.getMessage(), e);
                    showErrorView(e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e("LineFragment", "搜索线路异常", e);
            showErrorView("搜索失败");
        }
    }

    private void showBusLineDetails(BusApiClient.BusLineInfo line) {
        // 实现跳转到线路详情页的逻辑
        Intent intent = new Intent(getActivity(), BusLineDetailActivity.class);
        intent.putExtra("line_name", line.lineName);
        intent.putExtra("start_station", line.startStation);
        intent.putExtra("end_station", line.endStation);
        startActivity(intent);
    }

    private void showEmptyView() {
        if (getView() == null) return;
        Log.d("BusLine", "显示空视图");
    }

    private void showErrorView(String message) {
        // 显示错误视图
    }
}

