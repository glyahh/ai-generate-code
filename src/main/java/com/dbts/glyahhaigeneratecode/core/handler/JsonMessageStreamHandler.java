package com.dbts.glyahhaigeneratecode.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.ai.model.message.AiResponseMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.StreamMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.StreamMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolExecutedMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolRequestMessage;
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
import reactor.core.publisher.SignalType;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class JsonMessageStreamHandler {

    @Resource
    private ToolManager toolManager;

    private final RetryOutputGuardrail retryOutputGuardrail = new RetryOutputGuardrail();

    public Flux<String> handle(Flux<String> originFlux,
                               ChatHistoryService chatHistoryService,
                               long appId,
                               User loginUser,
                               boolean firstRound,
                               String userMessage) {

        StringBuilder chatHistoryStringBuilder = new StringBuilder();
        Set<String> seenToolIds = new HashSet<>();
        AtomicBoolean hasToolCall = new AtomicBoolean(false);
        AtomicBoolean firstRoundToolViolationNotified = new AtomicBoolean(false);
        AtomicBoolean persisted = new AtomicBoolean(false);
        boolean editModeIntent = isEditModeIntent(userMessage);

        Flux<String> mainFlux = originFlux
                .map(chunk -> handleJsonMessageChunk(
                        chunk,
                        chatHistoryStringBuilder,
                        seenToolIds,
                        firstRound,
                        hasToolCall,
                        firstRoundToolViolationNotified
                ))
                .filter(StrUtil::isNotEmpty)
                .doOnComplete(() -> {
                    if (persisted.compareAndSet(false, true)) {
                        String aiResponse = chatHistoryStringBuilder.toString();
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
                    if (signal == SignalType.CANCEL && persisted.compareAndSet(false, true)) {
                        String partial = chatHistoryStringBuilder.toString();
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

        return mainFlux.concatWith(Mono.defer(() -> {
            if (retryOutputGuardrail.shouldWarnEditModeWithoutToolCall(editModeIntent, hasToolCall.get())) {
                String warning = "【工具调用异常】检测到你在编辑模式修改代码，但本轮未发生工具调用，代码可能生成异常。";
                chatHistoryStringBuilder.append(warning);
                return Mono.just(warning);
            }
            return Mono.empty();
        }));
    }

    private String handleJsonMessageChunk(String chunk,
                                          StringBuilder chatHistoryStringBuilder,
                                          Set<String> seenToolIds,
                                          boolean firstRound,
                                          AtomicBoolean hasToolCall,
                                          AtomicBoolean firstRoundToolViolationNotified) {
        StreamMessage streamMessage;
        try {
            streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
        } catch (Exception e) {
            log.warn("忽略无法解析的流式JSON chunk, chunk={}", truncate(chunk, 240));
            return "";
        }

        StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
        if (typeEnum != null) {
            switch (typeEnum) {
                case AI_RESPONSE -> {
                    AiResponseMessage aiMessage = JSONUtil.toBean(chunk, AiResponseMessage.class);
                    String data = aiMessage.getData();
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
                    if (toolId != null && !seenToolIds.contains(toolId)) {
                        seenToolIds.add(toolId);
                        BaseTool tool = toolManager.getTool(toolRequestMessage.getName());
                        if (tool != null) {
                            return tool.generateToolRequestResponse();
                        }
                        return String.format("[选择工具] %s", toolName);
                    }
                    return "";
                }
                case TOOL_EXECUTED -> {
                    ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(chunk, ToolExecutedMessage.class);
                    JSONObject jsonObject = safeParseArguments(toolExecutedMessage.getArguments());
                    BaseTool tool = toolManager.getTool(toolExecutedMessage.getName());
                    String result;
                    if (tool != null) {
                        result = tool.generateToolExecutedResult(jsonObject);
                    } else {
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
        String[] keywords = {"修改", "重构", "优化", "修复", "改一个", "edit", "refactor", "modify", "fix"};
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private JSONObject safeParseArguments(String arguments) {
        if (StrUtil.isBlank(arguments)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(arguments);
        } catch (Exception e) {
            JSONObject fallback = new JSONObject();
            fallback.set("_rawArguments", arguments);
            log.warn("工具参数非严格JSON，已降级为原始字符串。arguments={}", truncate(arguments, 240));
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

    private String fallbackToolExecutedFormatting(String toolName, JSONObject jsonObject) {
        String path = StrUtil.blankToDefault(
                jsonObject.getStr("relativeFilePath"),
                jsonObject.getStr("relativeDirPath"));
        return String.format("[滚木工具] %s %s",
                StrUtil.blankToDefault(toolName, "滚木"),
                StrUtil.blankToDefault(path, "-"));
    }
}
