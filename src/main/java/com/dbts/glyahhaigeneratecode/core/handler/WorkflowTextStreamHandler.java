package com.dbts.glyahhaigeneratecode.core.handler;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Workflow 文本流处理器。
 */
@Slf4j
public class WorkflowTextStreamHandler {

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
                    if (!persisted.compareAndSet(false, true)) {
                        return;
                    }
                    String aiResponse = aiResponseBuilder.toString();
                    if (StrUtil.isBlank(aiResponse)) {
                        log.info("workflow stream completed with blank response, skip persist, appId={}", appId);
                        return;
                    }
                    chatHistoryService.addChatMessage(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                })
                .doOnError(error -> {
                    if (persisted.compareAndSet(false, true)) {
                        String errorMessage = "AI回复失败: " + error.getMessage();
                        chatHistoryService.addChatMessage(appId, errorMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    }
                })
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL && persisted.compareAndSet(false, true)) {
                        String partial = aiResponseBuilder.toString();
                        if (StrUtil.isNotBlank(partial)) {
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
