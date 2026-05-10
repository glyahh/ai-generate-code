package com.dbts.glyahhaigeneratecode.core.handler;

import com.dbts.glyahhaigeneratecode.core.util.LegacyHtmlStreamIntegrity;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 简单文本流处理器。
 */
@Slf4j
public class SimpleTextStreamHandler {

    public Flux<String> handle(Flux<String> originFlux,
                               ChatHistoryService chatHistoryService,
                               long appId,
                               User loginUser) {
        StringBuilder aiResponseBuilder = new StringBuilder();
        AtomicBoolean persisted = new AtomicBoolean(false);

        return originFlux
                .map(chunk -> {
                    aiResponseBuilder.append(chunk);
                    return chunk;
                })
                .doOnComplete(() -> {
                    if (persisted.compareAndSet(false, true)) {
                        String raw = aiResponseBuilder.toString();
                        String aiResponse = LegacyHtmlStreamIntegrity.appendIntegrityNoticeIfNeeded(raw);
                        if (!raw.equals(aiResponse)) {
                            log.warn("legacy AI 消息疑似末尾截断（未闭合标签样式），已追加提示，appId={} charLen={}", appId, raw.length());
                        }
                        log.info("SimpleTextStreamHandler doOnComplete appId={} aiCharLen={}", appId, aiResponse.length());
                        chatHistoryService.addChatMessage(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    }
                })
                .doOnError(error -> {
                    if (persisted.compareAndSet(false, true)) {
                        String errorMessage = "AI回复失败: " + error.getMessage();
                        chatHistoryService.addChatMessage(appId, errorMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    }
                })
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        log.warn("SimpleTextStreamHandler flux CANCEL appId={} bufferedChars={}", appId, aiResponseBuilder.length());
                    }
                    if (signal == SignalType.CANCEL && persisted.compareAndSet(false, true)) {
                        String partial = LegacyHtmlStreamIntegrity.appendIntegrityNoticeIfNeeded(aiResponseBuilder.toString());
                        if (!partial.isBlank()) {
                            chatHistoryService.addChatMessage(
                                    appId,
                                    partial + "\n\n[中断]",
                                    ChatHistoryMessageTypeEnum.AI.getValue(),
                                    loginUser.getId()
                            );
                        }
                    }
                });
    }
}
