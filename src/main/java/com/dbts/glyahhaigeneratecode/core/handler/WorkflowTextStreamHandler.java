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
    // 历史会话记录
    // 历史会话记录不再做硬截断：避免超长代码在入库阶段丢失，导致后续“修改/增量编辑”无法基于完整原文进行。

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
                    // 会话结束后，如果已经保存过，则返回
                    // 只有第一次把 false 改成 true 的那条路径能执行入库
                    if (!persisted.compareAndSet(false, true)) {
                        return;
                    }
                    
                    // 在会话结束后保存到数据库时,清理消息
                    String aiResponse = sanitizeBeforePersist(aiResponseBuilder.toString());
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
                        // 在会话中毒截断时,清理消息
                        String partial = sanitizeBeforePersist(aiResponseBuilder.toString());
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

    /**
     * 在入库前清理消息，避免历史会话记录异常膨胀。
     * @param message
     * @return
     */
    private String sanitizeBeforePersist(String message) {
        // 1. 非空验证
        if (StrUtil.isBlank(message)) {
            return "";
        }

        // 2. 按行分割
        String[] lines = message.split("\\r?\\n");
        StringBuilder cleaned = new StringBuilder(message.length());

        // 3. 逐行处理
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            // 3.1 跳过 workflow旁路通知 噪声
            if (trimmed.startsWith("[workflow_notice]")) {
                continue;
            }
            // 3.2 跳过 workflow 噪声
            if (trimmed.startsWith("[workflow]")) {
                continue;
            }
            // 3.4 拼接行
            if (cleaned.length() > 0) {
                cleaned.append('\n');
            }
            cleaned.append(line == null ? "" : line);
        }

        // 4.1 返回拼接结果（不再做硬截断，避免丢失超长代码）
        String result = cleaned.toString().trim();
        return result;
    }
}
