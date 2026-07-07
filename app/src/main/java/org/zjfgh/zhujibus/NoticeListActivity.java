package org.zjfgh.zhujibus;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class NoticeListActivity extends AppCompatActivity {
    private static final String TAG = "NoticeListActivity";
    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private BusApiClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notice_list);

        findViewById(R.id.tv_back).setOnClickListener(v -> finish());
        recyclerView = findViewById(R.id.rv_notice);
        tvEmpty = findViewById(R.id.tv_empty);
        client = new BusApiClient();

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        loadNotices();
    }

    private void loadNotices() {
        client.getNoticeList(1, 20, new BusApiClient.ApiCallback<BusApiClient.NoticeListResponse>() {
            @Override
            public void onSuccess(BusApiClient.NoticeListResponse response) {
                if (response == null || !"200".equals(response.code) || response.data == null || response.data.list == null) {
                    showEmpty();
                    return;
                }

                List<BusApiClient.BusAnnouncement> notices = new ArrayList<>(response.data.list);
                if (notices.isEmpty()) {
                    showEmpty();
                    return;
                }

                tvEmpty.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                NoticeAdapter adapter = new NoticeAdapter(notices);
                adapter.setOnItemClickListener(notice -> {
                    Intent intent = new Intent(NoticeListActivity.this, NoticeDetailActivity.class);
                    intent.putExtra(NoticeDetailActivity.EXTRA_TITLE, notice.title);
                    intent.putExtra(NoticeDetailActivity.EXTRA_CONTENT, notice.publishContent);
                    startActivity(intent);
                });
                recyclerView.setAdapter(adapter);
            }

            @Override
            public void onError(BusApiClient.BusApiException e) {
                Log.w(TAG, "公告列表请求失败", e);
                showEmpty();
                Toast.makeText(NoticeListActivity.this, "公告加载失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showEmpty() {
        recyclerView.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.VISIBLE);
    }
}
