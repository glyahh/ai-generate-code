package com.dbts.glyahhaigeneratecode.core.parser;

import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多文件代码解析器，将字符串中的 HTML / CSS / JS 代码块解析为 MultiFileCodeResult
 * 实现类
 *
 * 大致思路: 正则分别找 html / css / js 三块代码 → 各自取出 → 填进 MultiFileCodeResult
 */
public class MultiFileCodeParser implements CodeParser<MultiFileCodeResult> {

    private static final Pattern HTML_CODE_PATTERN = Pattern.compile("```html\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_CODE_PATTERN = Pattern.compile("```css\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_CODE_PATTERN = Pattern.compile("```(?:js|javascript)\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    /**
     * 解析多文件代码（HTML + CSS + JS）
     *
     * @param codeContent 原始代码内容
     * @return 解析后的 MultiFileCodeResult，必不为 null
     */
    @Override
    public MultiFileCodeResult parse(String codeContent) {
        MultiFileCodeResult result = new MultiFileCodeResult();
        // 1. 分别用 html / css / js 三个 Pattern 从原文提取 fenced 块
        String htmlCode = extractCodeByPattern(codeContent, HTML_CODE_PATTERN);
        String cssCode = extractCodeByPattern(codeContent, CSS_CODE_PATTERN);
        String jsCode = extractCodeByPattern(codeContent, JS_CODE_PATTERN);
        // 2. 仅当某类代码非空才 set，避免空串覆盖、也避免把说明文字写进文件
        if (htmlCode != null && !htmlCode.trim().isEmpty()) {
            result.setHtmlCode(htmlCode.trim());
        }
        if (cssCode != null && !cssCode.trim().isEmpty()) {
            result.setCssCode(cssCode.trim());
        }
        if (jsCode != null && !jsCode.trim().isEmpty()) {
            result.setJsCode(jsCode.trim());
        }
        // 3. 返回聚合对象（字段可能部分为空，由保存层再校验）
        return result;
    }

    /**
     * 根据正则模式提取代码
     *
     * @param content 原始内容
     * @param pattern 正则模式
     * @return 提取的代码，未匹配到则 null
     */
    private String extractCodeByPattern(String content, Pattern pattern) {
        // 1. null 无内容可匹配
        if (content == null) {
            return null;
        }
        // 2. 用传入 pattern 做 find
        Matcher matcher = pattern.matcher(content);
        // 3. 命中返回 group(1)，否则 null
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
