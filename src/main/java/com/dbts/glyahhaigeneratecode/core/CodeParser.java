package com.dbts.glyahhaigeneratecode.core;

import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码解析器
 * 提供静态方法解析不同类型的代码内容
 * 工具类
 *
 * 大致思路:
 * 根据正则表达式获取对应的代码块
 * 根据不同类型进行解析成字符串
 *
 * @author yupi
 */
@Deprecated
public class CodeParser {

    // 正则表达式对象
    private static final Pattern HTML_CODE_PATTERN = Pattern.compile("```html\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_CODE_PATTERN = Pattern.compile("```css\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_CODE_PATTERN = Pattern.compile("```(?:js|javascript)\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    /**
     * 从模型输出中解析 HTML 单文件代码（优先 fenced 代码块，否则整段兜底）
     *
     * @param codeContent 原始文本（可能含 ```html 围栏）
     * @return 解析后的 HtmlCodeResult
     */
    public static HtmlCodeResult parseHtmlCode(String codeContent) {
        HtmlCodeResult result = new HtmlCodeResult();
        // 1. 用 ```html 围栏正则尝试抽取中间正文
        String htmlCode = extractHtmlCode(codeContent);
        // 2. 命中围栏则写入 trim 后的 HTML；否则把整段 trim 后当作 HTML（兼容无围栏输出）
        if (htmlCode != null && !htmlCode.trim().isEmpty()) {
            result.setHtmlCode(htmlCode.trim());
        } else {
            result.setHtmlCode(codeContent.trim());
        }
        // 3. 返回结果对象
        return result;
    }

    /**
     * 从模型输出中解析多文件代码（HTML、CSS、JS 三个 fenced 块）
     *
     * @param codeContent 原始文本（可能含 html/css/js 围栏）
     * @return 解析后的 MultiFileCodeResult
     */
    public static MultiFileCodeResult parseMultiFileCode(String codeContent) {
        MultiFileCodeResult result = new MultiFileCodeResult();
        // 1. 分别用三个正则从同一段文本里提取 html / css / js 围栏内容
        String htmlCode = extractCodeByPattern(codeContent, HTML_CODE_PATTERN);
        String cssCode = extractCodeByPattern(codeContent, CSS_CODE_PATTERN);
        String jsCode = extractCodeByPattern(codeContent, JS_CODE_PATTERN);
        // 2. 仅当某类代码非空时才 set，避免用空串覆盖已有字段语义
        if (htmlCode != null && !htmlCode.trim().isEmpty()) {
            result.setHtmlCode(htmlCode.trim());
        }
        if (cssCode != null && !cssCode.trim().isEmpty()) {
            result.setCssCode(cssCode.trim());
        }
        if (jsCode != null && !jsCode.trim().isEmpty()) {
            result.setJsCode(jsCode.trim());
        }
        // 3. 返回聚合结果
        return result;
    }

    /**
     * 用 HTML 围栏正则从原始文本中提取第一个匹配到的代码体
     *
     * @param content 原始内容
     * @return 匹配到的 HTML 字符串；未匹配返回 null
     */
    private static String extractHtmlCode(String content) {
        // 1. 对全文做 matcher
        Matcher matcher = HTML_CODE_PATTERN.matcher(content);
        // 2. 找到则返回捕获组 1（围栏内正文）；否则 null
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 用给定 Pattern 从原始文本中提取第一个匹配到的代码体
     *
     * @param content 原始内容
     * @param pattern 已编译的正则（需含捕获组 1）
     * @return 捕获组内容；未匹配返回 null
     */
    private static String extractCodeByPattern(String content, Pattern pattern) {
        // 1. 创建 matcher
        Matcher matcher = pattern.matcher(content);
        // 2. find 成功则返回 group(1)
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
