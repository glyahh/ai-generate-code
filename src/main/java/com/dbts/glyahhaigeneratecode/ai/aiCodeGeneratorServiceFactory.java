package com.dbts.glyahhaigeneratecode.ai;

import com.dbts.glyahhaigeneratecode.ai.tool.ToolManager;
import com.dbts.glyahhaigeneratecode.config.OpenAiOutputGuardrailsConfig;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.guardrail.PromptSafetyInputGuardrail;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
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

//    @Resource
//    private OpenAiStreamingChatModel openAiStreamingChatModel;
//
//    @Resource
//    private StreamingChatModel reasoningChatModel;

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
     * 获取ai代码生成器服务
     * @param appId
     * @return
     */
    public aiCodeGeneratorService getAiCodeGeneratorService(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        return getAiCodeGeneratorService(appId, codeGenTypeEnum, false, true);
    }

    /**
     * 获取 ai 代码生成器服务（支持首轮工具白名单控制）
     */
    public aiCodeGeneratorService getAiCodeGeneratorService(Long appId, CodeGenTypeEnum codeGenTypeEnum, boolean firstRound) {
        return getAiCodeGeneratorService(appId, codeGenTypeEnum, firstRound, true);
    }

    /**
     * 获取 ai 代码生成器服务（支持首轮工具白名单控制 + 可选缓存命中压缩）
     */
    public aiCodeGeneratorService getAiCodeGeneratorService(Long appId,
                                                            CodeGenTypeEnum codeGenTypeEnum,
                                                            boolean firstRound,
                                                            boolean compactMemoryOnCacheHit) {
        // 首轮且 VUE：只暴露 writeFile，避免把“受限工具集”实例缓存到后续轮次, 并且直接创建Service
        if (firstRound && codeGenTypeEnum == CodeGenTypeEnum.VUE) {
            log.info("首轮 VUE 对话，创建 writeFile-only AI 服务，appId={}", appId);
            return createAiCodeGeneratorServiceForEachApp(appId, codeGenTypeEnum, true);
        }
        // 从缓存中获取 aiCodeGeneratorService
        String cacheKey = buildServiceCacheKey(appId, codeGenTypeEnum);
        aiCodeGeneratorService service = serviceCache.getIfPresent(cacheKey);
        if (service != null) {
            // Redis 可能已过期（TTL）而 Caffeine 未过期，导致内存里拿到的 service 其 ChatMemory 从 Redis 读不到任何消息
            // 此时需失效缓存并重新创建，以便 turnHistoryToMemory 从 MySQL 重新加载历史到 Redis
            if (appId > 0) {
                if (isRedisMemoryEmpty(appId)) {
                    log.warn("Redis 对话记忆已空（可能过期），将重建 AI 服务并从 MySQL 重新加载历史，appId={}", appId);
                    // 失效缓存并重新创建,废除这个键
                    serviceCache.invalidate(cacheKey);
                    service = null;
                }
            }
            if (service != null) {
                if (compactMemoryOnCacheHit) {
                    // 缓存命中时也执行一次在线压缩，避免旧的超长历史 AI 消息持续放大请求 token
                    chatHistoryService.compactMemoryMessagesIfNeeded(appId, codeGenTypeEnum, "cache_hit");
                }
                log.info("从缓存中获取 AI 服务实例，appId: {}", appId);
                return service;
            }
        }

        // 否则，创建一个新的 aiCodeGeneratorService
        service = createAiCodeGeneratorServiceForEachApp(appId, codeGenTypeEnum, false);
        log.info("创建一个新的 AI 服务实例，appId: {}, service: {}", appId, service);
        serviceCache.put(cacheKey, service);

        return service;
    }


    /**
     * 根据 appId 为每一个应用创建一个 aiCodeGeneratorService
     * @param appId
     * @return
     */
    public aiCodeGeneratorService createAiCodeGeneratorServiceForEachApp(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        return createAiCodeGeneratorServiceForEachApp(appId, codeGenTypeEnum, false);
    }

    /**
     * 根据 appId 为每一个应用创建一个 aiCodeGeneratorService（可选首轮工具白名单）
     */
    public aiCodeGeneratorService createAiCodeGeneratorServiceForEachApp(Long appId, CodeGenTypeEnum codeGenTypeEnum, boolean firstRound) {

        //根据 appId 为每一个应用创建一个 aiCodeGeneratorService
        MessageWindowChatMemory build = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(chatMemoryStore)
                .maxMessages(20)
                .build();

        // 预加载历史对话到内存（抛开用户刚发送的那条）
        // Mysql -> Redis
        // 注意：appId=0 的默认 Bean 仅用于兼容旧代码，不做历史加载
        if (appId != null && appId > 0) {
            try {
                //
                int loadedCount = chatHistoryService.turnHistoryToMemory(appId, build, 20);
                log.info("为应用预加载历史对话到内存，appId={}, loadedCount={}", appId, loadedCount);
            } catch (Exception e) {
                log.error("预加载历史对话到内存失败，appId={}", appId, e);
                throw new MyException(114514, "预加载历史对话到内存失败");
            }
        }

        // 根据用户创作的不同类型的代码生成不同的Service
        return switch (codeGenTypeEnum) {
            case VUE -> {
                StreamingChatModel prototypeReasoningChatModel = applicationContext.getBean("prototypeReasoningChatModel", StreamingChatModel.class);

                yield AiServices.builder(aiCodeGeneratorService.class)
                    .chatModel(chatModel)
                    .streamingChatModel(prototypeReasoningChatModel)
                    // 将memoryId一同作为ai需要记忆的内容
                    // 将本来的ai默认记忆的Id转化为build类型的 以appId作为唯一标识的chatMemory
                    .chatMemoryProvider(memoryId -> build)
                    .tools(
                            firstRound ? toolManager.getWriteFileOnlyTools() : toolManager.getAllTools()
                    )
                    .maxSequentialToolsInvocations(20)
                    // 当ai调用了本来没有的tool时
                    .hallucinatedToolNameStrategy(hallucinatedToolNameStrategy ->
                            ToolExecutionResultMessage.from(hallucinatedToolNameStrategy, "There is no toolbar named: " + hallucinatedToolNameStrategy.name())
                    )
                    .inputGuardrails(new PromptSafetyInputGuardrail())
                    // .outputGuardrails(new RetryOutputGuardrail(outputGuardrailsConfig.getMaxRetries())) 为了能够流式输出,先注释
                    .build();
            }
            case HTML, MULTI_FILE -> {
                // 使用多例模式的 streamingModel 解决并发问题
                StreamingChatModel prototypeStreamingChatModel = applicationContext.getBean("prototypeStreamingChatModel", StreamingChatModel.class);
                yield AiServices.builder(aiCodeGeneratorService.class)
                    .chatModel(chatModel)
                    .streamingChatModel(prototypeStreamingChatModel)
                    .chatMemory(build)
                    .inputGuardrails(new PromptSafetyInputGuardrail())
                    // .outputGuardrails(new RetryOutputGuardrail(outputGuardrailsConfig.getMaxRetries())) 为了能够流式输出,先注释
                    .build();
            }
            default -> throw new MyException(114514, "不支持的代码生成类型");
        };
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
     * 创建ai代码生成器服务
     * pass 掉的方法, 为了维护一致性遂取0
     * @return
     */
    @Bean
    public aiCodeGeneratorService createAiCodeGeneratorService() {
        return getAiCodeGeneratorService(0L);
    }

    /**
     * 获取ai代码生成器服务
     * @param appId
     * @return
     */
    public aiCodeGeneratorService getAiCodeGeneratorService(Long appId) {
        // 从缓存中获取 aiCodeGeneratorService
        String cacheKey = buildServiceCacheKey(appId, CodeGenTypeEnum.MULTI_FILE);
        aiCodeGeneratorService service = serviceCache.getIfPresent(cacheKey);
        if (service != null) {
            // Redis 可能已过期（TTL）而 Caffeine 未过期，导致内存里拿到的 service 其 ChatMemory 从 Redis 读不到任何消息
            // 此时需失效缓存并重新创建，以便 turnHistoryToMemory 从 MySQL 重新加载历史到 Redis
            if (appId > 0) {
                if (isRedisMemoryEmpty(appId)) {
                    log.warn("Redis 对话记忆已空（可能过期），将重建 AI 服务并从 MySQL 重新加载历史，appId={}", appId);
                    // 失效缓存并重新创建
                    serviceCache.invalidate(cacheKey);
                    service = null;
                }
            }
            if (service != null) {
                log.info("从缓存中获取 AI 服务实例，appId: {}", appId);
                return service;
            }
        }

        // 否则，创建一个新的 aiCodeGeneratorService
        service = createAiCodeGeneratorServiceForEachApp(appId, CodeGenTypeEnum.MULTI_FILE, false);
        log.info("创建一个新的 AI 服务实例，appId: {}, service: {}", appId, service);
        serviceCache.put(cacheKey, service);

        return service;
    }

    /**
     * 构建 AI 服务缓存 key，统一使用枚举 value（如 vue / multi_file），避免依赖 toString 导致类型拼接不一致
     */
    private String buildServiceCacheKey(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        return appId + "_" + codeGenTypeEnum.getValue();
    }

}
