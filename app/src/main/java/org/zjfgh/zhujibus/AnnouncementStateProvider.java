package org.zjfgh.zhujibus;

/**
 * 实时报站状态提供方（由宿主 Activity 实现，如车机报站页的 BusLineDetailActivity）。
 * 报站格式设置（语音模板试听 / LED 预览）优先用实时状态构造变量上下文；宿主未实现时回退为样例数据。
 */
public interface AnnouncementStateProvider {
    /** 提供当前报站的变量上下文（线路名、起终点、当前/下一站等）。 */
    AnnouncementFormatter.VariableContext provideAnnouncementContext();

    /** 预览：把渲染后的 LED 文本直接应用到车内 LED 滚动屏。 */
    void applyLedPreview(String text);
}
