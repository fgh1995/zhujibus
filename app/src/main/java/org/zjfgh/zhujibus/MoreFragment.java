package org.zjfgh.zhujibus;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.zjfgh.zhujibus.AnnouncementFormatter;
import org.zjfgh.zhujibus.AnnouncementFormatManager;
import org.zjfgh.zhujibus.AnnouncementStateProvider;
import org.zjfgh.zhujibus.TTSUtils;

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
    private View stationMarkerCard; // 站点坐标标记按钮，用于切换高亮态
    private AnnouncementFormatManager formatManager; // 报站格式管理（懒加载）

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
            // 站点坐标标记：开启后在地图上叠加官方红点 + 高德蓝点对比（默认关闭）
            list.add(new FunctionItem("站点坐标标记", "station_markers", R.drawable.ic_station_marker));
        }
        // 模拟报站：沿当前公交路线注入合成 GPS 位置，用于测试报站（再次点击停止）
        list.add(new FunctionItem("模拟报站", "simulate_report", R.drawable.ic_location));
        // 报站格式设置：语音模板（与模拟报站同风格的弹窗）
        list.add(new FunctionItem("语音格式设置", "voice_format", R.drawable.ic_voice_format));
        list.add(new FunctionItem("LED设置", "led_settings", R.drawable.ic_led_settings));
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
            params.height = dpToPx(76);
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

            // 记录站点坐标标记按钮，用于切换高亮态
            if ("station_markers".equals(item.getId())) {
                stationMarkerCard = card;
                boolean active = getActivity() instanceof BusLineDetailActivity
                        && ((BusLineDetailActivity) getActivity()).isStationMarkerActive();
                // 风格与「高德坐标」按钮一致：背景不变，激活时文字/图标变 #1FAACE
                applyStationMarkerActive(active);
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
        case "simulate_report":
            // 打开模拟报站设置页（设置时速 / 起点站停留 / 终点站停留，并开启位置模拟）
            showSimulateReportDialog();
            break;
        case "voice_format":
            // 语音格式设置：6 个报站场景的自定义模板 + 试听
            showVoiceFormatDialog();
            break;
        case "led_settings":
            // LED 设置：默认状态 + 与语音场景对齐的 LED 滚动文本模板 + 预览
            showLedSettingsDialog();
            break;
        case "station_markers":
            // 站点坐标标记：在地图上叠加官方红点 + 高德蓝点对比（再次点击关闭）
            if (getActivity() instanceof BusLineDetailActivity) {
                ((BusLineDetailActivity) getActivity()).toggleStationMarkers();
            }
            break;
    }
    }

    /** 由 BusLineDetailActivity 在切换坐标源后回调，更新按钮高亮态 */
    public void setAmapCoordActive(boolean active) {
        applyAmapCoordActive(active);
    }

    /** 由 BusLineDetailActivity 在切换站点标记显隐后回调，更新按钮高亮态 */
    public void setStationMarkerActive(boolean active) {
        applyStationMarkerActive(active);
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

    /**
     * 应用站点坐标标记按钮的视觉状态（风格与「高德坐标」按钮一致）：
     * - 默认（关闭）：背景、文字、图标均与「刷卡机」按钮一致（白字/白图标）。
     * - 开启：仅把文字与图标颜色变为 #1FAACE，背景保持不变。
     */
    private void applyStationMarkerActive(boolean active) {
        if (stationMarkerCard == null) return;
        final int activeColor = Color.parseColor("#1FAACE");
        final int defaultColor = Color.parseColor("#FFFFFF");
        int color = active ? activeColor : defaultColor;
        ImageView icon = stationMarkerCard.findViewById(R.id.button_card_icon);
        TextView text = stationMarkerCard.findViewById(R.id.button_card_text);
        if (text != null) text.setTextColor(color);
        if (icon != null) icon.setColorFilter(color);
        stationMarkerCard.setAlpha(1.0f);
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

    // ===== 报站格式设置（语音格式 / LED 设置）=====

    /** 懒加载格式管理器。 */
    private AnnouncementFormatManager getFormatManager() {
        if (formatManager == null) {
            formatManager = AnnouncementFormatManager.getInstance(requireContext());
        }
        return formatManager;
    }

    /** 打开“语音格式设置”弹窗：模式单选 + 6 个报站场景模板（可试听）。 */
    private void showVoiceFormatDialog() {
        if (!isAdded()) return;
        View fragmentRoot = getView();
        if (fragmentRoot == null) return;
        FrameLayout container = fragmentRoot.findViewById(R.id.dialog_container);
        if (container == null) return;

        container.removeAllViews();
        container.setVisibility(View.VISIBLE);

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_format_panel, container, false);

        TextView title = dialogView.findViewById(R.id.format_title);
        title.setText("语音格式设置");
        RadioGroup modeGroup = dialogView.findViewById(R.id.format_mode_group);
        LinearLayout rowsContainer = dialogView.findViewById(R.id.format_rows_container);
        ((TextView) dialogView.findViewById(R.id.format_hint_text))
                .setText("报站格式模板语法：\n普通文字直接朗读\n{变量} 替换为实时信息\n[语音包] 播放自定义音频\n点右侧 ? 看完整说明与示例");

        dialogView.findViewById(R.id.format_help_btn).setOnClickListener(v -> showTemplateHelpDialog());

        AnnouncementFormatManager fmt = getFormatManager();
        boolean custom = fmt.getMode() == AnnouncementFormatManager.Mode.CUSTOM;
        ((RadioButton) dialogView.findViewById(R.id.radio_zhuji)).setChecked(!custom);
        ((RadioButton) dialogView.findViewById(R.id.radio_custom)).setChecked(custom);
        rowsContainer.setVisibility(custom ? View.VISIBLE : View.GONE);

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isCustom = (checkedId == R.id.radio_custom);
            fmt.setMode(isCustom ? AnnouncementFormatManager.Mode.CUSTOM
                    : AnnouncementFormatManager.Mode.ZHUJI);
            rowsContainer.setVisibility(isCustom ? View.VISIBLE : View.GONE);
        });

        buildVoiceRows(dialogView.getContext(), rowsContainer);

        View close = dialogView.findViewById(R.id.format_close);
        close.setOnClickListener(v -> dismissSimDialog(container));
        container.setOnClickListener(v -> dismissSimDialog(container));
        dialogView.setOnClickListener(v -> {});

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dialogView.setLayoutParams(params);
        container.addView(dialogView);
        animatePanelIn(dialogView);
    }

    /** 打开“LED 设置”弹窗：模式单选 + 默认状态与 6 个场景的 LED 滚动文本模板（可预览到 LED 屏）。 */
    private void showLedSettingsDialog() {
        if (!isAdded()) return;
        View fragmentRoot = getView();
        if (fragmentRoot == null) return;
        FrameLayout container = fragmentRoot.findViewById(R.id.dialog_container);
        if (container == null) return;

        container.removeAllViews();
        container.setVisibility(View.VISIBLE);

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_format_panel, container, false);

        TextView title = dialogView.findViewById(R.id.format_title);
        title.setText("LED设置");
        RadioGroup modeGroup = dialogView.findViewById(R.id.format_mode_group);
        LinearLayout rowsContainer = dialogView.findViewById(R.id.format_rows_container);
        ((TextView) dialogView.findViewById(R.id.format_hint_text))
                .setText("LED 文本模板语法（仅文本，不支持语音包）：\n普通文字直接显示\n{变量} 替换为实时信息\n点右侧 ? 看完整说明与示例");

        dialogView.findViewById(R.id.format_help_btn).setOnClickListener(v -> showTemplateHelpDialog());

        AnnouncementFormatManager fmt = getFormatManager();
        boolean ledCustom = fmt.getLedMode() == AnnouncementFormatManager.Mode.CUSTOM;
        ((RadioButton) dialogView.findViewById(R.id.radio_zhuji)).setChecked(!ledCustom);
        ((RadioButton) dialogView.findViewById(R.id.radio_custom)).setChecked(ledCustom);
        rowsContainer.setVisibility(ledCustom ? View.VISIBLE : View.GONE);

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isCustom = (checkedId == R.id.radio_custom);
            fmt.setLedMode(isCustom ? AnnouncementFormatManager.Mode.CUSTOM
                    : AnnouncementFormatManager.Mode.ZHUJI);
            rowsContainer.setVisibility(isCustom ? View.VISIBLE : View.GONE);
        });

        buildLedRows(dialogView.getContext(), rowsContainer);

        View close = dialogView.findViewById(R.id.format_close);
        close.setOnClickListener(v -> dismissSimDialog(container));
        container.setOnClickListener(v -> dismissSimDialog(container));
        dialogView.setOnClickListener(v -> {});

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dialogView.setLayoutParams(params);
        container.addView(dialogView);
        animatePanelIn(dialogView);
    }

    /** 打开“报站格式模板语法”详解弹窗，逐条说明各类语法与真实示例。 */
    private void showTemplateHelpDialog() {
        if (!isAdded()) return;
        android.app.Dialog dlg = new android.app.Dialog(requireContext());
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_template_help, null);
        dlg.setContentView(view);
        dlg.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dlg.getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        view.findViewById(R.id.template_help_close).setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }

    /** 填充语音格式模板行（6 个场景，含 [语音包] 芯片与“试听”）。 */
    private void buildVoiceRows(Context ctx, ViewGroup parent) {
        AnnouncementFormatManager fmt = getFormatManager();
        LayoutInflater inflater = LayoutInflater.from(ctx);
        for (AnnouncementFormatManager.Scenario scenario : AnnouncementFormatManager.allScenarios()) {
            View row = inflater.inflate(R.layout.item_announce_format_row, parent, false);
            TextView label = row.findViewById(R.id.row_label);
            TextView desc = row.findViewById(R.id.row_desc);
            EditText edit = row.findViewById(R.id.row_edit);
            Button preview = row.findViewById(R.id.row_preview);
            LinearLayout chips = row.findViewById(R.id.row_chips);

            label.setText(scenario.label);
            desc.setText(scenario.desc);
            edit.setText(fmt.getTemplate(scenario));
            // 默认把光标放到文本末尾，避免没点过输入框就直接点芯片时插到开头
            edit.setSelection(edit.getText().length());

            edit.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    fmt.setTemplate(scenario, s.toString());
                }
            });

            preview.setText("试听");
            preview.setOnClickListener(v -> previewAnnouncement(scenario));

            for (String key : AnnouncementFormatManager.VARIABLE_KEYS) {
                addFormatChip(inflater, chips, edit, "{" + key + "}");
            }
            addFormatChip(inflater, chips, edit, "[语音包]");

            parent.addView(row);
        }
    }

    /** 填充 LED 滚动文本行：默认状态 + 与语音场景对齐的 6 个场景，仅变量芯片，“预览”应用到 LED 屏。 */
    private void buildLedRows(Context ctx, ViewGroup parent) {
        AnnouncementFormatManager fmt = getFormatManager();
        LayoutInflater inflater = LayoutInflater.from(ctx);
        parent.addView(buildLedRow(inflater, parent, null));
        for (AnnouncementFormatManager.Scenario scenario : AnnouncementFormatManager.allScenarios()) {
            parent.addView(buildLedRow(inflater, parent, scenario));
        }
    }

    private View buildLedRow(LayoutInflater inflater, ViewGroup parent, AnnouncementFormatManager.Scenario scenario) {
        AnnouncementFormatManager fmt = getFormatManager();
        View row = inflater.inflate(R.layout.item_announce_format_row, parent, false);
        TextView label = row.findViewById(R.id.row_label);
        TextView desc = row.findViewById(R.id.row_desc);
        EditText edit = row.findViewById(R.id.row_edit);
        Button preview = row.findViewById(R.id.row_preview);
        LinearLayout chips = row.findViewById(R.id.row_chips);

        boolean isDefault = (scenario == null);
        label.setText(isDefault ? "LED·默认状态" : "LED·" + scenario.label);
        desc.setText(isDefault ? "进入/初始状态 LED 滚动文本（next_station_info）"
                               : "LED 滚动文本（" + scenario.label + "），仅文本模板");
        edit.setText(isDefault ? fmt.getLedDefaultFormat() : fmt.getLedFormat(scenario));
        // 默认把光标放到文本末尾，避免没点过输入框就直接点芯片时插到开头
        edit.setSelection(edit.getText().length());

        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (isDefault) fmt.setLedDefaultFormat(s.toString());
                else fmt.setLedFormat(scenario, s.toString());
            }
        });

        preview.setText("预览");
        preview.setOnClickListener(v -> {
            AnnouncementFormatter.VariableContext ctx = getPreviewContext();
            String led = isDefault ? fmt.getLedDefaultFormat() : fmt.getLedFormat(scenario);
            String text = AnnouncementFormatter.formatTextOnly(led, ctx);
            // 直接应用到车内 LED 滚动屏预览
            if (getActivity() instanceof AnnouncementStateProvider) {
                ((AnnouncementStateProvider) getActivity()).applyLedPreview(text);
            }
            Toast.makeText(requireContext(), "已应用到 LED 滚动屏", Toast.LENGTH_SHORT).show();
        });

        for (String key : AnnouncementFormatManager.VARIABLE_KEYS) {
            addFormatChip(inflater, chips, edit, "{" + key + "}");
        }

        return row;
    }

    private void addFormatChip(LayoutInflater inflater, LinearLayout chips, EditText edit, String token) {
        Button chip = new Button(requireContext());
        chip.setText(token);
        chip.setTextSize(12f);
        chip.setTextColor(0xFFFFFFFF);
        chip.setBackgroundResource(R.drawable.chip_bg);
        chip.setMinWidth(0);
        chip.setMinHeight(0);
        chip.setPadding(12, 6, 12, 6);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 8, 0);
        chip.setLayoutParams(lp);
        // 芯片不抢焦点，保证插入时仍能读到编辑框里真实的光标位置
        chip.setFocusableInTouchMode(false);
        chip.setFocusable(false);
        chip.setOnClickListener(v -> insertToken(edit, token));
        chips.addView(chip);
    }

    /**
     * 把 {@code token} 插到编辑框当前光标处（有选区时替换选区），并把光标移到插入内容之后。
     * 这里必须实时读 getSelectionStart()/getSelectionEnd()，不能用缓存值：
     * 用户只是移动光标并不会触发 TextWatcher，缓存值会一直停留在旧位置（通常是末尾）。
     */
    private void insertToken(EditText edit, String token) {
        edit.requestFocus();
        Editable e = edit.getText();
        int len = e.length();
        int start = Math.min(Math.max(edit.getSelectionStart(), 0), len);
        int end = Math.min(Math.max(edit.getSelectionEnd(), 0), len);
        if (start > end) {
            int tmp = start;
            start = end;
            end = tmp;
        }
        e.replace(start, end, token);
        int newPos = start + token.length();
        edit.setSelection(Math.min(newPos, edit.getText().length()));
    }

    /** 试听某场景的自定义报站：优先用宿主提供的实时状态，否则回退样例数据。 */
    private void previewAnnouncement(AnnouncementFormatManager.Scenario scenario) {
        AnnouncementFormatter.VariableContext ctx = getPreviewContext();
        TTSUtils.getInstance(requireContext()).playFormattedAnnouncement(scenario, ctx);
    }

    /** 取预览/试听用的变量上下文：优先实时状态，否则样例。 */
    private AnnouncementFormatter.VariableContext getPreviewContext() {
        AnnouncementFormatter.VariableContext ctx = null;
        if (getActivity() instanceof AnnouncementStateProvider) {
            ctx = ((AnnouncementStateProvider) getActivity()).provideAnnouncementContext();
        }
        if (ctx == null) {
            ctx = new AnnouncementFormatter.VariableContext();
            ctx.lineName = "地铁1号线";
            ctx.startStation = "莘庄";
            ctx.endStation = "富锦路";
            ctx.currentStation = "上海南站";
            ctx.nextStation = "漕宝路";
            ctx.price = "6元";
            ctx.direction = "上行";
        }
        return ctx;
    }

    /** 面板入场动画（与模拟报站弹窗一致）。 */
    private void animatePanelIn(View dialogView) {
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
     * 打开模拟报站设置页（与刷卡机弹窗同风格）：可设置时速、起点站停留、终点站停留，
     * 并一键开启 / 停止位置模拟。
     */
    private void showSimulateReportDialog() {
        if (!isAdded()) return;
        View fragmentRoot = getView();
        if (fragmentRoot == null) return;
        FrameLayout container = fragmentRoot.findViewById(R.id.dialog_container);
        if (container == null) return;

        container.removeAllViews();
        container.setVisibility(View.VISIBLE);

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_simulate_report, container, false);

        // 当前可调参数（从持久化配置读取，默认值与 AmapNavigationView 一致）
        SharedPreferences simPrefs = requireContext().getSharedPreferences("sim_settings", android.content.Context.MODE_PRIVATE);
        final int[] speed = {simPrefs.getInt("sim_speed", 30)};
        final int[] startDwell = {simPrefs.getInt("sim_start_dwell", 0)};
        final int[] terminalDwell = {simPrefs.getInt("sim_terminal_dwell", 0)};
        final int[] arrivalDwell = {simPrefs.getInt("sim_arrival_dwell", 0)};

        EditText speedValue = dialogView.findViewById(R.id.sim_speed_value);
        EditText startValue = dialogView.findViewById(R.id.sim_start_value);
        EditText terminalValue = dialogView.findViewById(R.id.sim_terminal_value);
        EditText arrivalValue = dialogView.findViewById(R.id.sim_arrival_value);
        speedValue.setText(String.valueOf(speed[0]));
        startValue.setText(String.valueOf(startDwell[0]));
        terminalValue.setText(String.valueOf(terminalDwell[0]));
        arrivalValue.setText(String.valueOf(arrivalDwell[0]));

        bindSimStepper(dialogView, R.id.sim_speed_minus, R.id.sim_speed_plus, speedValue, speed, 1);
        bindSimStepper(dialogView, R.id.sim_start_minus, R.id.sim_start_plus, startValue, startDwell, 0);
        bindSimStepper(dialogView, R.id.sim_terminal_minus, R.id.sim_terminal_plus, terminalValue, terminalDwell, 0);
        bindSimStepper(dialogView, R.id.sim_arrival_minus, R.id.sim_arrival_plus, arrivalValue, arrivalDwell, 0);
        // 直接输入时同步回内存值（夹紧在合理范围由 +/- / 开启时统一处理）
        addSimValueWatcher(speedValue, speed);
        addSimValueWatcher(startValue, startDwell);
        addSimValueWatcher(terminalValue, terminalDwell);
        addSimValueWatcher(arrivalValue, arrivalDwell);

        Button toggle = dialogView.findViewById(R.id.sim_toggle);
        View close = dialogView.findViewById(R.id.sim_close);

        final boolean[] simulating = {getActivity() instanceof BusLineDetailActivity
                && ((BusLineDetailActivity) getActivity()).isSimulating()};
        updateSimToggleText(toggle, simulating[0]);

        toggle.setOnClickListener(v -> {
            BusLineDetailActivity act = (getActivity() instanceof BusLineDetailActivity)
                    ? (BusLineDetailActivity) getActivity() : null;
            if (act == null) return;
            // 先按输入框当前内容夹紧并回写，确保直接输入的数值生效
            syncSimValue(speedValue, speed, 1);
            syncSimValue(startValue, startDwell, 0);
            syncSimValue(terminalValue, terminalDwell, 0);
            syncSimValue(arrivalValue, arrivalDwell, 0);
            saveSimSettings(speed[0], startDwell[0], terminalDwell[0], arrivalDwell[0]);
            if (simulating[0]) {
                act.stopGpsSimulation();
                simulating[0] = false;
            } else {
                act.startGpsSimulation(speed[0], startDwell[0], terminalDwell[0], arrivalDwell[0]);
                simulating[0] = true;
            }
            updateSimToggleText(toggle, simulating[0]);
        });

        close.setOnClickListener(v -> {
            // 关闭前把当前输入夹紧并持久化
            syncSimValue(speedValue, speed, 1);
            syncSimValue(startValue, startDwell, 0);
            syncSimValue(terminalValue, terminalDwell, 0);
            syncSimValue(arrivalValue, arrivalDwell, 0);
            saveSimSettings(speed[0], startDwell[0], terminalDwell[0], arrivalDwell[0]);
            dismissSimDialog(container);
        });
        // 点击外部关闭
        container.setOnClickListener(v -> dismissSimDialog(container));
        dialogView.setOnClickListener(v -> {});

        // 填充面板并随内容垂直滚动（与地图设置页面一致：外层 dialog_container 提供 nav_panel_bg 边框）
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        dialogView.setLayoutParams(params);
        container.addView(dialogView);

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

    /** 步进器：点击 −/+ 按输入框当前数值增减并刷新显示，下限为 min（不设上限）。 */
    private void bindSimStepper(View root, int minusId, int plusId, EditText valueView,
                               final int[] holder, int min) {
        View minus = root.findViewById(minusId);
        View plus = root.findViewById(plusId);
        if (minus != null) {
            minus.setOnClickListener(v -> {
                syncSimValue(valueView, holder, min);
                if (holder[0] > min) {
                    holder[0]--;
                    valueView.setText(String.valueOf(holder[0]));
                }
            });
        }
        if (plus != null) {
            plus.setOnClickListener(v -> {
                syncSimValue(valueView, holder, min);
                holder[0]++;
                valueView.setText(String.valueOf(holder[0]));
            });
        }
    }

    /** 读取输入框当前数值，下限夹紧到 min（不设上限）后回写并同步到 holder。 */
    private void syncSimValue(EditText et, int[] holder, int min) {
        int v = holder[0];
        try {
            v = Integer.parseInt(et.getText().toString().trim());
        } catch (NumberFormatException ignored) {
        }
        if (v < min) v = min;
        holder[0] = v;
        if (!et.getText().toString().equals(String.valueOf(v))) {
            et.setText(String.valueOf(v));
        }
    }

    /** 持久化模拟报站配置（SharedPreferences：sim_settings）。 */
    private void saveSimSettings(int speed, int startDwell, int terminalDwell, int arrivalDwell) {
        SharedPreferences prefs = requireContext().getSharedPreferences("sim_settings", android.content.Context.MODE_PRIVATE);
        prefs.edit()
                .putInt("sim_speed", speed)
                .putInt("sim_start_dwell", startDwell)
                .putInt("sim_terminal_dwell", terminalDwell)
                .putInt("sim_arrival_dwell", arrivalDwell)
                .apply();
    }

    /** 监听直接输入，把数值同步回 holder（夹紧在 +/- / 开启时统一处理）。 */
    private void addSimValueWatcher(EditText et, final int[] holder) {
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                try {
                    holder[0] = Integer.parseInt(s.toString().trim());
                } catch (NumberFormatException ignored) {
                }
            }
        });
    }

    /** 更新「开启 / 停止」按钮文案，模拟中显示停止。 */
    private void updateSimToggleText(Button btn, boolean simulating) {
        if (btn == null) return;
        btn.setText(simulating ? "停止位置模拟" : "开启位置模拟");
    }

    /** 关闭模拟设置弹窗（带回弹动画）。 */
    private void dismissSimDialog(FrameLayout container) {
        View dv = container.getChildAt(0);
        if (dv != null) {
            dv.animate()
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
        } else {
            container.removeAllViews();
            container.setVisibility(View.GONE);
        }
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