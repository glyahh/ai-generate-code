package com.dbts.glyahhaigeneratecode.core.handler;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Workflow 文本流处理器。
 */
@Slf4j
public class WorkflowTextStreamHandler {
    // 历史会话记录
    private static final int WORKFLOW_HISTORY_MAX_LENGTH = 80_000;

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
        Set<String> seenToolRequestLines = new HashSet<>();
        Set<String> seenToolExecutedLines = new HashSet<>();

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
            // 3.3 仅清洗重复工具协议块：历史脏数据可能重复落库，导致回放卡片倍增
            if (trimmed.startsWith("[选择工具]")) {
                if (!seenToolRequestLines.add(trimmed)) {
                    continue;
                }
            }
            if (trimmed.startsWith("[工具调用]")) {
                if (!seenToolExecutedLines.add(trimmed)) {
                    continue;
                }
            }
            // 3.4 拼接行
            if (cleaned.length() > 0) {
                cleaned.append('\n');
            }
            cleaned.append(line == null ? "" : line);
        }

        // 4.1 如果不超过长度限制,则返回拼接结果
        String result = cleaned.toString().trim();
        if (result.length() <= WORKFLOW_HISTORY_MAX_LENGTH) {
            return result;
        }

        // 4.2 如果超过长度限制,则截断并添加截断标记
        return result.substring(0, WORKFLOW_HISTORY_MAX_LENGTH) + "\n...[workflow message truncated]";
    }
}
