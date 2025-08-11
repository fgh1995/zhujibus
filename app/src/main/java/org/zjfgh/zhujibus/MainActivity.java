package org.zjfgh.zhujibus;

import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private RecyclerView recyclerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        recyclerView = findViewById(R.id.recyclerView);
        BusApiClient client = new BusApiClient();
        client.getNearbyStations(120.234727, 29.727366, "2", 3, 5, new BusApiClient.ApiCallback<>() {
            @Override
            public void onSuccess(BusApiClient.StationAroundResponse response) {
                Toast.makeText(MainActivity.this, "stationAroundResponse" + response.code, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Toast.makeText(MainActivity.this, "e" + e, Toast.LENGTH_SHORT).show();
            }
        });
        //setupRecyclerView();
    }

    private void setupRecyclerView() {
        // 准备数据
        List<RouteItem> routes1 = Arrays.asList(
                new RouteItem("30路", "距离4站/2.6公里"),
                new RouteItem("31路", "距离2站/1.2公里")
        );

        List<RouteItem> routes2 = List.of(
                new RouteItem("长弄堂商业街临时站", "距离10站/7.2公里")
        );

        List<StationItem> stations = Arrays.asList(
                new StationItem("陶朱南路（红旗路口）", "273米", routes1),
                new StationItem("新农和公交首末站", "299米", routes2)
        );

        // 设置Adapter
        StationRouteAdapter adapter = new StationRouteAdapter(stations);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // 添加分隔线（可选）
        recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
    }
}