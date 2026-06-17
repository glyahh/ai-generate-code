//package com.dbts.glyahhaigeneratecode.core;
//
//import cn.hutool.json.JSONObject;
//import cn.hutool.json.JSONUtil;
//import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
//import com.dbts.glyahhaigeneratecode.ai.tool.ToolManager;
//import com.dbts.glyahhaigeneratecode.core.Builder.vueProjectBuilder;
//import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
//import dev.langchain4j.agent.tool.ToolExecutionRequest;
//import dev.langchain4j.rag.content.Content;
//import dev.langchain4j.service.TokenStream;
//import dev.langchain4j.service.tool.ToolExecution;
//import org.junit.jupiter.api.Test;
//import org.springframework.test.util.ReflectionTestUtils;
//import reactor.core.publisher.Flux;
//
//import java.util.List;
//import java.util.function.BiConsumer;
//import java.util.function.Consumer;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertNotNull;
//import static org.junit.jupiter.api.Assertions.assertNull;
//import static org.junit.jupiter.api.Assertions.assertTrue;
//import static org.mockito.ArgumentMatchers.anyString;
//import static org.mockito.ArgumentMatchers.eq;
//import static org.mockito.Mockito.mock;
//import static org.mockito.Mockito.when;
//
//class AiCodeGeneratorFacadeStreamingTest {
//
//    @Test
//    void tryExtractWriteFileArguments_shouldHandleStrictLongContent() {
//        String rawArguments = """
//                {"relativeFilePath":"src/App.vue","content":"<template>\\n  <div class=\\"app\\">{{ title }}<\\/div>\\n<\\/template>\\n<script setup>\\nconst title = `hello \\"world\\"`;\\nconst path = \\"C:\\\\\\\\temp\\\\\\\\demo\\";\\n<\\/script>\\n<style>\\n.app::after { content: \\"```\\"; }\\n<\\/style>"}
//                """;
//
//        JSONObject extracted = AiCodeGeneratorFacade.tryExtractWriteFileArguments(rawArguments);
//
//        assertNotNull(extracted);
//        assertEquals("src/App.vue", extracted.getStr("relativeFilePath"));
//        assertTrue(extracted.getStr("content").contains("const title = `hello \"world\"`;"));
//        assertTrue(extracted.getStr("content").contains("temp"));
//        assertTrue(extracted.getStr("content").contains("demo"));
//        assertTrue(extracted.getStr("content").contains("```"));
//    }
//
//    @Test
//    void tryExtractWriteFileArguments_shouldTolerateBrokenTailAfterClosedContent() {
//        String rawArguments = """
//                {"relativeFilePath":"src/main.ts","content":"console.log(\\"ok\\");"}n
//                """;
//
//        JSONObject extracted = AiCodeGeneratorFacade.tryExtractWriteFileArguments(rawArguments);
//
//        assertNotNull(extracted);
//        assertEquals("src/main.ts", extracted.getStr("relativeFilePath"));
//        assertEquals("console.log(\"ok\");", extracted.getStr("content"));
//    }
//
//    @Test
//    void tryExtractWriteFileArguments_shouldReturnNullWhenContentStillIncomplete() {
//        String rawArguments = """
//                {"relativeFilePath":"src/main.ts","content":"console.log(\\"ok\\");
//                """;
//
//        assertNull(AiCodeGeneratorFacade.tryExtractWriteFileArguments(rawArguments));
//    }
//
//    @Test
//    void adaptVueTokenStream_shouldEmitSyntheticForMultipleSequentialWriteFiles() {
//        AiCodeGeneratorFacade facade = newFacadeWithNoopBuilder();
//        FakeTokenStream tokenStream = new FakeTokenStream(stream -> {
//            stream.emitPartialToolExecutionRequest(0, toolRequest("call-1", "writeFile",
//                    "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"<template>first</template>\"}"));
//            stream.emitPartialToolExecutionRequest(1, toolRequest("call-2", "writeFile",
//                    "{\"relativeFilePath\":\"src/main.ts\",\"content\":\"console.log(\\\"second\\\")\"}n"));
//            stream.emitCompleteResponse();
//        });
//
//        List<String> messages = facade.adaptVueTokenStream(tokenStream, 1L, false).collectList().block();
//
//        assertNotNull(messages);
//        assertEquals(4, messages.size());
//        assertEquals(2, countMessagesByType(messages, "tool_request"));
//        assertEquals(2, countMessagesByType(messages, "tool_executed"));
//        assertTrue(hasToolExecutedForPath(messages, "src/App.vue"));
//        assertTrue(hasToolExecutedForPath(messages, "src/main.ts"));
//    }
//
//    @Test
//    void adaptVueTokenStream_shouldSuppressDuplicateNativeToolExecutedAfterSynthetic() {
//        AiCodeGeneratorFacade facade = newFacadeWithNoopBuilder();
//        ToolExecutionRequest request = toolRequest("call-1", "writeFile",
//                "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"<template>hi</template>\"}");
//        FakeTokenStream tokenStream = new FakeTokenStream(stream -> {
//            stream.emitPartialToolExecutionRequest(0, request);
//            stream.emitToolExecuted(request, "");
//            stream.emitCompleteResponse();
//        });
//
//        List<String> messages = facade.adaptVueTokenStream(tokenStream, 1L, false).collectList().block();
//
//        assertNotNull(messages);
//        assertEquals(1, countMessagesByType(messages, "tool_executed"));
//    }
//
//    @Test
//    void adaptVueTokenStream_shouldSuppressAllNativeToolExecutedForSameIdAfterSynthetic() {
//        AiCodeGeneratorFacade facade = newFacadeWithNoopBuilder();
//        ToolExecutionRequest request = toolRequest("call-1", "writeFile",
//                "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"<template>hi</template>\"}");
//        FakeTokenStream tokenStream = new FakeTokenStream(stream -> {
//            stream.emitPartialToolExecutionRequest(0, request);
//            stream.emitToolExecuted(request, "");
//            stream.emitToolExecuted(request, "");
//            stream.emitCompleteResponse();
//        });
//
//        List<String> messages = facade.adaptVueTokenStream(tokenStream, 1L, false).collectList().block();
//
//        assertNotNull(messages);
//        assertEquals(1, countMessagesByType(messages, "tool_executed"));
//    }
//
//    @Test
//    void adaptVueTokenStream_shouldFallbackToNativeToolExecutedWhenSyntheticNeverForms() {
//        AiCodeGeneratorFacade facade = newFacadeWithNoopBuilder();
//        ToolExecutionRequest partialRequest = toolRequest("call-1", "writeFile",
//                "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"unterminated");
//        ToolExecutionRequest nativeRequest = toolRequest("call-1", "writeFile",
//                "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"<template>native</template>\"}");
//        FakeTokenStream tokenStream = new FakeTokenStream(stream -> {
//            stream.emitPartialToolExecutionRequest(0, partialRequest);
//            stream.emitToolExecuted(nativeRequest, "");
//            stream.emitCompleteResponse();
//        });
//
//        List<String> messages = facade.adaptVueTokenStream(tokenStream, 1L, false).collectList().block();
//
//        assertNotNull(messages);
//        assertEquals(1, countMessagesByType(messages, "tool_executed"));
//        assertTrue(messages.stream().anyMatch(msg -> msg.contains("<template>native</template>")));
//    }
//
//    @Test
//    void buildSyntheticWriteFileToolExecutedMessage_shouldNormalizeArgumentsJson() {
//        String message = AiCodeGeneratorFacade.buildSyntheticWriteFileToolExecutedMessage(
//                "call-1",
//                "{\"relativeFilePath\":\"src/main.ts\",\"content\":\"console.log(\\\"ok\\\")\"}n"
//        );
//
//        assertNotNull(message);
//        JSONObject json = JSONUtil.parseObj(message);
//        assertEquals("tool_executed", json.getStr("type"));
//        assertEquals("writeFile", json.getStr("name"));
//        JSONObject arguments = json.getJSONObject("arguments");
//        //String arguments = json.getStr("arguments");
//        //JSONObject arguments = JSONUtil.parseObj("arguments");
//        assertEquals("src/main.ts", arguments.getStr("relativeFilePath"));
//        assertEquals("console.log(\"ok\")", arguments.getStr("content"));
//    }
//
//    @Test
//    void fixCodeTokenStream_shouldEmitMultiChunks() {
//        AiCodeGeneratorFacade facade = newFacadeWithNoopBuilder();
//        FakeTokenStream tokenStream = new FakeTokenStream(stream -> {
//            stream.emitPartialResponse("chunk-1");
//            stream.emitPartialResponse("chunk-2");
//            stream.emitCompleteResponse();
//        });
//
//        @SuppressWarnings("unchecked")
//        Flux<String> flux = (Flux<String>) ReflectionTestUtils.invokeMethod(
//                facade, "fixCodeTokenStream", CodeGenTypeEnum.HTML, tokenStream, 1L, false
//        );
//
//        List<String> chunks = flux.collectList().block();
//        assertNotNull(chunks);
//        assertEquals(List.of("chunk-1", "chunk-2"), chunks);
//    }
//
//    @Test
//    void createCodeTokenStream_shouldEmitChunksAndComplete() {
//        AiCodeGeneratorFacade facade = newFacadeWithNoopBuilder();
//        FakeTokenStream tokenStream = new FakeTokenStream(stream -> {
//            stream.emitPartialResponse("```html\n");
//            stream.emitPartialResponse("<!DOCTYPE html><html><body><h1>ok</h1></body></html>\n");
//            stream.emitPartialResponse("```");
//            stream.emitCompleteResponse();
//        });
//
//        @SuppressWarnings("unchecked")
//        Flux<String> flux = (Flux<String>) ReflectionTestUtils.invokeMethod(
//                facade, "createCodeTokenStream", CodeGenTypeEnum.HTML, tokenStream, 2L, false
//        );
//
//        List<String> chunks = flux.collectList().block();
//        assertNotNull(chunks);
//        assertEquals(3, chunks.size());
//        assertEquals("```html\n", chunks.get(0));
//        assertTrue(chunks.get(1).contains("<!DOCTYPE html>"));
//    }
//
//    @Test
//    void onlyCompleteToolRequest_emitsToolRequestBeforeToolExecuted() {
//        AiCodeGeneratorFacade facade = newFacadeWithNoopBuilder();
//        ToolExecutionRequest completeRequest = toolRequest("call-1", "writeFile",
//                "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"<template>hi</template>\"}");
//        FakeTokenStream tokenStream = new FakeTokenStream(stream -> {
//            stream.emitCompleteToolExecutionRequest(0, completeRequest);
//            stream.emitToolExecuted(completeRequest, "");
//            stream.emitCompleteResponse();
//        });
//
//        List<String> messages = facade.adaptVueTokenStream(tokenStream, 1L, false).collectList().block();
//
//        assertNotNull(messages);
//        assertEquals(2, messages.size());
//        // 第一条必须是 tool_request，第二条才是 tool_executed
//        JSONObject first = JSONUtil.parseObj(messages.get(0));
//        assertEquals("tool_request", first.getStr("type"));
//        JSONObject second = JSONUtil.parseObj(messages.get(1));
//        assertEquals("tool_executed", second.getStr("type"));
//    }
//
//    @Test
//    void duplicatePartialAndComplete_emitsSingleToolRequest() {
//        AiCodeGeneratorFacade facade = newFacadeWithNoopBuilder();
//        ToolExecutionRequest partialRequest = toolRequest("call-1", "writeFile",
//                "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"<template>");
//        ToolExecutionRequest completeRequest = toolRequest("call-1", "writeFile",
//                "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"<template>hi</template>\"}");
//        FakeTokenStream tokenStream = new FakeTokenStream(stream -> {
//            stream.emitPartialToolExecutionRequest(0, partialRequest);
//            stream.emitCompleteToolExecutionRequest(0, completeRequest);
//            stream.emitToolExecuted(completeRequest, "");
//            stream.emitCompleteResponse();
//        });
//
//        List<String> messages = facade.adaptVueTokenStream(tokenStream, 1L, false).collectList().block();
//
//        assertNotNull(messages);
//        // 同一 toolCallId 的 partial + complete 只产生一条 tool_request
//        assertEquals(1, countMessagesByType(messages, "tool_request"));
//        assertTrue(messages.size() >= 2);
//    }
//
//    private static AiCodeGeneratorFacade newFacadeWithNoopBuilder() {
//        AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade();
//        vueProjectBuilder builder = mock(vueProjectBuilder.class);
//        when(builder.buildProject(anyString())).thenReturn(true);
//        ReflectionTestUtils.setField(facade, "vueProjectBuilder", builder);
//
//        BaseTool writeFileTool = mock(BaseTool.class);
//        when(writeFileTool.generateToolRequestResponse()).thenReturn("\n\n[选择工具] 写入文件\n");
//        ToolManager toolManager = mock(ToolManager.class);
//        when(toolManager.getTool(eq("writeFile"))).thenReturn(writeFileTool);
//        ReflectionTestUtils.setField(facade, "toolManager", toolManager);
//        return facade;
//    }
//
//    private static ToolExecutionRequest toolRequest(String id, String name, String arguments) {
//        return ToolExecutionRequest.builder()
//                .id(id)
//                .name(name)
//                .arguments(arguments)
//                .build();
//    }
//
//    private static int countMessagesByType(List<String> messages, String type) {
//        int count = 0;
//        for (String message : messages) {
//            JSONObject json = JSONUtil.parseObj(message);
//            if (type.equals(json.getStr("type"))) {
//                count++;
//            }
//        }
//        return count;
//    }
//
//    private static boolean hasToolExecutedForPath(List<String> messages, String filePath) {
//        for (String message : messages) {
//            JSONObject json = JSONUtil.parseObj(message);
//            if (!"tool_executed".equals(json.getStr("type"))) {
//                continue;
//            }
//            JSONObject arguments = JSONUtil.parseObj(json.getStr("arguments"));
//            if (filePath.equals(arguments.getStr("relativeFilePath"))) {
//                return true;
//            }
//        }
//        return false;
//    }
//
//    private static final class FakeTokenStream implements TokenStream {
//
//        private final Consumer<FakeTokenStream> scenario;
//        private Consumer<String> partialResponseHandler = partial -> {};
//        private BiConsumer<Integer, ToolExecutionRequest> partialToolExecutionHandler = (index, request) -> {};
//        private BiConsumer<Integer, ToolExecutionRequest> completeToolExecutionHandler = (index, request) -> {};
//        private Consumer<ToolExecution> toolExecutedHandler = execution -> {};
//        private Consumer<dev.langchain4j.model.chat.response.ChatResponse> completeResponseHandler = response -> {};
//        private Consumer<Throwable> errorHandler = error -> {};
//
//        private FakeTokenStream(Consumer<FakeTokenStream> scenario) {
//            this.scenario = scenario;
//        }
//
//        @Override
//        public TokenStream onPartialResponse(Consumer<String> partialResponseHandler) {
//            this.partialResponseHandler = partialResponseHandler;
//            return this;
//        }
//
//        @Override
//        public TokenStream onPartialToolExecutionRequest(BiConsumer<Integer, ToolExecutionRequest> toolExecutionRequestHandler) {
//            this.partialToolExecutionHandler = toolExecutionRequestHandler;
//            return this;
//        }
//
//        @Override
//        public TokenStream onCompleteToolExecutionRequest(BiConsumer<Integer, ToolExecutionRequest> completedHandler) {
//            this.completeToolExecutionHandler = completedHandler;
//            return this;
//        }
//
//        @Override
//        public TokenStream onRetrieved(Consumer<List<Content>> contentHandler) {
//            return this;
//        }
//
//        @Override
//        public TokenStream onToolExecuted(Consumer<ToolExecution> toolExecuteHandler) {
//            this.toolExecutedHandler = toolExecuteHandler;
//            return this;
//        }
//
//        @Override
//        public TokenStream onCompleteResponse(Consumer<dev.langchain4j.model.chat.response.ChatResponse> completeResponseHandler) {
//            this.completeResponseHandler = completeResponseHandler;
//            return this;
//        }
//
//        @Override
//        public TokenStream onError(Consumer<Throwable> errorHandler) {
//            this.errorHandler = errorHandler;
//            return this;
//        }
//
//        @Override
//        public TokenStream ignoreErrors() {
//            this.errorHandler = error -> {};
//            return this;
//        }
//
//        @Override
//        public void start() {
//            try {
//                scenario.accept(this);
//            } catch (Throwable t) {
//                errorHandler.accept(t);
//            }
//        }
//
//        private void emitPartialToolExecutionRequest(int index, ToolExecutionRequest request) {
//            partialToolExecutionHandler.accept(index, request);
//        }
//
//        private void emitCompleteToolExecutionRequest(int index, ToolExecutionRequest request) {
//            completeToolExecutionHandler.accept(index, request);
//        }
//
//        private void emitToolExecuted(ToolExecutionRequest request, String result) {
//            toolExecutedHandler.accept(ToolExecution.builder()
//                    .request(request)
//                    .result(result)
//                    .build());
//        }
//
//        private void emitCompleteResponse() {
//            completeResponseHandler.accept(null);
//        }
//
//        @SuppressWarnings("unused")
//        private void emitPartialResponse(String text) {
//            partialResponseHandler.accept(text);
//        }
//    }
//}
