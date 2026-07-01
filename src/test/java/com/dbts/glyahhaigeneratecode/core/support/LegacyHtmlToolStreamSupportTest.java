package com.dbts.glyahhaigeneratecode.core.support;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LegacyHtmlToolStreamSupport 纯函数单元测试。
 * 覆盖 writeFile 参数严格 JSON 解析、容错扫描、未闭合内容处理，及 synthetic 消息拼装。
 */
class LegacyHtmlToolStreamSupportTest {

    // ==================== buildSyntheticWriteFileToolExecutedMessage ====================

    @Test
    void buildSynthetic_WithValidJson_ReturnsJsonString() {
        String raw = "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"<template>Hello</template>\"}";
        String result = LegacyHtmlToolStreamSupport.buildSyntheticWriteFileToolExecutedMessage("call_001", raw);
        assertNotNull(result);
        assertTrue(result.contains("tool_executed"));
        assertTrue(result.contains("call_001"));
        assertTrue(result.contains("writeFile"));
        assertTrue(result.contains("src/App.vue"));
        assertTrue(result.contains("Hello"));
    }

    @Test
    void buildSynthetic_NullToolCallId_ReturnsNull() {
        String result = LegacyHtmlToolStreamSupport.buildSyntheticWriteFileToolExecutedMessage(
                null, "{\"relativeFilePath\":\"a.txt\",\"content\":\"c\"}");
        assertNull(result);
    }

    @Test
    void buildSynthetic_MissingRelativeFilePath_ReturnsNull() {
        String raw = "{\"content\":\"abc\"}";
        String result = LegacyHtmlToolStreamSupport.buildSyntheticWriteFileToolExecutedMessage("call_002", raw);
        assertNull(result);
    }

    @Test
    void buildSynthetic_MissingContent_ReturnsNull() {
        String raw = "{\"relativeFilePath\":\"a.txt\"}";
        String result = LegacyHtmlToolStreamSupport.buildSyntheticWriteFileToolExecutedMessage("call_003", raw);
        assertNull(result);
    }

    @Test
    void buildSynthetic_EmptyRaw_ReturnsNull() {
        String result = LegacyHtmlToolStreamSupport.buildSyntheticWriteFileToolExecutedMessage("call_004", "");
        assertNull(result);
    }

    // ==================== tryExtractWriteFileArguments ====================

    @Test
    void tryExtract_StrictJson_Success() {
        String raw = "{\"relativeFilePath\":\"index.html\",\"content\":\"<h1>Hi</h1>\"}";
        JSONObject result = LegacyHtmlToolStreamSupport.tryExtractWriteFileArguments(raw);
        assertNotNull(result);
        assertEquals("index.html", result.getStr("relativeFilePath"));
        assertEquals("<h1>Hi</h1>", result.getStr("content"));
    }

    @Test
    void tryExtract_TolerantJson_TruncatedAfterValue_Success() {
        // 字符串值被截断但内容已完整提取
        String raw = "{\"relativeFilePath\":\"app.js\",\"content\":\"console.log('hi')\"";
        JSONObject result = LegacyHtmlToolStreamSupport.tryExtractWriteFileArguments(raw);
        assertNotNull(result);
        assertEquals("app.js", result.getStr("relativeFilePath"));
        assertEquals("console.log('hi')", result.getStr("content"));
    }

    @Test
    void tryExtract_TolerantJson_KeyOrderReversed_Success() {
        // content 在前 path 在后
        String raw = "{\"content\":\"body {}\",\"relativeFilePath\":\"style.css\"}";
        JSONObject result = LegacyHtmlToolStreamSupport.tryExtractWriteFileArguments(raw);
        assertNotNull(result);
        assertEquals("style.css", result.getStr("relativeFilePath"));
        assertEquals("body {}", result.getStr("content"));
    }

    @Test
    void tryExtract_EmptyString_ReturnsNull() {
        assertNull(LegacyHtmlToolStreamSupport.tryExtractWriteFileArguments(""));
        assertNull(LegacyHtmlToolStreamSupport.tryExtractWriteFileArguments(null));
        assertNull(LegacyHtmlToolStreamSupport.tryExtractWriteFileArguments("   "));
    }

    @Test
    void tryExtract_MissingBothFields_ReturnsNull() {
        String raw = "{\"other\":\"value\"}";
        assertNull(LegacyHtmlToolStreamSupport.tryExtractWriteFileArguments(raw));
    }

    @Test
    void tryExtract_UnclosedContent_ReturnsNull() {
        // content 值被截断且不完整
        String raw = "{\"relativeFilePath\":\"f.js\",\"content\":\"function ";
        assertNull(LegacyHtmlToolStreamSupport.tryExtractWriteFileArguments(raw));
    }

    @Test
    void tryExtract_CompleteContentWithEscapedChars_Success() {
        String raw = "{\"relativeFilePath\":\"dir/file.js\",\"content\":\"console.log(\\\"hi\\\") \\n ok\"}";
        JSONObject result = LegacyHtmlToolStreamSupport.tryExtractWriteFileArguments(raw);
        assertNotNull(result);
        assertEquals("dir/file.js", result.getStr("relativeFilePath"));
        assertEquals("console.log(\"hi\") \n ok", result.getStr("content"));
    }

    // ==================== safeParseToolArgumentsForStream ====================

    @Test
    void safeParse_ValidJson_ReturnsParsed() {
        JSONObject result = LegacyHtmlToolStreamSupport.safeParseToolArgumentsForStream(
                "{\"key\":\"val\"}");
        assertEquals("val", result.getStr("key"));
    }

    @Test
    void safeParse_InvalidJson_FallbackWithRaw() {
        JSONObject result = LegacyHtmlToolStreamSupport.safeParseToolArgumentsForStream(
                "{broken json}");
        assertNotNull(result);
        assertEquals("{broken json}", result.getStr("_rawArguments"));
    }

    @Test
    void safeParse_EmptyString_ReturnsEmptyObject() {
        JSONObject result = LegacyHtmlToolStreamSupport.safeParseToolArgumentsForStream("");
        assertTrue(result.isEmpty());
    }

    @Test
    void safeParse_Null_ReturnsEmptyObject() {
        JSONObject result = LegacyHtmlToolStreamSupport.safeParseToolArgumentsForStream(null);
        assertTrue(result.isEmpty());
    }

    // ==================== fallbackToolExecutedPlain ====================

    @Test
    void fallbackToolExecuted_WithPath() {
        JSONObject args = new JSONObject();
        args.set("relativeFilePath", "test.txt");
        String result = LegacyHtmlToolStreamSupport.fallbackToolExecutedPlain("writeFile", args);
        assertTrue(result.contains("test.txt"));
        assertTrue(result.contains("writeFile"));
    }

    @Test
    void fallbackToolExecuted_WithoutPath() {
        JSONObject args = new JSONObject();
        String result = LegacyHtmlToolStreamSupport.fallbackToolExecutedPlain("unknownTool", args);
        assertTrue(result.contains("unknownTool"));
    }

    // ==================== ToolStreamState ====================

    @Test
    void toolStreamState_Initialized_EmptyMapsAndSets() {
        LegacyHtmlToolStreamSupport.ToolStreamState state =
                new LegacyHtmlToolStreamSupport.ToolStreamState();
        assertTrue(state.toolArgsById.isEmpty());
        assertTrue(state.syntheticExecutedIds.isEmpty());
        assertTrue(state.warnedLargeIncompleteIds.isEmpty());
        assertTrue(state.seenToolRequestIds.isEmpty());
        assertFalse(state.nativeToolExecutedMode.get());
    }
}
