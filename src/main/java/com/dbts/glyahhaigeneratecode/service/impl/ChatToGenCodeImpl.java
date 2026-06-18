package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.core.AiCodeGeneratorFacade;
import com.dbts.glyahhaigeneratecode.core.WorkflowCodeGeneratorFacade;
import com.dbts.glyahhaigeneratecode.core.handler.StreamHandlerExecutor;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import dev.langchain4j.guardrail.InputGuardrailException;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.guardrail.PromptSafetyAuditEvaluator;
import com.dbts.glyahhaigeneratecode.guardrail.PromptSafetyAuditResult;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.UserPersonalizationService;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.dbts.glyahhaigeneratecode.service.ChatToGenCode;
import com.dbts.glyahhaigeneratecode.service.support.LoopInjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 应用对话生成代码外观类，串联应用配置、权限校验和代码生成
 * 门面类(工具类)
 * 大致思路: 校验参数 → 查询应用 → 校验只能本人使用 → 拼接 initPrompt + 用户输入 → 调用 AiCodeGeneratorFacade 流式生成并保存代码
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatToGenCodeImpl implements ChatToGenCode {
    private static final long REQUEST_DEDUP_WINDOW_MS = 12_000L;
    private static final int REQUEST_DEDUP_CACHE_MAX_SIZE = 10_000;
    private static final Map<String, Long> REQUEST_DEDUP_CACHE = new ConcurrentHashMap<>();
    private static final Map<Long, ReentrantLock> FIRST_ROUND_LOCKS = new ConcurrentHashMap<>();

    private final AppService appService;

    private final AiCodeGeneratorFacade aiCodeGeneratorFacade;
    private final WorkflowCodeGeneratorFacade workflowCodeGeneratorFacade;

    private final ChatHistoryService chatHistoryService;

    private final StreamHandlerExecutor streamHandlerExecutor;

    private final UserPersonalizationService userPersonalizationService;

    private final LoopInjectService loopInjectService;
    /**
     * 统一入口：基于应用配置和用户输入触发代码生成（流式）
     *
     * @param appId   应用 id
     * @param message 用户输入内容
     * @param loopId  要注入的 Loop ID（可选）
     * @param user    当前登录用户
     * @return 代码内容的流式输出
     */
    public Flux<String> chatToGenCode(Long appId, String message, Long loopId, User user) {
        // 1. 基础参数校验
        validateParams(appId, message, user);

        // 2. 查询应用信息
        App app = appService.getById(appId);
        if (app == null) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }

        // 3. 权限校验：只有创建人可以使用该应用生成代码
        ThrowUtils.throwIf(!user.getId().equals(app.getUserId()),
                ErrorCode.NO_AUTH_ERROR, "只能使用自己的应用生成代码");

        // 4. 组装最终提示词，调用 AI 代码生成门面（流式）
        CodeGenTypeEnum codeGenTypeEnum = resolveCodeGenType(app.getCodeGenType());
        ThrowUtils.throwIf(codeGenTypeEnum == null, ErrorCode.PARAMS_ERROR, "应用配置的 codeGenType 无效");

        assertRequestNotDuplicate(appId, user.getId(), message, "legacy");

        // 4.5 首轮判定：同一 appId 串行判定 + 入库，避免并发下都读到 rounds=0
        final boolean firstRound;
        final Long roundId;
        ReentrantLock lock = getFirstRoundLock(appId);
        lock.lock();
        try {
            firstRound = chatHistoryService.isFirstRound(appId, false);

            // 5. 保存用户消息到对话历史(Mysql), 入链路前进行最小审查，并写入审查日志 + 会话扩展字段
            PromptSafetyAuditResult auditResult = PromptSafetyAuditEvaluator.evaluate(message);
            log.info("prompt审查结果, appId={}, userId={}, blocked={}, rule={}, action={}",
                    appId, user.getId(), auditResult.isBlocked(), auditResult.getHitRule(), auditResult.getAction());
            roundId = chatHistoryService.addChatMessageAndReturnId(
                    appId,
                    message,
                    ChatHistoryMessageTypeEnum.USER.getValue(),
                    user.getId(),
                    auditResult.getAction(),
                    auditResult.getHitRule()
            );
            ThrowUtils.throwIf(roundId == null || roundId <= 0, ErrorCode.SYSTEM_ERROR, "用户消息入库失败");
            ThrowUtils.throwIf(auditResult.isBlocked(), ErrorCode.PARAMS_ERROR, auditResult.getUserMessage());
        } finally {
            lock.unlock();
        }

        // 6. 注入个性化 prompt + loop skill（注入顺序：personalization → message → loop_skill）
        String enhancedMessage = injectPersonalizationPrompt(message, user.getId());
        enhancedMessage = loopInjectService.injectIfPresent(enhancedMessage, user.getId(), appId, loopId);
        Flux<String> result = aiCodeGeneratorFacade.generateAndSaveCodeStream(enhancedMessage, codeGenTypeEnum, appId, firstRound);
        Flux<String> handlerFlux = streamHandlerExecutor.doExecute(result, chatHistoryService, appId, user, codeGenTypeEnum, false, firstRound, message, roundId);

        // 7. 在 AI 生成流前插入压缩阶段
        //    压缩在 boundedElastic 线程异步执行，避免阻塞 Netty event loop
        //    使用 Flux.concat 确保压缩完成后再进入 AI 生成阶段
        //    压缩触发时发射 [memory_compress_start/end] 标记，由 Controller 转为 SSE 事件通知前端
        //    使用 Flux.defer 惰性执行
        Flux<String> compressPhase = Flux.defer(() -> {
            // 是否成功压缩了历史记忆
            boolean didCompress = chatHistoryService.trySummarizeOldestRoundsIfNeeded(appId, user.getId(), "entry_normal");
            if (didCompress) {
                return Flux.just("[memory_compress_start]", "[memory_compress_end]");
            }
            return Flux.empty();
            //
        }).subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(compressPhase, handlerFlux);
    }

    @Override
    public Flux<String> chatToGenCodeByWorkflow(Long appId, String message, User user) {
        validateParams(appId, message, user);
        String originalPrompt = message;

        App app = appService.getById(appId);
        if (app == null) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        ThrowUtils.throwIf(!user.getId().equals(app.getUserId()),
                ErrorCode.NO_AUTH_ERROR, "只能使用自己的应用生成代码");

        CodeGenTypeEnum codeGenTypeEnum = resolveCodeGenType(app.getCodeGenType());
        ThrowUtils.throwIf(codeGenTypeEnum == null, ErrorCode.PARAMS_ERROR, "应用配置的 codeGenType 无效");

        assertRequestNotDuplicate(appId, user.getId(), message, "workflow");

        final boolean firstRound;
        final Long roundId;
        ReentrantLock lock = getFirstRoundLock(appId);
        lock.lock();
        try {
            firstRound = chatHistoryService.isFirstRound(appId, false);

            PromptSafetyAuditResult auditResult = PromptSafetyAuditEvaluator.evaluate(message);
            log.info("workflow prompt审查结果, appId={}, userId={}, blocked={}, rule={}, action={}",
                    appId, user.getId(), auditResult.isBlocked(), auditResult.getHitRule(), auditResult.getAction());
            roundId = chatHistoryService.addChatMessageAndReturnId(
                    appId,
                    message,
                    ChatHistoryMessageTypeEnum.USER.getValue(),
                    user.getId(),
                    auditResult.getAction(),
                    auditResult.getHitRule()
            );
            ThrowUtils.throwIf(roundId == null || roundId <= 0, ErrorCode.SYSTEM_ERROR, "用户消息入库失败");
            ThrowUtils.throwIf(auditResult.isBlocked(), ErrorCode.PARAMS_ERROR, auditResult.getUserMessage());
        } finally {
            lock.unlock();
        }

        // 获取一手流式string代码（此时不执行压缩，压缩作为 Flux 的第一个阶段）
        String enhancedMessage = injectPersonalizationPrompt(message, user.getId());
        Flux<String> result = workflowCodeGeneratorFacade.generateAndSaveCodeStream(
                enhancedMessage, codeGenTypeEnum, appId, firstRound);

        Flux<String> handlerFlux = streamHandlerExecutor.doExecute(result, chatHistoryService, appId, user, codeGenTypeEnum, true, firstRound, message, roundId)
                .doOnError(InputGuardrailException.class, e -> {
                    chatHistoryService.removeUserMessageByContent(appId, user.getId(), originalPrompt);
                    log.warn("Guardrail 拒绝，已回滚 DB user 消息，appId={}", appId);
                });

        // 在 AI 生成流前插入压缩阶段
        Flux<String> compressPhase = Flux.defer(() -> {
            boolean didCompress = chatHistoryService.trySummarizeOldestRoundsIfNeeded(appId, user.getId(), "entry_workflow");
            if (didCompress) {
                return Flux.just("[memory_compress_start]", "[memory_compress_end]");
            }
            return Flux.empty();
        }).subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(compressPhase, handlerFlux);
    }

    private CodeGenTypeEnum resolveCodeGenType(String codeGenType) {
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == null && StrUtil.isNotBlank(codeGenType)) {
            try {
                codeGenTypeEnum = CodeGenTypeEnum.valueOf(codeGenType);
            } catch (IllegalArgumentException ignored) {
                // 保持和原链路一致：无法识别时交给上层统一抛错
            }
        }
        return codeGenTypeEnum;
    }

    /**
     * 基础参数校验：校验 appId、用户输入和用户对象
     *
     * @param appId   应用 id
     * @param message 用户输入内容
     * @param user    当前登录用户
     */
    private void validateParams(Long appId, String message, User user) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 异常");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户输入内容不能为空");
        ThrowUtils.throwIf(user == null || user.getId() == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录或会话失效");
    }

    /**
     * 获取应用第一次请求锁
     * 第一次请求时,会锁定应用,避免并发下都读到 rounds=0
     * @param appId
     * @return
     */
    private ReentrantLock getFirstRoundLock(Long appId) {
        return FIRST_ROUND_LOCKS.computeIfAbsent(appId, k -> new ReentrantLock());
    }

    private void assertRequestNotDuplicate(Long appId, Long userId, String message, String channel) {
        long now = System.currentTimeMillis();
        // 小成本定期清理，防止 map 无界增长
        if (REQUEST_DEDUP_CACHE.size() > REQUEST_DEDUP_CACHE_MAX_SIZE) {
            REQUEST_DEDUP_CACHE.entrySet().removeIf(e -> now - e.getValue() > REQUEST_DEDUP_WINDOW_MS);
        }
        String key = buildRequestDedupKey(appId, userId, message, channel);
        Long lastTs = REQUEST_DEDUP_CACHE.put(key, now);
        if (lastTs != null && now - lastTs < REQUEST_DEDUP_WINDOW_MS) {
            log.warn("拦截重复请求，appId={}, userId={}, channel={}, gapMs={}", appId, userId, channel, now - lastTs);
            throw new MyException(ErrorCode.TOO_MANY_REQUEST, "请求过于频繁，请稍后重试");
        }
    }

    private String buildRequestDedupKey(Long appId, Long userId, String message, String channel) {
        String normalized = StrUtil.trimToEmpty(message).replaceAll("\\s+", " ");
        String digest = sha256Base64(normalized);
        return appId + "|" + userId + "|" + channel + "|" + digest;
    }

    private String sha256Base64(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception e) {
            // 退化兜底：即使哈希异常也保证 key 构造可用
            return String.valueOf((text == null ? "" : text).hashCode());
        }
    }

    /**
     * 构建最终提示词：用应用 initPrompt 作为前缀，再拼接用户输入
     *
     * @param app     当前应用
     * @param message 用户输入内容
     * @return 最终发送给 AI 的提示词
     */
    @SuppressWarnings("unused")
    private String buildUserMessage(App app, String message) {
        String initPrompt = app.getInitPrompt();
        if (StrUtil.isBlank(initPrompt)) {
            return message;
        }
        return initPrompt + System.lineSeparator() + message;
    }

    /**
     * 若用户已配置个性化 prompt，将其作为前缀注入 userMessage。
     * 优先级：低于本轮用户显式指令、高于系统默认 SystemMessage。
     * 空配置时原样返回 message。
     */
    private String injectPersonalizationPrompt(String message, Long userId) {
        String injectBlock = userPersonalizationService.buildInjectPrompt(userId);
        return StrUtil.isBlank(injectBlock) ? message : injectBlock + message;
    }

}
