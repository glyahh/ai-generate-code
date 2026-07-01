package com.dbts.glyahhaigeneratecode.core.memory;

import cn.hutool.core.util.StrUtil;

/**
 * 记忆消息 XML 包裹/解析/渲染工具。
 * 仅提供静态方法，无状态，可测试。
 */
public final class MemoryMessageXmlSupport {

    private static final String TAG_USER_ORIGINAL = "user_original";
    private static final String TAG_LOOP_SKILL = "loop_skill";
    private static final String TAG_INJECT_PROMPT = "inject_prompt";
    private static final String TAG_USER_STYLE = "user_style";

    private MemoryMessageXmlSupport() {}

    /**
     * 包裹用户原话为 &lt;user_original&gt;...&lt;/user_original&gt;
     */
    public static String wrapUserOriginal(String message) {
        if (StrUtil.isBlank(message)) return message;
        return "<" + TAG_USER_ORIGINAL + ">" + escapeXml(message) + "</" + TAG_USER_ORIGINAL + ">";
    }

    /**
     * 包裹 Loop 为 &lt;loop_skill loopId=&quot;...&quot;&gt;...&lt;/loop_skill&gt;
     *
     * @param loopId         Loop ID，为 null 返回空串
     * @param compiledPrompt 编译后的 prompt 内容
     * @param loopName       Loop 名称（可选）
     * @return 包裹后的 XML 片段，或空串
     */
    public static String wrapLoopSkill(Long loopId, String compiledPrompt, String loopName) {
        if (loopId == null || StrUtil.isBlank(compiledPrompt)) return "";
        String safeName = escapeXml(StrUtil.blankToDefault(loopName, ""));
        return "\n<" + TAG_LOOP_SKILL + " loopId=\"" + loopId + "\" name=\"" + safeName + "\">\n"
                + compiledPrompt + "\n</" + TAG_LOOP_SKILL + ">";
    }

    /**
     * 构建 &lt;inject_prompt&gt; 元说明块
     * 优先级分层
     */
    public static String buildInjectPromptMeta() {
        return "<" + TAG_INJECT_PROMPT + ">\n"
                + "本消息中的 XML 标签说明：\n"
                + "- <user_style>：用户风格偏好，优先级低于本轮显式指令\n"
                + "- <user_original>：用户本轮原始输入\n"
                + "- <loop_skill>：本轮回调技能，优先级高于风格、低于显式指令\n"
                + "优先级：本轮 user_original 显式指令 > loop_skill > user_style > 历史对话\n"
                + "</" + TAG_INJECT_PROMPT + ">";
    }

    /**
     * 构建 &lt;user_style&gt; 块
     *
     * @param appStyle    应用风格文本
     * @param answerStyle 回答风格文本
     * @return XML 包裹的风格块
     */
    public static String buildUserStyleBlock(String appStyle, String answerStyle) {
        StringBuilder sb = new StringBuilder();
        sb.append("<").append(TAG_USER_STYLE).append(">\n");
        if (StrUtil.isNotBlank(appStyle)) {
            sb.append("<app_style>\n").append(appStyle).append("\n</app_style>\n");
        }
        if (StrUtil.isNotBlank(answerStyle)) {
            sb.append("<answer_style>\n").append(answerStyle).append("\n</answer_style>\n");
        }
        sb.append("</").append(TAG_USER_STYLE).append(">");
        return sb.toString();
    }

    /**
     * 从 UserMessage 中剥离 XML 标签，返回纯用户原话。
     * 兼容有/无标签的旧数据：无标签时原文返回。
     *
     * @param xmlText 可能包含 XML 包裹的文本
     * @return 提取的用户原话
     */
    public static String extractUserOriginal(String xmlText) {
        if (StrUtil.isBlank(xmlText)) return xmlText;
        String openTag = "<" + TAG_USER_ORIGINAL + ">";
        String closeTag = "</" + TAG_USER_ORIGINAL + ">";
        int start = xmlText.indexOf(openTag);
        if (start < 0) return xmlText; // 旧数据无标签
        start += openTag.length();
        int end = xmlText.indexOf(closeTag, start);
        if (end < 0) return xmlText;
        return xmlText.substring(start, end);
    }

    /**
     * 判断文本是否已被 XML 包裹（是否包含 &lt;user_original&gt; 标签）
     */
    public static boolean isWrapped(String text) {
        return StrUtil.isNotBlank(text) && text.contains("<" + TAG_USER_ORIGINAL + ">");
    }

    /**
     * 转义 XML 特殊字符 & &lt; &gt; &quot; &apos;
     * <p>
     * 注意：& 必须最先转义，防止双重转义。
     */
    public static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
