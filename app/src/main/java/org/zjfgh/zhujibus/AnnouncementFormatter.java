package org.zjfgh.zhujibus;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报站格式解析器：把用户模板字符串拆解为可播放的 {@link Segment} 列表。
 *
 * <p>模板语法：
 * <ul>
 *     <li>{@code {变量名}} —— 普通文本段中的变量占位，运行时替换为 {@link VariableContext} 中的值；
 *         未知变量保留原样（如 {@code {未知}}），便于用户发现笔误。</li>
 *     <li>{@code [语音包名]} —— 语音包段。由 {@link VoicePackResolver} 解析为音频文件路径；
 *         实际实现见 {@link DefaultVoicePackResolver}（占位/未配置时）与 {@code TTSUtils#playFormattedAnnouncement}
 *         中接入的「用户自定义语音包目录」解析器（从 SAF 所选目录读取「名称.*」音频）。
 *         解析不到时由调用方优雅回退（如 TTS 播报包名）。</li>
 *     <li>{@code [{变量名}]} —— 变量化的语音包段。先按变量名取实际值（如 {@code [{线路名}]} 取到「1号线」），
 *         再用该值当作语音包名去匹配目录里的「值.*」音频。变量无值时退化为普通文本占位。</li>
 *     <li>{@code [前缀_{变量名}]} 与 {@code [前缀_语音包名]} —— 带字母前缀（如 {@code en_}，可为任意字母）的语音包段。
 *         例如 {@code [en_{线路名}]} 取到「1号线」后拼接为 {@code en_1号线} 去匹配目录里的「en_1号线.*」；
 *         纯前缀形式 {@code [en_紫庄]} 则直接匹配「en_紫庄.*」。</li>
 * </ul>
 */
public class AnnouncementFormatter {

    private static final String TAG = "AnnouncementFormatter";

    // 匹配 {变量} 或 [语音包]，group(1)=变量名, group(2)=语音包名
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{([^}]*)\\}|\\[([^\\]]*)\\]");

    /** 解析后的最小播放单元。 */
    public static class Segment {
        public enum Type { TEXT, VOICE_PACK }

        public final Type type;
        /** TEXT: 已替换变量的普通文本；VOICE_PACK: 语音包名（未解析前的名字）。 */
        public final String text;
        /** VOICE_PACK 且解析成功时的文件路径。 */
        public String resolvedPath;
        /** VOICE_PACK 是否成功解析到音频文件。 */
        public boolean voicePackResolved = false;

        private Segment(Type type, String text) {
            this.type = type;
            this.text = text;
        }

        public static Segment text(String text) {
            return new Segment(Type.TEXT, text);
        }

        public static Segment voicePack(String name) {
            return new Segment(Type.VOICE_PACK, name);
        }
    }

    /** 报站时可用变量集合（与 {@link AnnouncementFormatManager} 的变量键对应）。 */
    public static class VariableContext {
        public String lineName;       // 线路名
        public String startStation;   // 起点站
        public String endStation;     // 终点站
        public String currentStation; // 当前站
        public String nextStation;    // 下一站
        public String price;          // 票价
        public String direction;      // 方向

        /** 按变量键（如「线路名」）解析为实际值；未知键返回 null。 */
        public String resolve(String key) {
            if (key == null) return null;
            switch (key.trim()) {
                case AnnouncementFormatManager.VAR_LINE:       return lineName;
                case AnnouncementFormatManager.VAR_START:      return startStation;
                case AnnouncementFormatManager.VAR_END:        return endStation;
                case AnnouncementFormatManager.VAR_CURRENT:    return currentStation;
                case AnnouncementFormatManager.VAR_NEXT:       return nextStation;
                case AnnouncementFormatManager.VAR_PRICE:      return price;
                case AnnouncementFormatManager.VAR_DIRECTION:  return direction;
                default:                                       return null;
            }
        }
    }

    /**
     * 语音包解析接口。
     * <p>按 {@code packName} 解析为音频文件绝对路径；找不到返回 null（上层回退 TTS）。
     * 实际生产实现由调用方注入（如 {@code TTSUtils#playFormattedAnnouncement} 中接入的
     * 「用户自定义语音包目录」解析器）；{@link DefaultVoicePackResolver} 仅作为未配置时的占位。
     */
    public interface VoicePackResolver {
        /**
         * @param packName 语音包名（即模板中 [] 内的内容）
         * @return 音频文件绝对路径；找不到返回 null
         */
        String resolve(String packName);
    }

    /** 占位实现：未接入目录时一律判定为找不到（不破坏既有流程）。生产环境由调用方注入真实解析器。 */
    public static class DefaultVoicePackResolver implements VoicePackResolver {
        @Override
        public String resolve(String packName) {
            Log.d(TAG, "占位解析器：未配置语音包目录，判定为找不到: " + packName);
            return null;
        }
    }

    /**
     * 解析模板为段序列。
     *
     * @param template 用户填写的模板（可为 null/空）
     * @param ctx      变量上下文（可为 null，变量一律保留原样）
     * @param resolver 语音包解析器（可为 null，等价于找不到）
     */
    public static List<Segment> parse(String template, VariableContext ctx, VoicePackResolver resolver) {
        List<Segment> segments = new ArrayList<>();
        if (template == null || template.isEmpty()) {
            return segments;
        }

        Matcher m = TOKEN_PATTERN.matcher(template);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                segments.add(Segment.text(template.substring(last, m.start())));
            }
            if (m.group(1) != null) {
                // {变量}
                String key = m.group(1).trim();
                String value = (ctx != null) ? ctx.resolve(key) : null;
                if (value == null || value.isEmpty()) {
                    // 未知/空变量：保留原占位文本，便于用户察觉
                    segments.add(Segment.text("{" + key + "}"));
                } else {
                    segments.add(Segment.text(value));
                }
            } else if (m.group(2) != null) {
                // [语音包]；支持 [{变量}] 与 [前缀_{变量}]（前缀如 en_，可为任意字母）。
                // 先解析变量取值，再与前面的字面前缀拼接作为语音包名去目录匹配（如 en_1号线）。
                String raw = m.group(2).trim();
                String packName = raw;
                int vStart = raw.indexOf('{');
                int vEnd = (vStart >= 0) ? raw.indexOf('}', vStart) : -1;
                if (vStart >= 0 && vEnd > vStart) {
                    String prefix = raw.substring(0, vStart); // 变量前的字面前缀（如 "en_"），无则为空
                    String key = raw.substring(vStart + 1, vEnd).trim();
                    String value = (ctx != null) ? ctx.resolve(key) : null;
                    if (value != null && !value.isEmpty()) {
                        packName = prefix + value; // 前缀 + 变量值 作为语音包名
                    } else {
                        // 变量无值：退化为普通文本占位（与 {变量} 未解析行为一致）
                        segments.add(Segment.text("{" + key + "}"));
                        last = m.end();
                        continue;
                    }
                }
                // 否则为纯语音包名（可含 en_ 等字面前缀），如 [en_紫庄]
                Segment seg = Segment.voicePack(packName);
                if (resolver != null) {
                    String path = resolver.resolve(packName);
                    if (path != null) {
                        seg.resolvedPath = path;
                        seg.voicePackResolved = true;
                    }
                }
                segments.add(seg);
            }
            last = m.end();
        }
        if (last < template.length()) {
            segments.add(Segment.text(template.substring(last)));
        }
        return segments;
    }

    /**
     * 纯文本渲染：仅保留 {@code {变量}} 替换后的文本段，丢弃 {@code [语音包]} 段。
     * 用于 LED 横屏滚动等只显示文本、不支持语音包的场景（即"仅文本模板"）。
     *
     * @param template 模板（可为 null/空，返回空串）
     * @param ctx      变量上下文（可为 null，变量保留原占位）
     */
    public static String formatTextOnly(String template, VariableContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (Segment seg : parse(template, ctx, null)) {
            if (seg.type == Segment.Type.TEXT) {
                sb.append(seg.text);
            }
        }
        return sb.toString();
    }
}
