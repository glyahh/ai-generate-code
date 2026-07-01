package com.dbts.glyahhaigeneratecode.core.support;

import cn.hutool.json.JSONObject;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.ai.tool.ToolManager;
import com.dbts.glyahhaigeneratecode.core.support.LegacyHtmlToolStreamSupport.ToolStreamState;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * HTML / MULTI_FILE：LangChain4j TokenStream → 纯文本 Flux。
 */
public final class HtmlMultiFileTokenStreamAdapter {

    private HtmlMultiFileTokenStreamAdapter() {
    }

    public static Flux<String> adapt(
            TokenStream tokenStream,
            CodeGenTypeEnum codeGenTypeEnum,
            Long appId,
            boolean persistOnComplete,
            ToolManager toolManager,
            Consumer<String> fullTextPersister) {
        ToolStreamState state = new ToolStreamState();

        return Flux.<String>create(sink -> {
            sink.onCancel(() -> {
                try {
                    tokenStream.cancel();
                } catch (Exception ignored) {
                }
            });

            AtomicBoolean toolRound = new AtomicBoolean(false);
            AtomicBoolean exitExecuted = new AtomicBoolean(false);
            AtomicBoolean toolWroteDisk = new AtomicBoolean(false);
            StringBuilder codeBuilder = persistOnComplete ? new StringBuilder() : null;

            tokenStream
                    .onPartialResponse(partial -> {
                        if (partial == null || partial.isEmpty()) {
                            return;
                        }
                        if (toolRound.get() && !exitExecuted.get()) {
                            return;
                        }
                        if (codeBuilder != null) {
                            codeBuilder.append(partial);
                        }
                        sink.next(partial);
                    })
                    .onPartialToolExecutionRequest((index, request) -> {
                        toolRound.set(true);
                        LegacyHtmlToolStreamSupport.emitLegacyHtmlToolStreamChunk(
                                toolManager, sink, appId, codeGenTypeEnum, request, state, true);
                    })
                    .onCompleteToolExecutionRequest((index, request) ->
                            LegacyHtmlToolStreamSupport.emitLegacyHtmlToolStreamChunk(
                                    toolManager, sink, appId, codeGenTypeEnum, request, state, false))
                    .onToolExecuted(execution -> {
                        state.nativeToolExecutedMode.compareAndSet(false, true);
                        String toolCallId = execution.request().id();
                        if (toolCallId != null && state.syntheticExecutedIds.contains(toolCallId)) {
                            return;
                        }
                        String name = execution.request().name();
                        JSONObject args = LegacyHtmlToolStreamSupport.safeParseToolArgumentsForStream(
                                execution.request().arguments());
                        if ("modifyFile".equals(name) || "writeFile".equals(name) || "deleteFile".equals(name)) {
                            toolWroteDisk.set(true);
                        }
                        if ("exit".equals(name)) {
                            exitExecuted.set(true);
                        }
                        BaseTool tool = toolManager.getTool(name);
                        String out = tool != null
                                ? tool.generateToolExecutedResult(args)
                                : LegacyHtmlToolStreamSupport.fallbackToolExecutedPlain(name, args);
                        if (!out.isEmpty()) {
                            sink.next(out);
                        }
                    })
                    .onCompleteResponse(response -> {
                        if (persistOnComplete && codeBuilder != null && !toolWroteDisk.get()
                                && fullTextPersister != null) {
                            fullTextPersister.accept(codeBuilder.toString());
                        }
                        sink.complete();
                    })
                    .onError(sink::error)
                    .start();
        }, FluxSink.OverflowStrategy.BUFFER);
    }
}
