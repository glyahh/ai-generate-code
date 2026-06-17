package com.dbts.glyahhaigeneratecode.controller;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 ChatToGenCodeController.toSseEvents 方法
 * 验证压缩标记 [memory_compress_start/end] 正确转为 SSE 事件 memory-compress
 *
 * <p>测试目标：</p>
 * <ul>
 *   <li>[memory_compress_start] → memory-compress 事件 {phase:"start"}</li>
 *   <li>[memory_compress_end] → memory-compress 事件 {phase:"end"}</li>
 *   <li>压缩标记不泄漏为默认文本事件</li>
 *   <li>普通文本 chunk 不受影响，正常通过</li>
 *   <li>null chunk 返回空列表</li>
 * </ul>
 */
class ChatToGenCodeControllerSseEventTest {

    private ChatToGenCodeController controller;
    private Method toSseEventsMethod;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ChatToGenCodeController(null, null);
        toSseEventsMethod = ChatToGenCodeController.class
                .getDeclaredMethod("toSseEvents", String.class);
        toSseEventsMethod.setAccessible(true);
    }

    /**
     * 测试目标: [memory_compress_start] 标记被识别为 memory-compress 事件，phase=start
     * 边界条件: 标记字符串完全匹配
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldEmitMemoryCompressStartEvent() throws Exception {
        List<ServerSentEvent<String>> events =
                (List<ServerSentEvent<String>>) toSseEventsMethod.invoke(controller, "[memory_compress_start]");

        assertEquals(1, events.size(), "应只发射一个事件");
        assertEquals("memory-compress", events.get(0).event(), "event 类型应为 memory-compress");
        JSONObject data = JSONUtil.parseObj(events.get(0).data());
        assertEquals("start", data.get("phase"), "phase 应为 start");
    }

    /**
     * 测试目标: [memory_compress_end] 标记被识别为 memory-compress 事件，phase=end
     * 边界条件: 标记字符串完全匹配
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldEmitMemoryCompressEndEvent() throws Exception {
        List<ServerSentEvent<String>> events =
                (List<ServerSentEvent<String>>) toSseEventsMethod.invoke(controller, "[memory_compress_end]");

        assertEquals(1, events.size(), "应只发射一个事件");
        assertEquals("memory-compress", events.get(0).event(), "event 类型应为 memory-compress");
        JSONObject data = JSONUtil.parseObj(events.get(0).data());
        assertEquals("end", data.get("phase"), "phase 应为 end");
    }

    /**
     * 测试目标: 压缩标记不应同时作为默认文本事件发送
     * 异常场景: 避免前端收到双倍数据（事件 + 文本）
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldNotContainRawMarkerInData() throws Exception {
        List<ServerSentEvent<String>> startEvents =
                (List<ServerSentEvent<String>>) toSseEventsMethod.invoke(controller, "[memory_compress_start]");
        // data 应该是 JSON 字符串，不应包含原始标记
        assertFalse(startEvents.get(0).data().contains("[memory_compress_start]"),
                "data 不应包含原始标记文本");
    }

    /**
     * 测试目标: 普通文本 chunk 不被识别为压缩标记，正常作为默认事件发送
     * 边界条件: 包含 [memory_compress 但不一样字符串
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldPassThroughNormalChunks() throws Exception {
        List<ServerSentEvent<String>> events =
                (List<ServerSentEvent<String>>) toSseEventsMethod.invoke(controller, "这是一段普通文本");

        assertEquals(1, events.size());
        assertNull(events.get(0).event(), "普通文本不应有 event 类型");
        assertEquals("这是一段普通文本", events.get(0).data());
    }

    /**
     * 测试目标: null chunk 返回空列表，不抛异常
     * 异常场景: 后端发送 null chunk 时的防御处理
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldHandleNullChunk() throws Exception {
        List<ServerSentEvent<String>> events =
                (List<ServerSentEvent<String>>) toSseEventsMethod.invoke(controller, (String) null);

        assertTrue(events.isEmpty(), "null chunk 应返回空列表");
    }

    /**
     * 测试目标: 类似前缀但不匹配的字符串不被误识别
     * 边界条件: "[memory_compress_mid]" 等变体不是有效标记
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldNotMatchSimilarPrefix() throws Exception {
        List<ServerSentEvent<String>> events =
                (List<ServerSentEvent<String>>) toSseEventsMethod.invoke(controller, "[memory_compress_mid]");

        assertEquals(1, events.size(), "不是精确匹配的标记应作为文本事件");
        assertNull(events.get(0).event(), "不应有 event 类型");
    }

    /**
     * 测试目标: workflow-step 标记仍能正常识别，不受压缩标记逻辑影响
     * 回归场景: 保证已有功能不受新逻辑干扰
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldStillParseWorkflowStep() throws Exception {
        List<ServerSentEvent<String>> events =
                (List<ServerSentEvent<String>>) toSseEventsMethod.invoke(controller, "[workflow] 第 3 步完成：代码生成");

        // 应该有 2 个事件：1 个 workflow-step + 1 个默认文本
        assertFalse(events.isEmpty(), "workflow 标记应被识别");
        // 验证第一个事件是 workflow-step
        assertTrue(events.stream().anyMatch(e -> "workflow-step".equals(e.event())),
                "应包含 workflow-step 事件");
    }

    /**
     * 测试目标: memory-compress 和 workflow-step 标记不冲突
     * 边界条件: 一个 chunk 不应同时包含两种标记（内存标记单独作为 chunk）
     */
    @Test
    @SuppressWarnings("unchecked")
    void memoryCompressAndWorkflowStepAreMutuallyExclusive() throws Exception {
        // memory-compress 标记返回的事件无 workflow-step 类型
        List<ServerSentEvent<String>> events =
                (List<ServerSentEvent<String>>) toSseEventsMethod.invoke(controller, "[memory_compress_start]");
        boolean hasWorkflowStep = events.stream()
                .anyMatch(e -> "workflow-step".equals(e.event()));
        assertFalse(hasWorkflowStep, "压缩标记不应产生 workflow-step 事件");
    }
}
