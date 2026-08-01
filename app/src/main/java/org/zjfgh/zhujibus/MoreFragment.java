package org.zjfgh.zhujibus;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.gridlayout.widget.GridLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MoreFragment extends Fragment {

    private static final String TAG = "MoreFragment";
    private GridLayout gridLayout;
    private String priceText;
    private Handler timeHandler = new Handler();
    private int timeDisplayPhase = 0; // 0:日期, 1:时间
    private long dateDisplayEndTime = 0;
    private long timeDisplayEndTime = 0;
    private TextView cardReaderTime;
    private Runnable timeUpdateRunnable;

    public static MoreFragment newInstance() {
        return new MoreFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Log.d(TAG, "========== onCreateView 开始 ==========");

        // 加载 XML 布局
        View rootView = inflater.inflate(R.layout.fragment_more, container, false);

        // 获取 XML 中定义的 GridLayout
        gridLayout = rootView.findViewById(R.id.gl_button_container);
        Log.d(TAG, "找到 GridLayout: " + gridLayout);
        Log.d(TAG, "GridLayout 列数: " + gridLayout.getColumnCount());

        // 获取功能列表
        List<FunctionItem> functionList = getFunctionList();
        Log.d(TAG, "功能列表大小: " + functionList.size());

        // 动态添加卡片按钮
        for (int i = 0; i < functionList.size(); i++) {
            FunctionItem item = functionList.get(i);
            Log.d(TAG, "正在创建第 " + (i + 1) + " 个按钮: " + item.getName());
            View card = createCardButton(item);
            if (card != null) {
                gridLayout.addView(card);
                Log.d(TAG, "✓ 成功添加按钮: " + item.getName());
            } else {
                Log.e(TAG, "✗ 创建按钮失败: " + item.getName());
            }
        }

        Log.d(TAG, "GridLayout 子视图数量: " + gridLayout.getChildCount());
        Log.d(TAG, "========== onCreateView 结束 ==========");

        return rootView;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopTimeDisplay();
    }

    /**
     * 获取功能列表（可动态从网络或数据库获取）
     */
    private List<FunctionItem> getFunctionList() {
        List<FunctionItem> list = new ArrayList<>();
        list.add(new FunctionItem("刷卡机", "card_reader", R.drawable.icon_card_reader));
        return list;
    }

    /**
     * 创建卡片按钮（加载 button_card.xml 布局）
     */
    private View createCardButton(FunctionItem item) {
        try {
            // 加载卡片布局
            View card = LayoutInflater.from(requireContext())
                    .inflate(R.layout.button_card, null, false);

            // 设置卡片在 GridLayout 中的布局参数
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;  // 让GridLayout自动分配宽度
            params.height = dpToPx(70);
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);  // 每列权重相等
            params.setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));
            card.setLayoutParams(params);

            // 设置图标
            ImageView icon = card.findViewById(R.id.button_card_icon);
            if (icon != null) {
                icon.setImageResource(item.getIconRes());
            }

            // 设置文字
            TextView text = card.findViewById(R.id.button_card_text);
            if (text != null) {
                text.setText(item.getName());
            }

            // 设置点击事件
            card.setOnClickListener(v -> {
                handleFunctionClick(item);
            });

            return card;

        } catch (Exception e) {
            Log.e(TAG, "createCardButton() 异常: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 处理功能点击
     */
    private void handleFunctionClick(FunctionItem item) {
        switch (item.getId()) {
            case "card_reader":
                // 跳转刷卡机
                showCardReaderDialogAtViewView();
                break;
        }
    }

    private void showCardReaderDialogAtViewView() {
        if (!isAdded()) return;
        View fragmentRoot = getView();
        if (fragmentRoot == null) return;
        FrameLayout container = fragmentRoot.findViewById(R.id.dialog_container);
        if (container == null) return;

        container.removeAllViews();
        container.setVisibility(View.VISIBLE);

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_card_reader, container, false);

        TextView cardTicket = dialogView.findViewById(R.id.card_ticket);
        Typeface digitalTypeface = Typeface.createFromAsset(requireActivity().getAssets(), "fonts/DS-DIGIB-2.ttf");
        cardTicket.setTypeface(digitalTypeface, Typeface.NORMAL);
        cardTicket.setText(priceText);

        // 获取时间显示控件并启动定时器
        cardReaderTime = dialogView.findViewById(R.id.card_reader_time);
        cardReaderTime.setTypeface(digitalTypeface, Typeface.NORMAL);
        startTimeDisplay(); // 启动时间显示

        View paymentSuccessfulView = dialogView.findViewById(R.id.payment_successful);
        if (paymentSuccessfulView != null) {
            paymentSuccessfulView.setOnClickListener(v -> {
                TTSUtils ttsUtils = TTSUtils.getInstance(getActivity());
                if (ttsUtils != null) {
                    ttsUtils.playScanCodeSuccessSound();
                }
            });
        }

        View cardAcceptedView = dialogView.findViewById(R.id.card_accepted);
        if (cardAcceptedView != null) {
            cardAcceptedView.setOnClickListener(v -> {
                TTSUtils ttsUtils = TTSUtils.getInstance(getActivity());
                if (ttsUtils != null) {
                    ttsUtils.playCardSwipeSuccessSound();
                }
            });
        }

        // 设置居中
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.CENTER;

        int marginHorizontal = (int) (12 * getResources().getDisplayMetrics().density);
        params.leftMargin = marginHorizontal;
        params.rightMargin = marginHorizontal;

        dialogView.setLayoutParams(params);
        container.addView(dialogView);

        // 点击外部关闭
        container.setOnClickListener(v -> {
            stopTimeDisplay(); // 停止时间更新
            dialogView.animate()
                    .alpha(0)
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .setDuration(200)
                    .withEndAction(() -> {
                        container.removeAllViews();
                        container.setVisibility(View.GONE);
                        container.setAlpha(1f);
                        container.setScaleX(1f);
                        container.setScaleY(1f);
                    })
                    .start();
        });

        dialogView.setOnClickListener(v -> {});

        // 入场动画
        dialogView.setAlpha(0f);
        dialogView.setScaleX(0.9f);
        dialogView.setScaleY(0.9f);
        dialogView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /**
     * 启动时间显示（方案2最终版）
     * 日期显示2秒，时间显示3秒，时间每秒刷新
     */
    private void startTimeDisplay() {
        stopTimeDisplay();

        timeUpdateRunnable = new Runnable() {
            private int displayState = 0; // 0:日期, 1:时间显示中
            private int timeSecondsCount = 0; // 时间已显示秒数
            private long lastTimeUpdate = 0; // 上次更新时间

            @Override
            public void run() {
                if (cardReaderTime == null) return;

                long now = System.currentTimeMillis();

                if (displayState == 0) {
                    // 显示日期
                    SimpleDateFormat dateFormatter = new SimpleDateFormat("yy.MM.dd", Locale.getDefault());
                    cardReaderTime.setText(dateFormatter.format(new Date()));
                    displayState = 1;
                    timeSecondsCount = 0;
                    // 2秒后切换到时间
                    timeHandler.postDelayed(this, 2000);
                } else {
                    // 时间显示模式
                    if (timeSecondsCount < 3) {
                        // 还没到3秒，更新时间
                        SimpleDateFormat timeFormatter = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                        cardReaderTime.setText(timeFormatter.format(new Date()));
                        timeSecondsCount++;
                        lastTimeUpdate = now;
                        // 1秒后继续
                        timeHandler.postDelayed(this, 1000);
                    } else {
                        // 已显示3秒，直接切换回日期（不更新时间）
                        displayState = 0;
                        // 立即切换
                        timeHandler.post(this);
                    }
                }
            }
        };

        timeHandler.post(timeUpdateRunnable);
    }

    /**
     * 停止时间显示
     */
    private void stopTimeDisplay() {
        if (timeUpdateRunnable != null) {
            timeHandler.removeCallbacks(timeUpdateRunnable);
            timeUpdateRunnable = null;
        }
        timeDisplayPhase = 0;
    }

    /**
     * dp转px
     */
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    public void updatePriceText(String priceText) {
        this.priceText = priceText;
    }

    /**
     * 功能项数据模型
     */
    static class FunctionItem {
        private String name;
        private String id;
        private int iconRes;

        public FunctionItem(String name, String id, int iconRes) {
            this.name = name;
            this.id = id;
            this.iconRes = iconRes;
        }

        public String getName() { return name; }
        public String getId() { return id; }
        public int getIconRes() { return iconRes; }
    }
}