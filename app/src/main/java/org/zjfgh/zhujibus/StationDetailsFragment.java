package org.zjfgh.zhujibus;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

import java.util.List;
import java.util.Objects;

public class StationDetailsFragment extends DialogFragment {
    private BusApiClient busApiClient;
    private RecyclerView recyclerView;
    private BusStationAdapter adapter;
    private String currentStationName = "财税大楼";

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
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Log.e("-BusInfo-", Objects.requireNonNull(e.getMessage()));
            }
        });
    }
}