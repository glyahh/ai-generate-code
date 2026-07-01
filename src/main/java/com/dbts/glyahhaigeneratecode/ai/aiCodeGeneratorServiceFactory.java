package com.dbts.glyahhaigeneratecode.ai;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.ai.tool.ToolManager;
import com.dbts.glyahhaigeneratecode.config.OpenAiOutputGuardrailsConfig;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.guardrail.PromptSafetyInputGuardrail;
import com.dbts.glyahhaigeneratecode.constant.ChatHistoryConstant;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

/**
 * ai服务创建工厂
 */
@Configuration
@Slf4j
public class aiCodeGeneratorServiceFactory {

    // 这里的 AI 模型不是用于rounting的,所以注入openai的Bean
    @Resource(name = "openAiChatModel")
    private ChatModel chatModel;

    @Resource
    private ChatMemoryStore chatMemoryStore;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private ToolManager toolManager;

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private OpenAiOutputGuardrailsConfig outputGuardrailsConfig;

    /**
     * AI 服务实例缓存
     * 缓存策略：
     * - 最大缓存 1000 个实例
     * - 写入后 30 分钟过期
     * - 访问后 10 分钟过期
     */
    private final Cache<String, aiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .expireAfterAccess(Duration.ofMinutes(10))
            .removalListener((key, value, cause) -> {
                log.info("AI 服务实例被移除，appId: {}, 原因: {}", key, cause);
            })
            .build();

    /**
     * 已创建的 MessageWindowChatMemory 实例映射（appId → memory）。
     * 用于会话级风格注入时能直接操作 MessageWindowChatMemory 的内部列表，
     * 避免缓存的服务实例中内存列表与 ChatMemoryStore 不同步。
     */
    private final ConcurrentHashMap<Long, MessageWindowChatMemory> appMemoryMap = new ConcurrentHashMap<>();

    /** 每 app 最近一次注入的 style body；无变化时跳过清空重建 */
    private final ConcurrentHashMap<Long, String> appLastSessionStyle = new ConcurrentHashMap<>();

    /** Cached service -> memory version observed when binding its MessageWindowChatMemory. */
    private final ConcurrentHashMap<String, String> serviceBoundMemoryVersion = new ConcurrentHashMap<>();


    /**
     * 获取 ai 代码生成器服务（支持首轮工具白名单控制）
     * <p>缓存命中时仅刷新 Redis TTL 并在窗口空时失效重建，不做在线压缩。</p>
     */
    public aiCodeGeneratorService getAiCodeGeneratorService(Long appId, CodeGenTypeEnum codeGenTypeEnum, boolean firstRound) {
        //
        if (firstRound) {
            aiCodeGeneratorService ephemeral = buildEphemeralBootstrapService(appId, codeGenTypeEnum);
            if (ephemeral != null) {
                return ephemeral;
            }
        }

        String cacheKey = buildServiceCacheKey(appId, codeGenTypeEnum);
        aiCodeGeneratorService service = serviceCache.getIfPresent(cacheKey);

        if (service != null) {
            // Redis 可能已过期（TTL）而 Caffeine 未过期，导致内存里拿到的 service 其 ChatMemory 从 Redis 读不到任何消息
            // 此时需失效缓存并重新创建，由 build 链路从 MySQL 重新加载历史到 Redis
            if (appId != null && appId > 0) {
                if (isRedisMemoryEmpty(appId)) {
                    log.warn("Redis 对话记忆已空（可能过期），将重建 AI 服务并从 MySQL 重新加载历史，appId={}", appId);
                    serviceCache.invalidate(cacheKey);
                    serviceBoundMemoryVersion.remove(cacheKey);
                    service = null;
                }
            }

            if (service != null) {
                String currentVersion = readAiVisibleMemoryVersion(appId);
                String boundVersion = serviceBoundMemoryVersion.get(cacheKey);
                if (!Objects.equals(currentVersion, boundVersion)) {
                    log.info("AI service memory version changed, rebuild cached service. appId={}, boundVersion={}, currentVersion={}",
                            appId, boundVersion, currentVersion);
                    serviceCache.invalidate(cacheKey);
                    serviceBoundMemoryVersion.remove(cacheKey);
                    service = null;
                    // 这里先比对 version，命中才复用，避免 silent stale；不一致就落到下方 build 链路重建。
                } else {
                    chatHistoryService.refreshAiChatMemoryTtl(appId);
                    log.info("从缓存中获取 AI 服务实例，appId: {}", appId);
                    return service;
                }
            }
        }

        service = buildCachedFullToolService(appId, codeGenTypeEnum);
        log.info("创建一个新的 AI 服务实例（full-tool），appId: {}, service: {}", appId, service);
        putCachedService(cacheKey, service);
        bindMemoryVersion(cacheKey, appId);

        return service;
    }

    /**
     * 首轮临时 service：writeFile-only（VUE）或无工具（HTML/MULTI），禁止写入 Caffeine。
     */
    private aiCodeGeneratorService buildEphemeralBootstrapService(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        MessageWindowChatMemory memory = prepareChatMemory(appId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case VUE -> {
                log.info("首轮 VUE 对话，创建 writeFile-only AI 服务，appId={}", appId);
                yield buildVueService(memory, toolManager.getWriteFileOnlyTools());
            }
            case HTML, MULTI_FILE -> {
                log.info("首轮 HTML/MULTI_FILE 纯流式（不绑定文件工具），appId={}", appId);
                yield buildHtmlMultiService(memory, null);
            }
            default -> null;
        };
    }

    /**
     * 后续轮次 service：VUE 全工具集 / HTML/MULTI 编辑工具集，可写入 Caffeine。
     */
    private aiCodeGeneratorService buildCachedFullToolService(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        MessageWindowChatMemory memory = prepareChatMemory(appId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case VUE -> buildVueService(memory, toolManager.getAllTools());
            case HTML, MULTI_FILE -> buildHtmlMultiService(memory, toolManager.getHtmlMultiEditTools());
            default -> throw new MyException(114514, "不支持的代码生成类型");
        };
    }

    /**
     * 预加载 ChatMemory 并注册到 appMemoryMap（Mysql → Redis）。
     */
    private MessageWindowChatMemory prepareChatMemory(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        MessageWindowChatMemory messageWindowChatMemory = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(chatMemoryStore)
                .maxMessages(ChatHistoryConstant.CHAT_MEMORY_MAX_MESSAGES)
                .build();
        if (appId != null && appId > 0) {
            appMemoryMap.put(appId, messageWindowChatMemory);
        }

        if (appId != null && appId > 0) {
            try {
                int loadedCount = chatHistoryService.loadConversationMemoryStateAndInject(
                        appId, messageWindowChatMemory, ChatHistoryConstant.MEMORY_PRELOAD_MESSAGE_ROWS, codeGenTypeEnum);
                String styleBody = appLastSessionStyle.get(appId);
                if (styleBody != null) {
                    applySessionStyleToMemory(appId, messageWindowChatMemory, styleBody);
                }
                log.info("为应用预加载历史对话到内存，appId={}, loadedCount={}", appId, loadedCount);
            } catch (Exception e) {
                log.error("预加载历史对话到内存失败，appId={}", appId, e);
                throw new MyException(114514, "预加载历史对话到内存失败");
            }
        }
        return messageWindowChatMemory;
    }

    private aiCodeGeneratorService buildVueService(MessageWindowChatMemory memory, BaseTool[] tools) {
        StreamingChatModel prototypeReasoningChatModel =
                applicationContext.getBean("prototypeReasoningChatModel", StreamingChatModel.class);

        return AiServices.builder(aiCodeGeneratorService.class)
                .chatModel(chatModel)
                .streamingChatModel(prototypeReasoningChatModel)
                .chatMemoryProvider(memoryId -> memory)
                .tools((Object[]) tools)
                .maxSequentialToolsInvocations(20)
                .hallucinatedToolNameStrategy(hallucinatedToolNameStrategy ->
                        ToolExecutionResultMessage.from(
                                hallucinatedToolNameStrategy,
                                "There is no toolbar named: " + hallucinatedToolNameStrategy.name())
                )
                .inputGuardrails(new PromptSafetyInputGuardrail())
                .build();
    }

    private aiCodeGeneratorService buildHtmlMultiService(
            MessageWindowChatMemory memory, BaseTool[] tools) {

        StreamingChatModel prototypeStreamingChatModel =
                applicationContext.getBean("prototypeStreamingChatModel", StreamingChatModel.class);

        var builder = AiServices.builder(aiCodeGeneratorService.class)
                .chatModel(chatModel)
                .streamingChatModel(prototypeStreamingChatModel)
                .chatMemoryProvider(memoryId -> memory)
                .maxSequentialToolsInvocations(20)
                .hallucinatedToolNameStrategy(hallucinatedToolNameStrategy ->
                        ToolExecutionResultMessage.from(
                                hallucinatedToolNameStrategy,
                                "There is no toolbar named: " + hallucinatedToolNameStrategy.name())
                )
                .inputGuardrails(new PromptSafetyInputGuardrail());

        if (tools != null && tools.length > 0) {
            builder.tools((Object[]) tools);
        }
        return builder.build();
    }

    /**
     * 仅 full-tool profile 的 service 允许进入 Caffeine；ephemeral 首轮实例禁止调用此方法。
     */
    private void putCachedService(String cacheKey, aiCodeGeneratorService service) {
        serviceCache.put(cacheKey, service);
    }

    private void bindMemoryVersion(String cacheKey, Long appId) {
        if (appId == null || appId <= 0) {
            return;
        }
        serviceBoundMemoryVersion.put(cacheKey, readAiVisibleMemoryVersion(appId));
    }

    private String readAiVisibleMemoryVersion(Long appId) {
        try {
            return chatHistoryService.getAiVisibleMemoryVersion(appId);
        } catch (Exception e) {
            log.warn("读取 AI 记忆 version 失败，视为需要重建，appId={}", appId, e);
            return "unavailable:" + System.nanoTime();
        }
    }

    /**
     * 判断 Redis 中该 appId 的对话记忆是否为空（如 key 已过期被删除）
     */
    private boolean isRedisMemoryEmpty(Long appId) {
        try {
            List<ChatMessage> messages = chatMemoryStore.getMessages(appId);
            return messages == null || messages.isEmpty();
        } catch (Exception e) {
            log.warn("检查 Redis 对话记忆时异常，视为需重建，appId={}", appId, e);
            return true;
        }
    }

    /**
     * 将会话级风格 SystemMessage（或 null 表示移除）应用到已缓存的 MessageWindowChatMemory。
     */
    public void applySessionStyle(Long appId, String styleBody) {
        if (appId == null || appId <= 0) return;

        String lastStyle = appLastSessionStyle.get(appId);
        if (Objects.equals(lastStyle, styleBody)) return;
        appLastSessionStyle.put(appId, styleBody);

        MessageWindowChatMemory memory = appMemoryMap.get(appId);
        if (memory == null) {
            log.debug("memory 实例尚未创建，跳过会话级风格注入，appId={}", appId);
            return;
        }

        applySessionStyleToMemory(appId, memory, styleBody);
    }

    private void applySessionStyleToMemory(Long appId, MessageWindowChatMemory memory, String styleBody) {
        List<ChatMessage> currentMsgs;
        try {
            currentMsgs = chatMemoryStore.getMessages(appId);
        } catch (Exception e) {
            log.warn("读取 ChatMemoryStore 中的 style 信息失败，跳过风格注入，appId={}", appId, e);
            return;
        }
        if (currentMsgs == null) currentMsgs = new ArrayList<>();
        else currentMsgs = new ArrayList<>(currentMsgs);

        if (styleBody == null) {
            currentMsgs.removeIf(
                    msg -> msg instanceof SystemMessage sm
                            && sm.text() != null
                            && sm.text().startsWith("<inject_prompt>")
            );
        } else {
            boolean replaced = false;
            for (int i = 0; i < currentMsgs.size(); i++) {
                if (currentMsgs.get(i) instanceof SystemMessage sm
                        && sm.text() != null
                        && sm.text().startsWith("<inject_prompt>")) {
                    currentMsgs.set(i, SystemMessage.from(styleBody));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                int insertPos = currentMsgs.size();
                for (int i = 0; i < currentMsgs.size(); i++) {
                    if (!(currentMsgs.get(i) instanceof SystemMessage)) {
                        insertPos = i;
                        break;
                    }
                }
                currentMsgs.add(insertPos, SystemMessage.from(styleBody));
            }
        }

        memory.clear();
        for (ChatMessage msg : currentMsgs) {
            memory.add(msg);
        }
        log.debug("会话级风格已应用，appId={}, hasStyle={}", appId, styleBody != null);
    }

    /**
     * 确保用户消息已存在
     */
    public void ensureUserMessagePresent(Long appId, String userMessageText) {
        if (appId == null || appId <= 0 || StrUtil.isBlank(userMessageText)) {
            return;
        }
        try {
            List<ChatMessage> msgs = chatMemoryStore.getMessages(appId);
            boolean hasFirstUserMessage = false;
            if (msgs != null) {
                for (ChatMessage msg : msgs) {
                    if (msg instanceof UserMessage) {
                        hasFirstUserMessage = true;
                        break;
                    }
                    if (!(msg instanceof ToolExecutionResultMessage)) {
                        break;
                    }
                }
            }
            if (!hasFirstUserMessage) {
                log.warn("Chat memory 无 UserMessage，注入兜底 user 提示。appId={}", appId);
                List<ChatMessage> next = msgs == null ? new ArrayList<>() : new ArrayList<>(msgs);
                next.add(UserMessage.from(userMessageText));
                chatMemoryStore.updateMessages(appId, next);
                chatHistoryService.markAiVisibleMemoryChanged(appId, "ensure_user_message");
            }
        } catch (Exception e) {
            log.error("ensureUserMessagePresent 失败，appId={}", appId, e);
        }
    }

    /**
     * 构建 AI 服务缓存 key，统一使用枚举 value（如 vue / multi_file），避免依赖 toString 导致类型拼接不一致
     */
    String buildServiceCacheKey(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        return appId + "_" + codeGenTypeEnum.getValue();
    }

    /**
     * 包内测试用：读取 Caffeine 缓存中是否已有 service。
     */
    aiCodeGeneratorService getCachedServiceForTest(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        return serviceCache.getIfPresent(buildServiceCacheKey(appId, codeGenTypeEnum));
    }

}
