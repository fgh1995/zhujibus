package org.zjfgh.zhujibus;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private TextView tv_search_line;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        recyclerView = findViewById(R.id.recyclerView);
        tv_search_line = findViewById(R.id.tv_search_line);
        BusApiClient client = new BusApiClient();
        TTSUtils.getInstance(this);
        client.getNearbyStations(120.235555, 29.713397, "2", 3, 5, new BusApiClient.ApiCallback<>() {
            @Override
            public void onSuccess(BusApiClient.StationLineAroundResponse response) {
                List<StationItem> stations = new ArrayList<>();
                for (int i = 0; i < response.data.size(); i++) {
                    List<RouteItem> routes1 = new ArrayList<>();
                    List<BusApiClient.DistanceData> distanceDataList = response.data.get(i).distanceData;
                    if (distanceDataList != null) {
                        for (int j = 0; j < response.data.get(i).distanceData.size(); j++) {
                            BusApiClient.DistanceData distanceData = response.data.get(i).distanceData.get(j);
                            routes1.add(new RouteItem(distanceData.lineName, "距离" + distanceData.nextNumber + "站/"
                                    + DistanceUtils.formatDistance(distanceData.distance), distanceData.startStation, distanceData.endStation, distanceData.arrivalTime));
                        }
                    }
                    stations.add(new StationItem(response.data.get(i).stationName, DistanceUtils.formatDistance(response.data.get(i).distance), routes1));
                }
                // 设置Adapter
                StationRouteAdapter adapter = new StationRouteAdapter(stations);
                recyclerView.setLayoutManager(new LinearLayoutManager(MainActivity.this));
                recyclerView.setAdapter(adapter);
                // 添加分隔线（可选）
                recyclerView.addItemDecoration(new DividerItemDecoration(MainActivity.this, DividerItemDecoration.VERTICAL));
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Toast.makeText(MainActivity.this, "e" + e, Toast.LENGTH_SHORT).show();
            }
        });
        tv_search_line.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, BusRouteSearchActivity.class);
                startActivity(intent);
            }
        });
    }
}