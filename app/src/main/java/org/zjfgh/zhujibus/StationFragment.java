package org.zjfgh.zhujibus;


import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

// StationFragment.java
public class StationFragment extends Fragment {
    private SearchBusStationAdapter searchBusStationAdapter;
    private RecyclerView recyclerView;
    BusApiClient client;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.line_fragment, container, false);
        TextView title = view.findViewById(R.id.title);
        title.setText("站点");
        recyclerView = view.findViewById(R.id.rv_bus_line_results);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        // 添加分割线
        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(
                requireContext(),
                LinearLayoutManager.VERTICAL  // 垂直方向的分割线
        );
        recyclerView.addItemDecoration(dividerItemDecoration);
        searchBusStationAdapter = new SearchBusStationAdapter();
        recyclerView.setAdapter(searchBusStationAdapter);

        // 设置点击监听
        searchBusStationAdapter.setOnItemClickListener(stationSimpleInfo -> {
            showBusStationDetails(stationSimpleInfo.stationName);
        });

        client = new BusApiClient();
        return view;
    }

    private void showBusStationDetails(String stationName) {
        StationDetailsFragment stationDetailsFragment = new StationDetailsFragment(stationName);
        stationDetailsFragment.show(requireActivity().getSupportFragmentManager(), "dialog_tag");
    }

    public void searchStations(String keyword) {
        client = new BusApiClient();
        client.searchStations(keyword, 1, 10, new BusApiClient.ApiCallback<>() {
            @Override
            public void onSuccess(BusApiClient.StationSearchResponse response) {
                if ("200".equals(response.returnFlag)) {
                    requireActivity().runOnUiThread(() -> {
                        searchBusStationAdapter.setData(response.data.list);
                    });
                } else {
                    Log.e("BusInfo", "搜索站点失败-状态码错误： " + response.code);
                }
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Log.e("BusInfo", "搜索站点失败-请求异常： " + e.getMessage());
            }
        });
    }
}

