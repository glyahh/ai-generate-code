package com.dbts.glyahhaigeneratecode.core.parser;

import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 单文件代码解析器，将字符串中的 HTML 代码块解析为 HtmlCodeResult
 * 实现类
 *
 * 大致思路: 正则找 ```html...``` 代码块 → 取出中间内容 → 填到 HtmlCodeResult；没有则整段当 HTML 并加提示
 */
public class HtmlCodeParser implements CodeParser<HtmlCodeResult> {

    private static final Pattern HTML_CODE_PATTERN = Pattern.compile("```html\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    /**
     * 解析 HTML 单文件代码
     *
     * @param codeContent 原始代码内容
     * @return 解析后的 HtmlCodeResult，必不为 null
     */
    @Override
    public HtmlCodeResult parse(String codeContent) {
        HtmlCodeResult result = new HtmlCodeResult();
        // 1. 优先用 ```html 围栏正则抽取正文
        String htmlCode = extractHtmlCode(codeContent);
        // 2. 命中且非空：trim 后写入结果并直接返回（标准路径）
        if (htmlCode != null && !htmlCode.trim().isEmpty()) {
            result.setHtmlCode(htmlCode.trim());
            return result;
        }
        // 3. 未命中围栏：仅在整段文本「看起来像 HTML」时才落盘，避免自然语言污染 index.html
        String raw = codeContent == null ? "" : codeContent.trim();
        if (looksLikeHtmlDocument(raw)) {
            result.setHtmlCode(raw);
        }
        // 4. 返回结果（可能 html 仍为空，由上层决定是否可保存）
        return result;
    }

    /**
     * 提取HTML代码内容
     *
     * @param content 原始内容
     * @return HTML代码，未匹配到则 null
     */
    private String extractHtmlCode(String content) {
        // 1. null 直接视为无代码
        if (content == null) {
            return null;
        }
        // 2. 正则匹配第一个 ```html ... ``` 块
        Matcher matcher = HTML_CODE_PATTERN.matcher(content);
        // 3. 命中返回捕获组，否则 null
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 判定文本是否像可执行 HTML 文档。
     * @param content 原始文本
     * @return true-可按 HTML 落盘；false-视为非代码文本
     */
    private boolean looksLikeHtmlDocument(String content) {
        // 1. 空串不算 HTML
        if (content == null || content.isBlank()) {
            return false;
        }
        String lower = content.toLowerCase();
        // 2. 完整文档特征：DOCTYPE 或 <html
        if (lower.contains("<!doctype html") || lower.contains("<html")) {
            return true;
        }
        // 3. 片段特征：常见块级标签，降低把纯文本当 HTML 的概率
        return lower.contains("<body") || lower.contains("<div") || lower.contains("<section");
    }
}
