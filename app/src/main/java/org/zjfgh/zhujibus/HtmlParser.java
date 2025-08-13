package org.zjfgh.zhujibus;

import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;

public class HtmlParser {
    /**
     * 获取 HTML 中的纯文本（保留换行和段落）
     *
     * @param html HTML 格式的文本
     * @return 保留格式的纯文本
     */
    public static String htmlToFormattedText(String html) {
        if (TextUtils.isEmpty(html)) {
            return "";
        }

        // 替换 <br> 和 <p> 标签为换行符
        String formatted = html
                .replaceAll("<br\\s*/?>", "\n")
                .replaceAll("<p\\b[^>]*>", "\n")
                .replaceAll("</p>", "\n\n");

        // 移除其他 HTML 标签
        formatted = formatted.replaceAll("<[^>]+>", "");

        // 处理 HTML 实体
        formatted = Html.fromHtml(formatted, Html.FROM_HTML_MODE_COMPACT).toString();

        // 合并多个换行和空格
        formatted = formatted
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll("\\s{2,}", " ")
                .trim();

        return formatted;
    }
}