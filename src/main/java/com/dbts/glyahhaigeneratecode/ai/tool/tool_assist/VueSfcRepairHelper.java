package com.dbts.glyahhaigeneratecode.ai.tool.tool_assist;

/**
 * Vue 单文件组件（SFC）修复工具。
 * 负责处理 AI 生成 Vue SFC 时常见的标签不平衡问题：
 * - 移除多余的闭合标签（如多个 {@code </template>}）
 * - 补上缺失的闭合标签（如缺了 {@code </script>}）
 * - 清理尾部空白
 */
public final class VueSfcRepairHelper {

    private VueSfcRepairHelper() {
        // 工具类禁止实例化
    }

    /**
     * 针对 .vue 文件内容做最小兜底修复，避免顶层 SFC 标签不平衡导致构建失败。
     *
     * @param content 原始文件内容
     * @return 修复后的内容；null 入参返回 null
     */
    public static String repairVueSfcContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String result = sanitizeVueContent(content);
        result = appendMissingClosingTag(result, "<template", "</template>");
        result = appendMissingClosingTag(result, "<script", "</script>");
        result = appendMissingClosingTag(result, "<style", "</style>");
        return result.stripTrailing();
    }

    /**
     * 修正标签数量不匹配：移除多余的闭合标签，保留正确的数量。
     */
    private static String sanitizeVueContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        String result = content;

        int excessTemplate = countMatches(result, "</template>") - countMatches(result, "<template");
        for (int i = 0; i < excessTemplate; i++) {
            int lastIdx = result.lastIndexOf("</template>");
            if (lastIdx >= 0) {
                result = result.substring(0, lastIdx) + result.substring(lastIdx + "</template>".length());
            }
        }

        int excessScript = countMatches(result, "</script>") - countMatches(result, "<script");
        for (int i = 0; i < excessScript; i++) {
            int lastIdx = result.lastIndexOf("</script>");
            if (lastIdx >= 0) {
                result = result.substring(0, lastIdx) + result.substring(lastIdx + "</script>".length());
            }
        }

        return result.stripTrailing();
    }

    /**
     * 如果开标签数量 > 闭标签数量，在末尾追加缺失的闭标签。
     */
    private static String appendMissingClosingTag(String text, String openingTagPrefix, String closingTag) {
        int openingCount = countMatches(text, openingTagPrefix);
        int closingCount = countMatches(text, closingTag);
        int missingCount = openingCount - closingCount;
        if (missingCount <= 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (int i = 0; i < missingCount; i++) {
            sb.append(System.lineSeparator()).append(closingTag);
        }
        return sb.toString();
    }

    /**
     * 统计子串出现的次数。
     */
    private static int countMatches(String text, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }
}
