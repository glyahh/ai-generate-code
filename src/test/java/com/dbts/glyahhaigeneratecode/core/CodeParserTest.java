package com.dbts.glyahhaigeneratecode.core;

import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import com.dbts.glyahhaigeneratecode.core.parser.HtmlCodeParser;
import com.dbts.glyahhaigeneratecode.core.parser.MultiFileCodeParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodeParserTest {

    @Test
    void parseHtmlCode() {
        String codeContent = """
            随便写一段描述：
            ```html
            <!DOCTYPE html>
            <html>
            <head>
                <title>测试页面</title>
            </head>
            <body>
                <h1>Hello World!</h1>
            </body>
            </html>
            ```
            随便写一段描述
            """;
        HtmlCodeResult result = CodeParser.parseHtmlCode(codeContent);
        assertNotNull(result);
        assertNotNull(result.getHtmlCode());
    }

    @Test
    void parseHtmlCode_shouldRejectPlainNaturalLanguageAsHtml() {
        String content = "我来为您创建一个详细介绍各种咖啡制作和风味的应用网页。";
        HtmlCodeResult result = new HtmlCodeParser().parse(content);
        assertNotNull(result);
        assertTrue(result.getHtmlCode() == null || result.getHtmlCode().isBlank());
    }

    @Test
    void parseMultiFileCode() {
        String codeContent = """
            创建一个完整的网页：
            ```html
            <!DOCTYPE html>
            <html>
            <head>
                <title>多文件示例</title>
                <link rel="stylesheet" href="style.css">
            </head>
            <body>
                <h1>欢迎使用</h1>
                <script src="script.js"></script>
            </body>
            </html>
            ```

            ```css
            h1 {
                color: blue;
                text-align: center;
            }
            ```

            ```js
            console.log('页面加载完成');
            ```

            文件创建完成！
            """;
        MultiFileCodeResult result = CodeParser.parseMultiFileCode(codeContent);
        assertNotNull(result);
        assertNotNull(result.getHtmlCode());
        assertNotNull(result.getCssCode());
        assertNotNull(result.getJsCode());
    }

    @Test
    void parseMultiFileCode_shouldRejectPlainNaturalLanguage() {
        String content = "我来为您创建一个详细介绍各种咖啡制作和风味的应用网页。";
        MultiFileCodeResult result = new MultiFileCodeParser().parse(content);
        assertNotNull(result);
        assertTrue(result.getHtmlCode() == null || result.getHtmlCode().isBlank());
        assertTrue(result.getCssCode() == null || result.getCssCode().isBlank());
        assertTrue(result.getJsCode() == null || result.getJsCode().isBlank());
    }


}


