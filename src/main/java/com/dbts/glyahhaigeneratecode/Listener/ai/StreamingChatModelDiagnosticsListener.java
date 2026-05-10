package com.dbts.glyahhaigeneratecode.Listener.ai;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 普通流式 {@code OpenAiStreamingChatModel} 的诊断监听：记录响应元数据，便于区分 LENGTH 截断与其它失败。
 * 构造参数 {@code maxTokens} 为当前 YAML 中的流式 max_tokens，仅用于 LENGTH 告警文案（可为 null）。
 */
@Slf4j
@RequiredArgsConstructor
public final class StreamingChatModelDiagnosticsListener implements ChatModelListener {

    private final Integer maxTokens;

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        ChatResponse r = responseContext.chatResponse();
        FinishReason fr = r.finishReason();
        TokenUsage tu = r.tokenUsage();
        log.info(
                "streaming-chat-model response meta modelName={} id={} finishReason={} tokenUsage={}",
                r.modelName(),
                r.id(),
                fr,
                tu
        );
        if (FinishReason.LENGTH.equals(fr)) {
            log.warn("streaming-chat-model 返回 finishReason=LENGTH，输出可能因 max_tokens 截断。当前配置 maxTokens={}", maxTokens);
        }
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        log.warn(
                "streaming-chat-model error: {}",
                errorContext.error() != null ? errorContext.error().getMessage() : "unknown",
                errorContext.error()
        );
    }
}
