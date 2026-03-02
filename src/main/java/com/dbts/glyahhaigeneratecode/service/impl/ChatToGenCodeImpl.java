package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.core.AiCodeGeneratorFacade;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.dbts.glyahhaigeneratecode.service.ChatToGenCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 应用对话生成代码外观类，串联应用配置、权限校验和代码生成
 * 门面类(工具类)
 * 大致思路: 校验参数 → 查询应用 → 校验只能本人使用 → 拼接 initPrompt + 用户输入 → 调用 AiCodeGeneratorFacade 流式生成并保存代码
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatToGenCodeImpl implements ChatToGenCode {

    private final AppService appService;

    private final AiCodeGeneratorFacade aiCodeGeneratorFacade;

    private final ChatHistoryService chatHistoryService;

    /**
     * 统一入口：基于应用配置和用户输入触发代码生成（流式）
     *
     * @param appId   应用 id
     * @param message 用户输入内容
     * @param user    当前登录用户
     * @return 代码内容的流式输出
     */
    public Flux<String> chatToGenCode(Long appId, String message, User user) {
        // 1. 基础参数校验
        validateParams(appId, message, user);

        // 2. 查询应用信息
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");

        // 3. 权限校验：只有创建人可以使用该应用生成代码
        ThrowUtils.throwIf(!user.getId().equals(app.getUserId()),
                ErrorCode.NO_AUTH_ERROR, "只能使用自己的应用生成代码");

        // 4. 组装最终提示词，调用 AI 代码生成门面（流式）
        String codeGenType = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == null && StrUtil.isNotBlank(codeGenType)) {
            try {
                codeGenTypeEnum = CodeGenTypeEnum.valueOf(codeGenType);
            } catch (IllegalArgumentException ignored) { }
        }
        ThrowUtils.throwIf(codeGenTypeEnum == null, ErrorCode.PARAMS_ERROR, "应用配置的 codeGenType 无效");

        // 5. 保存用户消息到对话历史
        chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), user.getId());

        // 6. 调用 AI 生成代码（流式），收集完整回复后保存 AI 消息
        Long userId = user.getId();
        StringBuilder aiResponseBuilder = new StringBuilder();
        Flux<String> result = aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId);

        return result
                .doOnNext(aiResponseBuilder::append)
                .doOnComplete(() -> {
                    String fullResponse = aiResponseBuilder.toString();
                    if (StrUtil.isNotBlank(fullResponse)) {
                        chatHistoryService.addChatMessage(appId, fullResponse,
                                ChatHistoryMessageTypeEnum.AI.getValue(), userId);
                        // 超过 20 轮时，将最早两轮总结为一轮并同步 Redis，避免超限并节省 Token
                        chatHistoryService.trySummarizeOldestRoundsIfNeeded(appId, userId);
                    }
                })
                .doOnError(e -> {
                    log.error("AI 生成代码异常, appId={}, userId={}", appId, userId, e);
                    try {
                        chatHistoryService.addChatMessage(appId, "AI 回复异常: " + e.getMessage(),
                                ChatHistoryMessageTypeEnum.ERROR.getValue(), userId);
                    } catch (Exception ex) {
                        log.error("保存错误消息到对话历史失败, appId={}, userId={}", appId, userId, ex);
                    }
                });
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
     * 构建最终提示词：用应用 initPrompt 作为前缀，再拼接用户输入
     *
     * @param app     当前应用
     * @param message 用户输入内容
     * @return 最终发送给 AI 的提示词
     */
    private String buildUserMessage(App app, String message) {
        String initPrompt = app.getInitPrompt();
        if (StrUtil.isBlank(initPrompt)) {
            return message;
        }
        return initPrompt + System.lineSeparator() + message;
    }
}
