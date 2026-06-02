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
import com.dbts.glyahhaigeneratecode.constant.ChatHistoryConstant;
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

/**
 * JSON 分片流处理器（Vue 等）：将模型输出的 JSON 行解析为 StreamMessage，
 * 再映射为前端可展示的纯文本/工具卡片，并在流结束时写入聊天记录。
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    @Resource
    private ToolManager toolManager;

    private final RetryOutputGuardrail retryOutputGuardrail = new RetryOutputGuardrail();

    /**
     * 将 JSON 行流转为前端文本流，并在完成/错误/取消时持久化 AI 消息；末尾可拼接编辑模式工具告警
     *
     * @param originFlux         上游每行一条 JSON 的流
     * @param chatHistoryService 会话服务
     * @param appId              应用 ID
     * @param loginUser          当前用户
     * @param firstRound         是否首轮（首轮工具白名单校验）
     * @param userMessage        原始用户输入（用于判断是否编辑意图）
     * @return 处理后的文本流（可能 concat 一条告警 Mono）
     */
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
        // 1. 根据用户措辞判断是否「编辑模式」意图（用于收尾 guardrail）
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
                    // 1. 流正常结束：把聚合文本落库为一条 AI 消息（仅一次）
                    if (persisted.compareAndSet(false, true)) {
                        String aiResponse = chatHistoryStringBuilder.toString();
                        chatHistoryService.addChatMessage(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    }
                })
                .doOnError(error -> {
                    // 1. 出错时写用户可见失败说明；真实异常仅打日志
                    if (persisted.compareAndSet(false, true)) {
                        log.warn("json message stream failed, appId={}, type={}", appId, error.getClass().getSimpleName(), error);
                        chatHistoryService.addChatMessage(
                                appId,
                                ChatHistoryConstant.GENERATION_FAILED_USER_MESSAGE,
                                ChatHistoryMessageTypeEnum.AI.getValue(),
                                loginUser.getId());
                    }
                })
                .doFinally(signal -> {
                    // 1. 订阅被取消且尚未持久化：把已有片段加 [中断] 落库
                    if (signal == SignalType.CANCEL && persisted.compareAndSet(false, true)) {
                        String partial = chatHistoryStringBuilder.toString();
                        if (StrUtil.isNotBlank(partial)) {
                            chatHistoryService.addChatMessage(
                                    appId,
                                    partial + "\n\n" + ChatHistoryConstant.GENERATION_INTERRUPTED_MARKER,
                                    ChatHistoryMessageTypeEnum.AI.getValue(),
                                    loginUser.getId()
                            );
                        }
                    }
                });

        // 2. 主流结束后，若编辑意图但本轮无工具调用，再拼接一条告警（Mono.defer 懒执行）
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
     * 解析单行 JSON chunk，按 type 分发：AI 文本透传、工具请求生成卡片、工具执行生成结果
     *
     * @param chunk                     单行 JSON
     * @param chatHistoryStringBuilder  会话聚合缓冲（与落库内容一致）
     * @param seenToolIds               已处理过的 tool id，用于去重
     * @param firstRound                 是否首轮
     * @param hasToolCall                是否发生过工具调用（原子布尔，供收尾告警）
     * @param firstRoundToolViolationNotified 首轮非法工具是否已提示
     * @return 映射后的前端文本；解析失败返回空串
     */
    private String handleJsonMessageChunk(String chunk,
                                          StringBuilder chatHistoryStringBuilder,
                                          Set<String> seenToolIds,
                                          boolean firstRound,
                                          AtomicBoolean hasToolCall,
                                          AtomicBoolean firstRoundToolViolationNotified) {
        StreamMessage streamMessage;
        // 1. 反序列化为 StreamMessage；失败则丢弃该行（避免整条 SSE 崩）
        try {
            streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
        } catch (Exception e) {
            log.warn("忽略无法解析的流式JSON chunk, chunk={}", truncate(chunk, 240));
            return "";
        }

        // 2. 取枚举类型，进入 switch 分发
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
                            String output = tool.generateToolRequestResponse();
                            // AI 消息持久化
                            chatHistoryStringBuilder.append(output);
                            return output;
                        }
                        String output = String.format("[选择工具] %s", toolName);
                        chatHistoryStringBuilder.append(output);
                        return output;
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
        // 3. 未知 type：抛业务异常（理论上不应到达）
        throw new MyException(ErrorCode.SYSTEM_ERROR, "不支持的消息类型");
    }

    /**
     * 根据中英文关键词判断用户是否在表达「编辑/修复」类意图
     *
     * @param userMessage 用户原始输入
     * @return true 表示命中编辑类关键词
     */
    private boolean isEditModeIntent(String userMessage) {
        // 1. 空串不算编辑意图
        if (StrUtil.isBlank(userMessage)) {
            return false;
        }
        String lower = userMessage.toLowerCase();
        String[] keywords = {"修改", "重构", "优化", "修复", "改一个", "edit", "refactor", "modify", "fix"};
        // 2. 线性扫描关键词表
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        // 3. 未命中则非编辑意图
        return false;
    }

    /**
     * 将工具参数字符串安全解析为 JSONObject；非严格 JSON 时降级为 _rawArguments
     *
     * @param arguments 工具 arguments 原始字符串
     * @return 解析后的对象，永不为 null
     */
    private JSONObject safeParseArguments(String arguments) {
        // 1. 空参返回空对象
        if (StrUtil.isBlank(arguments)) {
            return new JSONObject();
        }
        try {
            // 2. 严格 JSON 解析
            return JSONUtil.parseObj(arguments);
        } catch (Exception e) {
            // 3. 解析失败：保留原文，避免工具链完全断掉
            JSONObject fallback = new JSONObject();
            fallback.set("_rawArguments", arguments);
            log.warn("工具参数非严格JSON，已降级为原始字符串。arguments={}", truncate(arguments, 240));
            return fallback;
        }
    }

    /**
     * 日志/告警用字符串截断，避免打印超大 chunk
     *
     * @param text   原文
     * @param maxLen 最大字符数
     * @return 截断后的文本
     */
    private String truncate(String text, int maxLen) {
        // 1. null 当空串
        if (text == null) {
            return "";
        }
        // 2. 未超长原样返回
        if (text.length() <= maxLen) {
            return text;
        }
        // 3. 超长截断并加省略号
        return text.substring(0, maxLen) + "...";
    }

    /**
     * 当找不到对应 BaseTool 时的兜底展示（路径优先 relativeFilePath）
     *
     * @param toolName   工具名
     * @param jsonObject 已解析参数
     * @return 一行可读文本
     */
    private String fallbackToolExecutedFormatting(String toolName, JSONObject jsonObject) {
        // 1. 尝试从参数里取路径字段
        String path = StrUtil.blankToDefault(
                jsonObject.getStr("relativeFilePath"),
                jsonObject.getStr("relativeDirPath"));
        // 2. 拼固定格式占位输出
        return String.format("[滚木工具] %s %s",
                StrUtil.blankToDefault(toolName, "滚木"),
                StrUtil.blankToDefault(path, "-"));
    }
}
