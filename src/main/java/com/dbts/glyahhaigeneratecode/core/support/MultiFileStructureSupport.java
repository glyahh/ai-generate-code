package com.dbts.glyahhaigeneratecode.core.support;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MULTI_FILE 三文件结构校验：避免 index.html 被改成内联 style/script 的单页而破坏多文件约定。
 */
public final class MultiFileStructureSupport {

    private static final Pattern INLINE_SCRIPT = Pattern.compile(
            "<script(?![^>]*\\ssrc\\s*=)", Pattern.CASE_INSENSITIVE);

    private MultiFileStructureSupport() {
    }

    /**
     * 是否像「单文件 HTML」（内联样式/脚本且未引用 style.css、script.js）
     */
    public static boolean looksLikeMonolithicHtml(String html) {
        if (StrUtil.isBlank(html)) {
            return false;
        }
        String lower = html.toLowerCase(Locale.ROOT);
        boolean refsExternal = lower.contains("style.css") && lower.contains("script.js");
        if (refsExternal) {
            return false;
        }
        boolean hasInlineStyle = lower.contains("<style");
        boolean hasInlineScript = INLINE_SCRIPT.matcher(html).find();
        return hasInlineStyle || hasInlineScript;
    }
}
