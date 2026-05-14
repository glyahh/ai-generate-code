package com.dbts.glyahhaigeneratecode.core.handler;

import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流处理器执行器
 * 根据代码生成类型创建合适的流处理器：
 * 1. 传统的 Flux<String> 流（HTML、MULTI_FILE） -> SimpleTextStreamHandler
 * 2. TokenStream 格式的复杂流（VUE_PROJECT） -> JsonMessageStreamHandler
 */
@Slf4j
@Component
public class StreamHandlerExecutor {

    @Resource
    private JsonMessageStreamHandler jsonMessageStreamHandler;

    private final SimpleTextStreamHandler simpleTextStreamHandler = new SimpleTextStreamHandler();
    private final WorkflowTextStreamHandler workflowTextStreamHandler = new WorkflowTextStreamHandler();

    /**
     * 创建流处理器并处理聊天历史记录
     *
     * @param originFlux         原始流
     * @param chatHistoryService 聊天历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @param codeGenType        代码生成类型
     * @return 处理后的流
     */
    public Flux<String> doExecute(Flux<String> originFlux,
                                  ChatHistoryService chatHistoryService,
                                  long appId, User loginUser, CodeGenTypeEnum codeGenType,
                                  boolean workflowMode, boolean firstRound, String userMessage, Long roundId) {
        long startMs = System.currentTimeMillis();
        AtomicBoolean once = new AtomicBoolean(false);
        int[] bufferChars = new int[]{0};

        Flux<String> handledFlux;
        if (workflowMode) {
            handledFlux = workflowTextStreamHandler.handle(originFlux, chatHistoryService, appId, loginUser);
        } else {
            handledFlux = switch (codeGenType) {
                case VUE -> // 使用注入的组件实例
                        jsonMessageStreamHandler.handle(originFlux, chatHistoryService, appId, loginUser, firstRound, userMessage);
                case HTML, MULTI_FILE -> // 简单文本处理器不需要依赖注入
                        simpleTextStreamHandler.handle(originFlux, chatHistoryService, appId, loginUser);
            };
        }

        return handledFlux
                .doOnNext(chunk -> {
                    // 1. 聚合流式输出长度，用于 onRoundCompleted 指标上报。
                    if (chunk != null) {
                        bufferChars[0] += chunk.length();
                    }
                })
                .doFinally(signal -> {
                    // 只有当当前值是 false 时，才把它原子地改成 true，并返回 true；
                    // 如果当前值已经不是 false（例如已经是 true），则不改，返回 false。

                    /**
                     * 第一次进入 doFinally 时：值是 false → compareAndSet(false, true) 成功 → 返回 true → !true 为假 → 不 return，继续执行后面的 onRoundCompleted。
                     * 若再次进入同一段逻辑
                     * （理论上 Reactor 对单次订阅的 doFinally 通常只调一次，但这里属于防御式写法，或应对重试/重复终止等边界）：
                     *  值已是 true → CAS 失败 → 返回 false → !false 为真 → 直接 return，后面的收尾不会再跑。
                     */
                    if (!once.compareAndSet(false, true)) {
                        return;
                    }
                    if (roundId == null || roundId <= 0) {
                        log.warn("onRoundCompleted 跳过，roundId 非法，appId={}, roundId={}", appId, roundId);
                        return;
                    }
                    try {
                        // 1. 统一在执行器层收口，确保 complete/error/cancel 只触发一次。
                        // 2. onRoundCompleted 内部自吞异常，保证不反向影响 SSE 主链路。
                        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startMs);
                        chatHistoryService.onRoundCompleted(appId, roundId, loginUser.getId(), codeGenType, workflowMode, bufferChars[0], elapsedMs);
                    } catch (Exception e) {
                        log.warn("onRoundCompleted 执行失败已忽略，appId={}, roundId={}, signal={}",
                                appId, roundId, signal == null ? SignalType.ON_COMPLETE : signal, e);
                    }
        });
    }
}
