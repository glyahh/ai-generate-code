package com.dbts.glyahhaigeneratecode.core.util;

/**
 * Legacy HTML/MULTI_FILE 流式输出的完整性辅助：检测「末尾像未写完的标签」并在入库前给出可读提示（不静默补单个字符）。
 */
public final class LegacyHtmlStreamIntegrity {

    /**
     * 若最后一个非空行像 {@code </div} 或 {@code <section}（缺少收尾 {@code >}），视为可能截断。
     */
    public static boolean looksLikeIncompleteTrailingTag(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim();
            if (t.isEmpty()) {
                continue;
            }
            return t.matches("^</?[a-zA-Z][\\w:-]*\\s*$");
        }
        return false;
    }

    /**
     * 在检测到可能截断时追加简短系统提示，便于用户重试或缩短需求（不修改代码正文）。
     */
    public static String appendIntegrityNoticeIfNeeded(String aiText) {
        if (!looksLikeIncompleteTrailingTag(aiText)) {
            return aiText;
        }
        return aiText + """

                ⚠️ [系统提示] 检测到生成末尾可能存在未闭合标签或输出被长度截断（finish_reason 常见为 LENGTH）。请简化页面复杂度后重试，或联系管理员提高流式 max_tokens。
                """;
    }

    private LegacyHtmlStreamIntegrity() {
    }
}
