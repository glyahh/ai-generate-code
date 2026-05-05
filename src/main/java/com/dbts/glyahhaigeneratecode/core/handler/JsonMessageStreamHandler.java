package com.dbts.glyahhaigeneratecode.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.ai.model.message.*;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.ai.tool.ToolManager;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.guardrail.RetryOutputGuardrail;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JSON 消息流处理器
 * 处理 VUE_PROJECT 类型的复杂流式响应，包含工具调用信息
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    @Resource
    private ToolManager toolManager;

    private final RetryOutputGuardrail retryOutputGuardrail = new RetryOutputGuardrail();

    /**
     * 处理 TokenStream（VUE_PROJECT）
     * 解析 JSON 消息并重组为完整的响应格式
     *
     * @param originFlux         原始流
     * @param chatHistoryService 聊天历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @return 处理后的流
     */
    public Flux<String> handle(Flux<String> originFlux,
                               ChatHistoryService chatHistoryService,
                               long appId, User loginUser,
                               boolean firstRound, String userMessage) {

        // 收集数据用于生成后端记忆格式
        StringBuilder chatHistoryStringBuilder = new StringBuilder();
        // 用于跟踪已经见过的工具ID，判断是否是第一次调用
        Set<String> seenToolIds = new HashSet<>();
        AtomicBoolean hasToolCall = new AtomicBoolean(false);
        AtomicBoolean firstRoundToolViolationNotified = new AtomicBoolean(false);
        boolean editModeIntent = isEditModeIntent(userMessage);

        Flux<String> mainFlux = originFlux

                .map(chunk -> {
                    // 解析每个 JSON 消息块,见下方函数
                    // 指定消息拼接方式 请求+结果(卡片)
                    return handleJsonMessageChunk(
                            chunk,
                            chatHistoryStringBuilder,
                            seenToolIds,
                            firstRound,
                            hasToolCall,
                            firstRoundToolViolationNotified
                    );
                })

                .filter(StrUtil::isNotEmpty) // 过滤空字串

                .doOnComplete(() -> {
                    // 流式响应完成后，添加 AI 消息到对话历史, 保存到MySQL中
                    String aiResponse = chatHistoryStringBuilder.toString();
                    chatHistoryService.addChatMessage(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                })

                .doOnError(error -> {
                    // 如果AI回复失败，也要记录错误消息
                    String errorMessage = "AI回复失败: " + error.getMessage();
                    chatHistoryService.addChatMessage(appId, errorMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                });

        return mainFlux.concatWith(Mono.defer(() -> {
            if (retryOutputGuardrail.shouldWarnEditModeWithoutToolCall(editModeIntent, hasToolCall.get())) {
                String warning = "【工具调用异常】检测到你在编辑模式修改代码，但本轮未发生工具调用，代码可能生成异常。";
                chatHistoryStringBuilder.append(warning);
                return Mono.just(warning);
            }
            return Mono.empty();
        }));
    }

    /**
     * 解析并收集 TokenStream 数据
     * 将不同的chunk转化成对应的json字符串
     */
    private String handleJsonMessageChunk(String chunk,
                                          StringBuilder chatHistoryStringBuilder,
                                          Set<String> seenToolIds,
                                          boolean firstRound,
                                          AtomicBoolean hasToolCall,
                                          AtomicBoolean firstRoundToolViolationNotified) {
        // 容错说明：流式场景下个别 chunk 可能是截断/脏 JSON，不能让单个坏块中断整条 SSE。
        // 证据：前端出现 "A JSONObject text must end with '}'" 时，根因是这里直接解析失败并向上抛出。
        StreamMessage streamMessage;
        try {
            streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
        } catch (Exception e) {
            log.warn("忽略无法解析的流式 JSON chunk，避免中断整条会话。chunk={}", truncate(chunk, 240));
            return "";
        }
        StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
        if (typeEnum != null) {
            switch (typeEnum) {

                case AI_RESPONSE -> {
                    AiResponseMessage aiMessage = JSONUtil.toBean(chunk, AiResponseMessage.class);
                    String data = aiMessage.getData();
                    // 直接拼接响应
                    chatHistoryStringBuilder.append(data);
                    return data;
                }

                case TOOL_REQUEST -> {
                    ToolRequestMessage toolRequestMessage = JSONUtil.toBean(chunk, ToolRequestMessage.class);
                    String toolId = toolRequestMessage.getId();
                    String toolName = StrUtil.blankToDefault(toolRequestMessage.getName(), "未知工具");
                    hasToolCall.set(true);
                    if (!retryOutputGuardrail.isFirstRoundToolAllowed(firstRound, toolName)) {
                        String warning = "【工具调用异常】首轮仅允许调用 writeFile，检测到非法工具 " + toolName + "，代码可能生成异常。";
                        if (firstRoundToolViolationNotified.compareAndSet(false, true)) {
                            chatHistoryStringBuilder.append(warning);
                            return warning;
                        }
                        return "";
                    }
                    // 检查是否是第一次看到这个工具 ID
                    if (toolId != null && !seenToolIds.contains(toolId)) {
                        seenToolIds.add(toolId);
                        BaseTool tool = toolManager.getTool(toolRequestMessage.getName());
                        if (tool != null) {
                            return tool.generateToolRequestResponse();
                        } else {
                            return String.format("[选择工具] %s", toolName);
                        }
                    }
                    // 回档策略：工具请求阶段不再流式输出参数内容，避免“逐行/逐字符抖动”影响实时体验。
                    return "";
                }

                case TOOL_EXECUTED -> {
                    ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(chunk, ToolExecutedMessage.class);
                    // 容错说明：部分模型会产出非严格 JSON 的 arguments，这里降级为 raw 字段而非抛异常。
                    JSONObject jsonObject = safeParseArguments(toolExecutedMessage.getArguments());
                    BaseTool tool = toolManager.getTool(toolExecutedMessage.getName());
                    String result;
                    if (tool != null) {
                        result = tool.generateToolExecutedResult(jsonObject);
                    } else {
                        // 未注册工具时兜底（兼容旧流式消息或名称不一致）
                        result = fallbackToolExecutedFormatting(toolExecutedMessage.getName(), jsonObject);
                    }
                    String output = String.format("%s", result);
                    chatHistoryStringBuilder.append(output);
                    return output;
                }
                default -> {
                    log.error("不支持的消息类型: {}", typeEnum);
                    return "";
                }
            }
        }
        throw new MyException(ErrorCode.SYSTEM_ERROR, "不支持的消息类型");
    }

    private boolean isEditModeIntent(String userMessage) {
        if (StrUtil.isBlank(userMessage)) {
            return false;
        }
        String lower = userMessage.toLowerCase();
        String[] keywords = {"修改", "重构", "优化", "修复", "改一下", "edit", "refactor", "modify", "fix"};
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 安全解析工具参数，避免 parseObj 异常打断 SSE。
     */
    private JSONObject safeParseArguments(String arguments) {
        if (StrUtil.isBlank(arguments)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(arguments);
        } catch (Exception e) {
            JSONObject fallback = new JSONObject();
            fallback.set("_rawArguments", arguments);
            log.warn("工具参数非严格 JSON，已降级为原始字符串。arguments={}", truncate(arguments, 240));
            return fallback;
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    /**
     * 当 {@link ToolManager} 中找不到对应工具时的兜底：固定前缀「滚木工具」，仅展示工具英文名与路径，不展开参数正文。
     */
    private String fallbackToolExecutedFormatting(String toolName, JSONObject jsonObject) {
        String path = StrUtil.blankToDefault(
                jsonObject.getStr("relativeFilePath"),
                jsonObject.getStr("relativeDirPath"));
        return String.format("[滚木工具] %s %s",
                StrUtil.blankToDefault(toolName, "滚木"),
                StrUtil.blankToDefault(path, "-"));
    }
}
