package org.zjfgh.zhujibus;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class MessageFragment extends Fragment {

    private static final String ARG_NOTIFICATION_TEXT = "notification_text";
    private String notificationText;

    public static MessageFragment newInstance(String notificationText) {
        MessageFragment fragment = new MessageFragment();
        Bundle args = new Bundle();
        args.putString(ARG_NOTIFICATION_TEXT, notificationText);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            notificationText = getArguments().getString(ARG_NOTIFICATION_TEXT);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (notificationText != null && !notificationText.isEmpty()) {
            // 创建外层容器，实现圆角裁剪
            FrameLayout containerLayout = new FrameLayout(requireContext());

            // 设置圆角背景
            android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
            drawable.setColor(0xFF3A3F5C); // 背景色
            drawable.setCornerRadius(16f); // 圆角半径，单位是像素
            drawable.setStroke(2, 0xFF1A1D2E); // 边框
            containerLayout.setBackground(drawable);

            // 设置内边距
            int paddingPx = (int) (16 * getResources().getDisplayMetrics().density);
            containerLayout.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);

            // 创建 WebView
            WebView webView = new WebView(requireContext());
            webView.getSettings().setJavaScriptEnabled(false);
            webView.getSettings().setLoadWithOverviewMode(true);
            webView.getSettings().setUseWideViewPort(true);

            // 设置 WebView 背景透明
            webView.setBackgroundColor(0x00000000);

            // 设置 WebViewClient
            webView.setWebViewClient(new WebViewClient());

            // 构造完整 HTML，强制所有文本为白色
            String fullHtml = "<html>" +
                    "<head>" +
                    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=true\">" +
                    "<style>" +
                    "* {" +
                    "   color: #FFFFFF !important;" +
                    "}" +
                    "body {" +
                    "   color: #FFFFFF !important;" +
                    "   background-color: transparent !important;" +
                    "   padding: 0px;" +
                    "   margin: 0px;" +
                    "   font-size: 16px;" +
                    "   line-height: 1.4;" +
                    "}" +
                    "img {" +
                    "   max-width: 100%;" +
                    "   height: auto;" +
                    "   display: block;" +
                    "   margin: 10px 0;" +
                    "}" +
                    "a {" +
                    "   color: #4CAF50 !important;" +
                    "}" +
                    "p, div, span, h1, h2, h3, h4, h5, h6, li, strong, em, b, i {" +
                    "   color: #FFFFFF !important;" +
                    "}" +
                    "</style>" +
                    "</head>" +
                    "<body>" + notificationText + "</body>" +
                    "</html>";

            webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null);

            // 将 WebView 添加到容器
            containerLayout.addView(webView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));

            return containerLayout;
        } else {
            // 无公告时的处理（同样添加圆角）
            FrameLayout frameLayout = new FrameLayout(requireContext());

            // 设置圆角背景
            android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
            drawable.setColor(0xFF3A3F5C);
            drawable.setCornerRadius(16f);
            drawable.setStroke(2, 0xFF1A1D2E);
            frameLayout.setBackground(drawable);

            frameLayout.setPadding(16, 16, 16, 16);

            TextView textView = new TextView(requireContext());
            textView.setText("暂无公告消息");
            textView.setTextSize(16f);
            textView.setTextColor(0xFFFFFFFF);
            textView.setGravity(Gravity.CENTER);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.CENTER;
            textView.setLayoutParams(params);
            frameLayout.addView(textView);
            return frameLayout;
        }
    }
}