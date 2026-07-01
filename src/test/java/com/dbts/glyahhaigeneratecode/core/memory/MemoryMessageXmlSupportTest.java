package com.dbts.glyahhaigeneratecode.core.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryMessageXmlSupportTest {

    @Test
    void wrapUserOriginal_shouldWrapPlainText() {
        String result = MemoryMessageXmlSupport.wrapUserOriginal("帮我生成一个登录页面");
        assertEquals("<user_original>帮我生成一个登录页面</user_original>", result);
    }

    @Test
    void wrapUserOriginal_shouldEscapeXml() {
        String result = MemoryMessageXmlSupport.wrapUserOriginal("a < b && c > d");
        assertEquals("<user_original>a &lt; b &amp;&amp; c &gt; d</user_original>", result);
    }

    @Test
    void wrapUserOriginal_shouldReturnBlankForBlank() {
        assertNull(MemoryMessageXmlSupport.wrapUserOriginal(null));
        assertEquals("", MemoryMessageXmlSupport.wrapUserOriginal(""));
    }

    @Test
    void extractUserOriginal_shouldExtractFromWrapped() {
        String wrapped = "<user_original>帮我生成登录页面</user_original>";
        assertEquals("帮我生成登录页面", MemoryMessageXmlSupport.extractUserOriginal(wrapped));
    }

    @Test
    void extractUserOriginal_shouldReturnOriginalForUnwrapped() {
        String raw = "帮我生成登录页面";
        assertEquals(raw, MemoryMessageXmlSupport.extractUserOriginal(raw));
    }

    @Test
    void wrapLoopSkill_shouldBuildWithAttributes() {
        String result = MemoryMessageXmlSupport.wrapLoopSkill(42L, "print('hello')", "hello-world");
        assertTrue(result.contains("<loop_skill loopId=\"42\" name=\"hello-world\">"));
        assertTrue(result.contains("print('hello')"));
        assertTrue(result.contains("</loop_skill>"));
    }

    @Test
    void wrapLoopSkill_shouldReturnEmptyForNullLoopId() {
        assertTrue(MemoryMessageXmlSupport.wrapLoopSkill(null, "prompt", "name").isEmpty());
    }

    @Test
    void buildInjectPromptMeta_shouldContainPriority() {
        String meta = MemoryMessageXmlSupport.buildInjectPromptMeta();
        assertTrue(meta.contains("user_original"));
        assertTrue(meta.contains("user_style"));
        assertTrue(meta.contains("loop_skill"));
        assertTrue(meta.contains("优先级"));
    }

    @Test
    void buildUserStyleBlock_shouldBuildBothStyles() {
        String block = MemoryMessageXmlSupport.buildUserStyleBlock("专业", "简洁");
        assertTrue(block.contains("<app_style>"));
        assertTrue(block.contains("专业"));
        assertTrue(block.contains("<answer_style>"));
        assertTrue(block.contains("简洁"));
    }

    @Test
    void isWrapped_shouldDetectWrappedText() {
        assertTrue(MemoryMessageXmlSupport.isWrapped("<user_original>hello</user_original>"));
        assertFalse(MemoryMessageXmlSupport.isWrapped("hello"));
        assertFalse(MemoryMessageXmlSupport.isWrapped(null));
    }
}
