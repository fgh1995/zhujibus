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

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import java.util.List;
import java.util.Objects;

public class StationDetailsFragment extends DialogFragment {
    private BusApiClient busApiClient;
    private RecyclerView recyclerView;
    private BusStationAdapter adapter;
    private final String currentStationName;

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
        loadStationData();
        return view;
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

    private void loadStationData() {
        busApiClient.queryStationInfo(currentStationName, new BusApiClient.ApiCallback<>() {
            @Override
            public void onSuccess(BusApiClient.StationInfoResponse response) {
                List<BusApiClient.StationLineInfo> busLineItems = response.data;
                adapter.setData(busLineItems);
                StringBuilder lineIdsBuilder = new StringBuilder();
                StringBuilder stationIdsBuilder = new StringBuilder();

                for (int i = 0; i < busLineItems.size(); i++) {
                    if (busLineItems.get(i).up != null) {
                        if (lineIdsBuilder.length() > 0) {
                            lineIdsBuilder.append(",");
                            stationIdsBuilder.append(",");
                        }
                        lineIdsBuilder.append(busLineItems.get(i).up.lineId);
                        stationIdsBuilder.append(busLineItems.get(i).up.stationId);
                    }
                    if (busLineItems.get(i).down != null) {
                        if (lineIdsBuilder.length() > 0) {
                            lineIdsBuilder.append(",");
                            stationIdsBuilder.append(",");
                        }
                        lineIdsBuilder.append(busLineItems.get(i).down.lineId);
                        stationIdsBuilder.append(busLineItems.get(i).down.stationId);
                    }
                }
                Log.w("-BusInfo-", lineIdsBuilder.toString());
                Log.w("-BusInfo-", stationIdsBuilder.toString());
                busApiClient.queryStationVehicleDynamic(lineIdsBuilder.toString(), stationIdsBuilder.toString(), new BusApiClient.ApiCallback<>() {
                    @Override
                    public void onSuccess(BusApiClient.StationVehicleDynamicResponse response) {
                        for (int i = 0; i < response.data.size(); i++) {
                            Log.w("-BusInfo-", response.data.get(i).lineId);
                            Log.w("-BusInfo-", response.data.get(i).stationId);
                            Log.w("-BusInfo-", response.data.get(i).distance + "");
                        }
                    }

                    @Override
                    public void onError(BusApiClient.BusApiException e) {

                    }
                });
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Log.e("-BusInfo-", Objects.requireNonNull(e.getMessage()));
            }
        });
    }
}