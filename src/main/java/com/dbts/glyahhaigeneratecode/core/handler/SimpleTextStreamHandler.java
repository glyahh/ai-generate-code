package com.dbts.glyahhaigeneratecode.core.handler;

import com.dbts.glyahhaigeneratecode.constant.ChatHistoryConstant;
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
 * <p>
 * 用于 HTML / MULTI_FILE 等传统「纯文本 token」SSE：边收边透传，结束时整段入库；
 * 并对疑似截断的 HTML 追加 {@link LegacyHtmlStreamIntegrity} 提示。
 */
@Slf4j
public class SimpleTextStreamHandler {

    /**
     * 包装原始文本流：透传分片、完成/错误/取消时写入一条 AI 聊天记录
     *
     * @param originFlux         上游模型输出的文本分片流
     * @param chatHistoryService 会话持久化服务
     * @param appId              当前应用 ID
     * @param loginUser          登录用户（取 userId 写库）
     * @return 与上游一致的文本流（副作用在 doOnComplete/doOnError/doFinally）
     */
    public Flux<String> handle(Flux<String> originFlux,
                               ChatHistoryService chatHistoryService,
                               long appId,
                               User loginUser) {
        StringBuilder aiResponseBuilder = new StringBuilder();
        AtomicBoolean persisted = new AtomicBoolean(false);

        return originFlux
                .map(chunk -> {
                    // 1. 累积完整 AI 文本，便于结束时一次性入库
                    aiResponseBuilder.append(chunk);
                    // 2. 分片原样下发给前端 SSE
                    return chunk;
                })
                .doOnComplete(() -> {
                    // 1. 仅首次完成路径写库，避免重复 insert
                    if (persisted.compareAndSet(false, true)) {
                        // 2. 取聚合文本并做「末尾疑似截断」提示拼接
                        String raw = aiResponseBuilder.toString();
                        String aiResponse = LegacyHtmlStreamIntegrity.appendIntegrityNoticeIfNeeded(raw);
                        if (!raw.equals(aiResponse)) {
                            log.warn("legacy AI 消息疑似末尾截断（未闭合标签样式），已追加提示，appId={} charLen={}", appId, raw.length());
                        }
                        log.info("SimpleTextStreamHandler doOnComplete appId={} aiCharLen={}", appId, aiResponse.length());
                        // 3. 持久化为一条 AI 消息
                        chatHistoryService.addChatMessage(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    }
                })
                .doOnError(error -> {
                    // 1. 错误路径同样只写一次；保留已累积内容，末尾追加失败提示
                    if (persisted.compareAndSet(false, true)) {
                        log.warn("simple text stream failed, appId={}, type={}", appId, error.getClass().getSimpleName(), error);
                        String partial = aiResponseBuilder.toString();
                        String message;
                        if (!partial.isBlank()) {
                            // 保留工具卡片 + AI 自然语言，末尾追加失败标识
                            message = partial + "\n\n" + ChatHistoryConstant.GENERATION_FAILED_USER_MESSAGE;
                        } else {
                            message = ChatHistoryConstant.GENERATION_FAILED_USER_MESSAGE;
                        }
                        chatHistoryService.addChatMessage(
                                appId,
                                message,
                                ChatHistoryMessageTypeEnum.AI.getValue(),
                                loginUser.getId());
                    }
                })
                .doFinally(signal -> {
                    // 1. 取消时打日志，便于排查客户端断开
                    if (signal == SignalType.CANCEL) {
                        log.warn("SimpleTextStreamHandler flux CANCEL appId={} bufferedChars={}", appId, aiResponseBuilder.length());
                    }
                    // 2. 取消且尚未持久化时，把已缓冲片段（加中断标记）写入历史
                    if (signal == SignalType.CANCEL && persisted.compareAndSet(false, true)) {
                        String partial = LegacyHtmlStreamIntegrity.appendIntegrityNoticeIfNeeded(aiResponseBuilder.toString());
                        if (!partial.isBlank()) {
                            chatHistoryService.addChatMessage(
                                    appId,
                                    partial + "\n\n" + ChatHistoryConstant.GENERATION_INTERRUPTED_MARKER,
                                    ChatHistoryMessageTypeEnum.AI.getValue(),
                                    loginUser.getId()
                            );
                        }
                    }
                });
    }
}
