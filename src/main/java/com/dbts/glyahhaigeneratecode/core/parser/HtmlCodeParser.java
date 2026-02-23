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
        // 提取 HTML 代码
        String htmlCode = extractHtmlCode(codeContent);
        if (htmlCode != null && !htmlCode.trim().isEmpty()) {
            result.setHtmlCode(htmlCode.trim());
        } else {
            // 如果没有找到代码块，将整个内容作为HTML
            result.setHtmlCode(codeContent.trim() + "\n" + "未找到HTML代码快");
        }
        return result;
    }

    /**
     * 提取HTML代码内容
     *
     * @param content 原始内容
     * @return HTML代码，未匹配到则 null
     */
    private String extractHtmlCode(String content) {
        Matcher matcher = HTML_CODE_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
