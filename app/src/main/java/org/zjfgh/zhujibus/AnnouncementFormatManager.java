package org.zjfgh.zhujibus;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报站格式管理器：负责「报站格式」配置的数据模型、默认值与持久化。
 *
 * <p>两种模式：
 * <ul>
 *     <li>{@link Mode#ZHUJI} 诸暨公交报站格式：使用 {@link TTSUtils} 内置硬编码的诸暨报站流程（不变）。</li>
 *     <li>{@link Mode#CUSTOM} 自定义格式：使用用户为 6 种场景各自填写的模板（见 {@link Scenario}）。</li>
 * </ul>
 *
 * <p>模板语法：
 * <ul>
 *     <li>{@code {变量名}} —— 在播报时替换为对应变量值（见 {@link #VARIABLE_KEYS}）。</li>
 *     <li>{@code [语音包名]} —— 使用指定语音包播放；当前语音包架构尚未接入目录读取，
 *          {@link AnnouncementFormatter} 一律判定为「找不到」，做好架构占位（详见 {@link AnnouncementFormatter.VoicePackResolver}）。</li>
 * </ul>
 */
public class AnnouncementFormatManager {

    // ===== 模式 =====
    public enum Mode {
        ZHUJI("诸暨公交报站格式"),
        CUSTOM("自定义格式");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    // ===== 报站场景 =====
    public enum Scenario {
        START_STATION("起点站格式", "车辆从起点站发车时的报站"),
        START_EN_ROUTE("起点站途中格式", "起点站开往下一站途中（起步后）的报站"),
        ARRIVED("已到站格式", "到达某站时的报站"),
        NEXT_STATION("下一站格式", "提示前方即将到站"),
        NEXT_IS_TERMINAL("下一站是终点站格式", "前方到站且该车为终点站"),
        ARRIVED_TERMINAL("已到站（终点站格式）", "到达终点站时的报站");

        public final String label;
        public final String desc;

        Scenario(String label, String desc) {
            this.label = label;
            this.desc = desc;
        }
    }

    // ===== 变量键（同时作为模板中的占位名与插入按钮文案）=====
    public static final String VAR_LINE = "线路名";
    public static final String VAR_START = "起点站";
    public static final String VAR_END = "终点站";
    public static final String VAR_CURRENT = "当前站";
    public static final String VAR_NEXT = "下一站";
    public static final String VAR_PRICE = "票价";
    public static final String VAR_DIRECTION = "方向";

    /** 变量插入按钮展示顺序（含中文键名）。 */
    public static final List<String> VARIABLE_KEYS = new ArrayList<>();

    static {
        VARIABLE_KEYS.add(VAR_LINE);
        VARIABLE_KEYS.add(VAR_START);
        VARIABLE_KEYS.add(VAR_END);
        VARIABLE_KEYS.add(VAR_CURRENT);
        VARIABLE_KEYS.add(VAR_NEXT);
        VARIABLE_KEYS.add(VAR_PRICE);
        VARIABLE_KEYS.add(VAR_DIRECTION);
    }

    // ===== 默认自定义模板（切换到自定义时展示的可编辑范例）=====
    private static final Map<Scenario, String> DEFAULT_TEMPLATES = new LinkedHashMap<>();

    static {
        DEFAULT_TEMPLATES.put(Scenario.START_STATION,
                "欢迎乘坐{线路名}，本次列车从{起点站}开往{终点站}，下一站{下一站}，请做好下车准备");
        DEFAULT_TEMPLATES.put(Scenario.START_EN_ROUTE,
                "车辆起步，下一站{下一站}");
        DEFAULT_TEMPLATES.put(Scenario.ARRIVED,
                "到达{当前站}，下一站{下一站}");
        DEFAULT_TEMPLATES.put(Scenario.NEXT_STATION,
                "前方到站{下一站}");
        DEFAULT_TEMPLATES.put(Scenario.NEXT_IS_TERMINAL,
                "前方到站{下一站}，{下一站}是终点站，请携带好随身物品下车");
        DEFAULT_TEMPLATES.put(Scenario.ARRIVED_TERMINAL,
                "终点站{当前站}到了，请全部下车，欢迎再次乘坐");
    }

    // ===== LED 滚屏格式（与语音场景一一对齐，外加一个"默认状态"，仅控制车内 LED 横屏滚动文本 next_station_info）=====
    // 每个 Scenario 对应一份 LED 文本模板；KEY_LED_DEFAULT 为进入/初始时的默认状态。
    // 默认均为空串：自定义格式下若对应模板未填写，则 LED 显示"请在报站设置中添加滚动模版"。
    public static final String KEY_LED_DEFAULT = "announce_led_default";
    public static final String KEY_LED_PREFIX = "announce_led_";

    // ===== 持久化 =====
    private static final String PREFS_NAME = "announce_format_prefs";
    private static final String KEY_MODE = "announce_format_mode";
    private static final String KEY_TEMPLATE_PREFIX = "announce_template_";

    private static AnnouncementFormatManager sInstance;

    private final SharedPreferences prefs;

    public static synchronized AnnouncementFormatManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new AnnouncementFormatManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private AnnouncementFormatManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ===== 模式读写 =====
    public Mode getMode() {
        String v = prefs.getString(KEY_MODE, Mode.ZHUJI.name());
        try {
            return Mode.valueOf(v);
        } catch (Exception e) {
            return Mode.ZHUJI;
        }
    }

    public void setMode(Mode mode) {
        prefs.edit().putString(KEY_MODE, mode.name()).apply();
    }

    // ===== LED 模式读写（与语音格式模式相互独立，互不干扰）=====
    private static final String KEY_LED_MODE = "announce_led_mode";

    public Mode getLedMode() {
        String v = prefs.getString(KEY_LED_MODE, Mode.ZHUJI.name());
        try {
            return Mode.valueOf(v);
        } catch (Exception e) {
            return Mode.ZHUJI;
        }
    }

    public void setLedMode(Mode mode) {
        prefs.edit().putString(KEY_LED_MODE, mode.name()).apply();
    }

    // ===== 模板读写 =====
    public String getTemplate(Scenario scenario) {
        String saved = prefs.getString(KEY_TEMPLATE_PREFIX + scenario.name(), null);
        if (saved == null) {
            return DEFAULT_TEMPLATES.get(scenario);
        }
        return saved;
    }

    public void setTemplate(Scenario scenario, String template) {
        prefs.edit().putString(KEY_TEMPLATE_PREFIX + scenario.name(), template).apply();
    }

    /** 供 UI 展示的默认模板（只读）。 */
    public String getDefaultTemplate(Scenario scenario) {
        return DEFAULT_TEMPLATES.get(scenario);
    }

    // ===== LED 滚屏格式读写（按场景对齐 + 默认状态）=====
    /** 默认状态 LED 模板（进入/初始时）。默认空串。 */
    public String getLedDefaultFormat() {
        String saved = prefs.getString(KEY_LED_DEFAULT, null);
        return saved != null ? saved : "";
    }

    public void setLedDefaultFormat(String fmt) {
        prefs.edit().putString(KEY_LED_DEFAULT, fmt).apply();
    }

    /** 某语音场景对应的 LED 模板（文本模板，支持 {变量}，不含语音包）。默认空串。 */
    public String getLedFormat(Scenario scenario) {
        String saved = prefs.getString(KEY_LED_PREFIX + scenario.name(), null);
        return saved != null ? saved : "";
    }

    public void setLedFormat(Scenario scenario, String fmt) {
        prefs.edit().putString(KEY_LED_PREFIX + scenario.name(), fmt).apply();
    }

    /** 全部 6 个场景，供 UI 遍历生成。 */
    public static List<Scenario> allScenarios() {
        List<Scenario> list = new ArrayList<>();
        for (Scenario s : Scenario.values()) {
            list.add(s);
        }
        return list;
    }
}
