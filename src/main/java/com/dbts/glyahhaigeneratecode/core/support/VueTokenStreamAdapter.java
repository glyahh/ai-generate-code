package com.dbts.glyahhaigeneratecode.core.support;

import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.ai.model.message.AiResponseMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolExecutedMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolRequestMessage;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.core.Builder.vueProjectBuilder;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Vue TokenStream 适配器：将 LangChain4j TokenStream 转为前端 JSON 行协议，
 * 处理 writeFile 参数的流式拼接与 synthetic tool_executed 提前回放、native 去重、构建触发。
 * <p>
 * 从 {@code AiCodeGeneratorFacade.adaptVueTokenStream} 抽出，降低门面复杂度。
 */
@Slf4j
public final class VueTokenStreamAdapter {

    private static final int WRITE_FILE_EXTRACT_WARN_THRESHOLD =
            LegacyHtmlToolStreamSupport.WRITE_FILE_EXTRACT_WARN_THRESHOLD;

    private VueTokenStreamAdapter() {
    }

    /**
     * 将 LangChain4j Vue TokenStream 转为 JSON 行 Flux，并处理 writeFile 参数流式拼接与构建。
     *
     * @param tokenStream   LangChain4j 流
     * @param appId         应用主键（决定 vue_project_{appId} 目录）
     * @param projectBuilder Vue 项目构建器
     * @return JSON 行流
     */
    public static Flux<String> adapt(TokenStream tokenStream, Long appId, vueProjectBuilder projectBuilder) {
        LegacyHtmlToolStreamSupport.ToolStreamState state =
                new LegacyHtmlToolStreamSupport.ToolStreamState();

        return Flux.<String>create(sink -> {
                    sink.onCancel(() -> {
                        try {
                            tokenStream.cancel();
                        } catch (Exception ignore) {
                        }
                    });

                    tokenStream
                            .onPartialResponse(partial -> {
                                AiResponseMessage msg = new AiResponseMessage(partial);
                                sink.next(JSONUtil.toJsonStr(msg));
                            })
                            .onPartialToolExecutionRequest((index, request) ->
                                    handlePartialToolExecution(sink, state, request))
                            .onCompleteToolExecutionRequest((index, request) ->
                                    handleCompleteToolExecution(sink, state, request))
                            .onToolExecuted(execution ->
                                    handleToolExecuted(sink, state, execution))
                            .onCompleteResponse(response ->
                                    handleComplete(sink, appId, projectBuilder))
                            .onError(error -> {
                                log.error("Vue TokenStream 异常", error);
                                sink.error(error);
                            })
                            .start();
                }, FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * onPartialToolExecutionRequest：每个 toolCallId 仅首次 emit ToolRequestMessage，
     * writeFile 参数累积到可提取 path+content 时提前合成 tool_executed 卡片。
     */
    private static void handlePartialToolExecution(
            FluxSink<String> sink,
            LegacyHtmlToolStreamSupport.ToolStreamState state,
            dev.langchain4j.agent.tool.ToolExecutionRequest request) {
        String toolCallId = request.id();
        if (toolCallId != null && state.seenToolRequestIds.add(toolCallId)) {
            ToolRequestMessage msg = new ToolRequestMessage(request);
            sink.next(JSONUtil.toJsonStr(msg));
        }
        try {
            String toolName = request.name();
            String argsPart = request.arguments();
            if (state.nativeToolExecutedMode.get()
                    || toolCallId == null
                    || toolName == null
                    || argsPart == null
                    || state.syntheticExecutedIds.contains(toolCallId)) {
                return;
            }
            StringBuilder buf = state.toolArgsById.computeIfAbsent(toolCallId, k -> new StringBuilder());
            buf.append(argsPart);

            if ("writeFile".equals(toolName)) {
                String synthetic = LegacyHtmlToolStreamSupport
                        .buildSyntheticWriteFileToolExecutedMessage(toolCallId, buf.toString());
                if (synthetic != null) {
                    state.syntheticExecutedIds.add(toolCallId);
                    sink.next(synthetic);
                } else if (buf.length() >= WRITE_FILE_EXTRACT_WARN_THRESHOLD
                        && state.warnedLargeIncompleteIds.add(toolCallId)) {
                    log.warn("writeFile 参数流超过 {} 字节仍未提取出 relativeFilePath/content，继续等待后续片段。toolCallId={}",
                            WRITE_FILE_EXTRACT_WARN_THRESHOLD, toolCallId);
                }
            }
        } catch (Exception e) {
            log.warn("Vue partial tool execution 处理异常 toolCallId={}", request.id(), e);
        }
    }

    /**
     * onCompleteToolExecutionRequest：对 partial 期间未发出的 toolCallId 补发 ToolRequestMessage。
     */
    private static void handleCompleteToolExecution(
            FluxSink<String> sink,
            LegacyHtmlToolStreamSupport.ToolStreamState state,
            dev.langchain4j.agent.tool.ToolExecutionRequest request) {
        String toolCallId = request.id();
        if (toolCallId != null && state.seenToolRequestIds.add(toolCallId)) {
            ToolRequestMessage msg = new ToolRequestMessage(request);
            sink.next(JSONUtil.toJsonStr(msg));
        }
    }

    /**
     * onToolExecuted：原生工具执行回调；若该 toolCallId 已发过 synthetic，跳过避免重复渲染。
     */
    private static void handleToolExecuted(
            FluxSink<String> sink,
            LegacyHtmlToolStreamSupport.ToolStreamState state,
            ToolExecution execution) {
        try {
            String toolCallId = execution.request().id();
            state.nativeToolExecutedMode.compareAndSet(false, true);
            if (toolCallId != null && state.syntheticExecutedIds.contains(toolCallId)) {
                return;
            }
        } catch (Exception e) {
            log.warn("Vue tool executed 跳过判断异常", e);
        }
        ToolExecutedMessage msg = new ToolExecutedMessage(execution);
        sink.next(JSONUtil.toJsonStr(msg));
    }

    /**
     * onCompleteResponse：流结束触发本地 npm build，供预览使用。
     */
    private static void handleComplete(
            FluxSink<String> sink,
            Long appId,
            vueProjectBuilder projectBuilder) {
        try {
            String projectDirName = "vue_project_" + appId;
            Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName);
            String path = projectRoot.toString();
            boolean ok = projectBuilder.buildProject(path);
            if (!ok) {
                log.warn("Vue 项目构建未成功，预览可能不可用。appId={} path={}", appId, path);
            }
        } catch (Exception e) {
            log.error("Vue 项目构建异常。appId={}", appId, e);
        }
        sink.complete();
    }
}
