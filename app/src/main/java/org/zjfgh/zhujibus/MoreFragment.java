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

import com.amap.api.services.core.AMapException;

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
    private View amapCoordCard; // 高德坐标切换按钮，用于切换高亮态

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
        // 仅诸暨市显示"站点坐标来源：高德"切换按钮（其他城市坐标本就来自高德）
        BusRegion region = new RegionManager(requireContext()).getSelectedRegion();
        if (region != null && region.adCode.startsWith("330681")) {
            list.add(new FunctionItem("使用高德站点坐标", "amap_coord", R.drawable.ic_directions));
        }
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
                try {
                    handleFunctionClick(item);
                } catch (AMapException e) {
                    throw new RuntimeException(e);
                }
            });

            // 记录高德坐标按钮，用于切换高亮态
            if ("amap_coord".equals(item.getId())) {
                amapCoordCard = card;
                boolean active = getActivity() instanceof BusLineDetailActivity
                        && ((BusLineDetailActivity) getActivity()).isAmapCoordActive();
                // 默认与「刷卡机」按钮完全一致（背景/文字/图标同 button_card.xml）；
                // 激活时仅将文字与图标颜色变为 #1FAACE，背景保持不变。
                applyAmapCoordActive(active);
            }

            return card;

        } catch (Exception e) {
            Log.e(TAG, "createCardButton() 异常: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 处理功能点击
     */
    private void handleFunctionClick(FunctionItem item) throws AMapException {
    switch (item.getId()) {
        case "card_reader":
            // 跳转刷卡机
            showCardReaderDialogAtViewView();
            break;
        case "amap_coord":
            // 切换站点坐标来源（高德 / 诸暨官方）
            if (getActivity() instanceof BusLineDetailActivity) {
                ((BusLineDetailActivity) getActivity()).toggleAmapCoordSource();
            }
            break;
    }
    }

    /** 由 BusLineDetailActivity 在切换坐标源后回调，更新按钮高亮态 */
    public void setAmapCoordActive(boolean active) {
        applyAmapCoordActive(active);
    }

    /**
     * 应用高德坐标按钮的视觉状态：
     * - 默认（未激活）：背景、文字、图标均与「刷卡机」按钮一致（白字/白图标，背景 nav_panel_bg）。
     * - 激活：仅把文字与图标颜色变为 #1FAACE，背景保持不变。
     */
    private void applyAmapCoordActive(boolean active) {
        if (amapCoordCard == null) return;
        final int activeColor = Color.parseColor("#1FAACE");
        final int defaultColor = Color.parseColor("#FFFFFF");
        int color = active ? activeColor : defaultColor;
        ImageView icon = amapCoordCard.findViewById(R.id.button_card_icon);
        TextView text = amapCoordCard.findViewById(R.id.button_card_text);
        if (text != null) text.setTextColor(color);
        if (icon != null) icon.setColorFilter(color);
        amapCoordCard.setAlpha(1.0f); // 背景始终与刷卡机一致，不做透明度变化
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