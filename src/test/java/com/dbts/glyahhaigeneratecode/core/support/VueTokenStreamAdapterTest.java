package com.dbts.glyahhaigeneratecode.core.support;

import com.dbts.glyahhaigeneratecode.ai.model.message.ToolExecutedMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolRequestMessage;
import com.dbts.glyahhaigeneratecode.core.Builder.vueProjectBuilder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VueTokenStreamAdapter 单元测试。
 * 使用 FakeTokenStream 模拟 LangChain4j TokenStream，在单线程中驱动回调并收集 Flux 输出。
 * 不依赖 Spring Boot、MySQL、Redis 或真实 AI 模型。
 */
class VueTokenStreamAdapterTest {

    private FakeTokenStream fakeTokenStream;
    private vueProjectBuilder mockBuilder;
    private List<String> emitted;
    private CountDownLatch doneLatch;
    private Flux<String> flux;

    @BeforeEach
    void setUp() {
        fakeTokenStream = new FakeTokenStream();
        mockBuilder = new vueProjectBuilder() {
            @Override
            public boolean buildProject(String projectPath) {
                return true;
            }
        };
        emitted = new ArrayList<>();
        doneLatch = new CountDownLatch(1);
    }

    private void subscribeAndRecord() {
        flux = VueTokenStreamAdapter.adapt(fakeTokenStream, 1L, mockBuilder);
        flux.subscribe(
                emitted::add,
                e -> {
                    emitted.add("[ERROR: " + e.getMessage() + "]");
                    doneLatch.countDown();
                },
                doneLatch::countDown
        );
    }

    private void awaitDone() throws Exception {
        assertTrue(doneLatch.await(2, TimeUnit.SECONDS), "Flux 未在超时内完成");
    }

    // ==================== 测试用例 ====================

    @Test
    void partialResponse_emitsAiResponseJson() throws Exception {
        subscribeAndRecord();
        fakeTokenStream.firePartialResponse("Hello Vue");
        fakeTokenStream.fireComplete();
        awaitDone();

        assertFalse(emitted.isEmpty(), "应有至少一条消息");
        String json = emitted.get(0);
        assertTrue(json.contains("\"type\":\"ai_response\""), "应含 type=ai_response: " + json);
        assertTrue(json.contains("Hello Vue"), "应包含文本: " + json);
    }

    @Test
    void writeFilePartial_emitsToolRequestAndSyntheticExecuted() throws Exception {
        subscribeAndRecord();
        fakeTokenStream.firePartialToolRequest(0, ToolExecutionRequest.builder()
                .id("call_wf_001")
                .name("writeFile")
                .arguments("{\"relativeFilePath\":\"src/App.vue\"")
                .build());
        // 第二个片段补全 content
        fakeTokenStream.firePartialToolRequest(0, ToolExecutionRequest.builder()
                .id("call_wf_001")
                .name("writeFile")
                .arguments(",\"content\":\"<template>OK</template>\"}")
                .build());
        fakeTokenStream.fireComplete();
        awaitDone();

        assertTrue(emitted.size() >= 2, "应有至少 ToolRequest + synthetic 两条消息");
        String first = emitted.get(0);
        assertTrue(first.contains("\"type\":\"tool_request\""), "第一条应是 tool_request: " + first);

        // 找到 synthetic tool_executed
        String synthetic = emitted.stream()
                .filter(s -> s.contains("tool_executed") && s.contains("call_wf_001"))
                .findFirst()
                .orElse(null);
        assertNotNull(synthetic, "应有 synthetic tool_executed");
    }

    @Test
    void completeOnlyToolRequest_emitsToolRequestMessage() throws Exception {
        subscribeAndRecord();
        // 某些 API 仅在 complete 时给出完整请求
        fakeTokenStream.fireCompleteToolRequest(0, ToolExecutionRequest.builder()
                .id("call_co_001")
                .name("writeFile")
                .arguments("{\"relativeFilePath\":\"a.txt\",\"content\":\"x\"}")
                .build());
        fakeTokenStream.fireComplete();
        awaitDone();

        assertFalse(emitted.isEmpty(), "应有消息");
        String json = emitted.get(0);
        assertTrue(json.contains("\"type\":\"tool_request\""), "应是 tool_request: " + json);
    }

    @Test
    void toolExecutedForSynthetic_skipsExtraEmit() throws Exception {
        subscribeAndRecord();
        // partial 阶段合成一条 writeFile
        fakeTokenStream.firePartialToolRequest(0, ToolExecutionRequest.builder()
                .id("call_skip_001")
                .name("writeFile")
                .arguments("{\"relativeFilePath\":\"f.js\",\"content\":\"console.log(1)\"}")
                .build());
        // native onToolExecuted 同一 id → 应跳过
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_skip_001")
                .name("writeFile")
                .arguments("{\"relativeFilePath\":\"f.js\",\"content\":\"console.log(1)\"}")
                .build();
        ToolExecution execution = ToolExecution.builder()
                .request(request)
                .result("ok")
                .build();
        fakeTokenStream.fireToolExecuted(execution);
        fakeTokenStream.fireComplete();
        awaitDone();

        // synthetic tool_executed 应在列表中
        long syntheticCount = emitted.stream()
                .filter(s -> s.contains("tool_executed"))
                .count();
        assertEquals(1, syntheticCount, "synthetic 应只有 1 条，native 同 ID 应跳过");
    }

    @Test
    void toolExecutedForNonSynthetic_emitsNativeExecuted() throws Exception {
        subscribeAndRecord();
        // 非 writeFile 工具（如 readFile），走 native onToolExecuted
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_native_001")
                .name("readFile")
                .arguments("{\"relativeFilePath\":\"pkg.json\"}")
                .build();
        ToolExecution execution = ToolExecution.builder()
                .request(request)
                .result("content...")
                .build();
        fakeTokenStream.fireToolExecuted(execution);
        fakeTokenStream.fireComplete();
        awaitDone();

        String nativeExec = emitted.stream()
                .filter(s -> s.contains("tool_executed") && s.contains("readFile"))
                .findFirst()
                .orElse(null);
        assertNotNull(nativeExec, "非 writeFile 的 native tool_executed 应被 emit");
    }

    @Test
    void onComplete_triggersBuildAndCompletes() throws Exception {
        subscribeAndRecord();
        fakeTokenStream.firePartialResponse("final");
        fakeTokenStream.fireComplete();
        awaitDone();

        assertFalse(emitted.isEmpty(), "应有 final 文本");
        // sink 已完成
        assertTrue(doneLatch.getCount() <= 0, "sink 应已被 complete");
    }

    @Test
    void onError_emitsError() throws Exception {
        flux = VueTokenStreamAdapter.adapt(fakeTokenStream, 1L, mockBuilder);
        flux.subscribe(
                emitted::add,
                e -> {
                    emitted.add("[ERROR]");
                    doneLatch.countDown();
                },
                doneLatch::countDown
        );
        fakeTokenStream.fireError(new RuntimeException("test error"));
        awaitDone();

        assertTrue(emitted.contains("[ERROR]"), "应收到错误信号");
    }

    // ==================== FakeTokenStream ====================

    /**
     * 简易 Fake：捕获 TokenStream 回调注册，测试时可手动触发.
     */
    static class FakeTokenStream implements TokenStream {

        Consumer<String> partialResponseHandler;
        BiConsumer<Integer, ToolExecutionRequest> partialToolExecutionRequestHandler;
        BiConsumer<Integer, ToolExecutionRequest> completeToolExecutionRequestHandler;
        Consumer<ToolExecution> toolExecutionHandler;
        Consumer<ChatResponse> completeResponseHandler;
        Consumer<Throwable> errorHandler;

        FakeTokenStream() {
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> handler) {
            this.partialResponseHandler = handler;
            return this;
        }

        @Override
        public TokenStream onPartialToolExecutionRequest(
                BiConsumer<Integer, ToolExecutionRequest> handler) {
            this.partialToolExecutionRequestHandler = handler;
            return this;
        }

        @Override
        public TokenStream onCompleteToolExecutionRequest(
                BiConsumer<Integer, ToolExecutionRequest> handler) {
            this.completeToolExecutionRequestHandler = handler;
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<Content>> contentHandler) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<ToolExecution> handler) {
            this.toolExecutionHandler = handler;
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(Consumer<ChatResponse> handler) {
            this.completeResponseHandler = handler;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> handler) {
            this.errorHandler = handler;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
        }

        @Override
        public void cancel() {
        }

        // 触发方法

        void firePartialResponse(String text) {
            if (partialResponseHandler != null) {
                partialResponseHandler.accept(text);
            }
        }

        void firePartialToolRequest(Integer idx, ToolExecutionRequest request) {
            if (partialToolExecutionRequestHandler != null) {
                partialToolExecutionRequestHandler.accept(idx, request);
            }
        }

        void fireCompleteToolRequest(Integer idx, ToolExecutionRequest request) {
            if (completeToolExecutionRequestHandler != null) {
                completeToolExecutionRequestHandler.accept(idx, request);
            }
        }

        void fireToolExecuted(ToolExecution execution) {
            if (toolExecutionHandler != null) {
                toolExecutionHandler.accept(execution);
            }
        }

        void fireComplete() {
            if (completeResponseHandler != null) {
                completeResponseHandler.accept(ChatResponse.builder().build());
            }
        }

        void fireError(Throwable t) {
            if (errorHandler != null) {
                errorHandler.accept(t);
            }
        }
    }
}
