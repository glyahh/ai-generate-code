package com.dbts.glyahhaigeneratecode.core.loop;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.dbts.glyahhaigeneratecode.service.support.LoopWorkflowCompiler;

class LoopWorkflowCompilerTest {

    @Test
    void compile_shouldJoinNonEmptySteps() {
        String json = "{\"templateId\":\"standard_v1\",\"steps\":[" +
            "{\"key\":\"role\",\"label\":\"角色设定\",\"content\":\"你是专家\"}," +
            "{\"key\":\"context\",\"label\":\"背景上下文\",\"content\":\"\"}," +
            "{\"key\":\"constraints\",\"label\":\"约束与边界\",\"content\":\"简洁输出\"}" +
            "]}";
        String result = LoopWorkflowCompiler.compile(json);
        assertTrue(result.contains("## 角色设定"));
        assertTrue(result.contains("你是专家"));
        assertTrue(result.contains("## 约束与边界"));
        assertTrue(result.contains("简洁输出"));
        // 空 content 的步骤不输出
        assertFalse(result.contains("背景上下文"));
    }

    @Test
    void compile_shouldSkipEmptyContentSteps() {
        String json = "{\"templateId\":\"standard_v1\",\"steps\":[" +
            "{\"key\":\"role\",\"label\":\"角色设定\",\"content\":\"\"}" +
            "]}";
        String result = LoopWorkflowCompiler.compile(json);
        assertEquals("", result.trim());
    }

    @Test
    void compile_shouldHandleNullContent() {
        String json = "{\"templateId\":\"standard_v1\",\"steps\":[" +
            "{\"key\":\"role\",\"label\":\"角色设定\",\"content\":null}" +
            "]}";
        String result = LoopWorkflowCompiler.compile(json);
        assertEquals("", result.trim());
    }

    @Test
    void compile_shouldReturnEmptyForInvalidJson() {
        String result = LoopWorkflowCompiler.compile("not json");
        assertEquals("", result);
    }

    @Test
    void compile_shouldReturnEmptyForNullInput() {
        String result = LoopWorkflowCompiler.compile(null);
        assertEquals("", result);
    }
}
