package org.zjfgh.zhujibus;

import android.graphics.Color;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class NoticeDetailActivity extends AppCompatActivity {
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_CONTENT = "content";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notice_detail);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String content = getIntent().getStringExtra(EXTRA_CONTENT);

        findViewById(R.id.tv_back).setOnClickListener(v -> finish());
        TextView tvTitle = findViewById(R.id.tv_title);
        tvTitle.setText(title == null ? "通知公告" : title);

        WebView webView = findViewById(R.id.web_notice);
        webView.getSettings().setJavaScriptEnabled(false);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setWebViewClient(new WebViewClient());
        webView.loadDataWithBaseURL(null, buildHtml(content), "text/html", "UTF-8", null);
    }

    private String buildHtml(String content) {
        String safeContent = content == null ? "" : content;
        return "<html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=true\">"
                + "<style>"
                + "*{color:#000000!important;box-sizing:border-box;}"
                + "body{background:#FFFFFF!important;margin:0;padding:14px;font-size:16px;line-height:1.7;}"
                + "p{margin:0 0 12px 0;}"
                + "img{max-width:100%;height:auto;}"
                + "a{color:#0d8dfb!important;}"
                + "</style>"
                + "</head><body>" + safeContent + "</body></html>";
    }
}
