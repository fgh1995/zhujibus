package org.zjfgh.zhujibus;

import android.app.Activity;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 系统栏（状态栏 / 底部导航栏）适配工具。
 * <p>
 * Android 15（API 35）起，targetSdk >= 35 的应用会被强制启用 Edge-to-Edge：
 * 系统不再像旧版本那样自动把内容放在状态栏/导航栏下方，而是让内容延伸到系统栏后面，
 * 若应用没有适配就会出现"顶部 UI 与状态栏重叠"。
 * 本工具通过 WindowInsets 动态读取系统栏实际高度，并以 padding 的形式让出该区域，
 * 恢复与旧系统一致的排版（顶栏不再被状态栏遮挡）。
 * 旧版本系统上 WindowInsets 为 0，调用后不会产生多余留白，可安全使用。
 */
public final class SystemBarUtils {

    private SystemBarUtils() {
    }

    /**
     * 让 Activity 的内容避开状态栏与底部导航栏。
     * 在 {@code setContentView(...)} 之后调用一次即可。
     *
     * @param activity 目标 Activity
     */
    public static void fitSystemBars(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            // 顶部让出状态栏，底部让出导航栏；左右保留原值（竖屏通常为 0）
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), bars.bottom);
            // 不消费 insets，继续分发给子 View，避免影响子 View 对软键盘等 insets 的处理
            return windowInsets;
        });
    }
}
