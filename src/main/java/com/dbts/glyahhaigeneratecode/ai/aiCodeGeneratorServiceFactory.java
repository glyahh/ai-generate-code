package com.dbts.glyahhaigeneratecode.ai;

import com.dbts.glyahhaigeneratecode.ai.tool.FileWriteTool;
import com.dbts.glyahhaigeneratecode.config.ReasoningChatModelConfig;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.apache.bcel.classfile.Code;
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

    @Resource
    private ChatModel chatModel;

    @Resource
    private OpenAiStreamingChatModel openAiStreamingChatModel;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private StreamingChatModel reasoningChatModel;

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
        // 从缓存中获取 aiCodeGeneratorService
        aiCodeGeneratorService service = serviceCache.getIfPresent(appId.toString() + "_" + codeGenTypeEnum);
        if (service != null) {
            // Redis 可能已过期（TTL）而 Caffeine 未过期，导致内存里拿到的 service 其 ChatMemory 从 Redis 读不到任何消息
            // 此时需失效缓存并重新创建，以便 turnHistoryToMemory 从 MySQL 重新加载历史到 Redis
            if (appId > 0) {
                if (isRedisMemoryEmpty(appId)) {
                    log.warn("Redis 对话记忆已空（可能过期），将重建 AI 服务并从 MySQL 重新加载历史，appId={}", appId);
                    // 失效缓存并重新创建
                    serviceCache.invalidate(appId.toString() + "_" + codeGenTypeEnum);
                    service = null;
                }
            }
            if (service != null) {
                log.info("从缓存中获取 AI 服务实例，appId: {}", appId);
                return service;
            }
        }

        // 否则，创建一个新的 aiCodeGeneratorService
        service = createAiCodeGeneratorServiceForEachApp(appId, codeGenTypeEnum);
        log.info("创建一个新的 AI 服务实例，appId: {}, service: {}", appId, service);
        serviceCache.put(appId.toString() + "_" + codeGenTypeEnum, service);

        return service;
    }


    /**
     * 根据 appId 为每一个应用创建一个 aiCodeGeneratorService
     * @param appId
     * @return
     */
    public aiCodeGeneratorService createAiCodeGeneratorServiceForEachApp(Long appId, CodeGenTypeEnum codeGenTypeEnum) {

        //根据 appId 为每一个应用创建一个 aiCodeGeneratorService
        MessageWindowChatMemory build = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(25)
                .build();

        // 预加载历史对话到内存（抛开用户刚发送的那条）
        // 注意：appId=0 的默认 Bean 仅用于兼容旧代码，不做历史加载
        if (appId != null && appId > 0) {
            try {
                int loadedCount = chatHistoryService.turnHistoryToMemory(appId, build, 25);
                log.info("为应用预加载历史对话到内存，appId={}, loadedCount={}", appId, loadedCount);
            } catch (Exception e) {
                log.error("预加载历史对话到内存失败，appId={}", appId, e);
                throw new MyException(114514, "预加载历史对话到内存失败");
            }
        }

        // 根据用户创作的不同类型的代码生成不同的Service
        return switch (codeGenTypeEnum) {
            case VUE -> AiServices.builder(aiCodeGeneratorService.class)
                    .chatModel(chatModel)
                    .streamingChatModel(reasoningChatModel)
                    // 将memoryId一同作为ai需要记忆的内容
                    .chatMemoryProvider(memoryId -> build)
                    .tools(new FileWriteTool())
                    // 当ai调用了本来没有的tool时
                    .hallucinatedToolNameStrategy(hallucinatedToolNameStrategy ->
                            ToolExecutionResultMessage.from(hallucinatedToolNameStrategy, "There is no toolbar named: " + hallucinatedToolNameStrategy.name())
                    )
                    .build();
            case HTML, MULTI_FILE -> AiServices.builder(aiCodeGeneratorService.class)
                    .chatModel(chatModel)
                    .streamingChatModel(openAiStreamingChatModel)
                    .chatMemory(build)
                    .build();
            default -> throw new MyException(114514, "不支持的代码生成类型");
        };
    }


    /**
     * 判断 Redis 中该 appId 的对话记忆是否为空（如 key 已过期被删除）
     */
    private boolean isRedisMemoryEmpty(Long appId) {
        try {
            List<ChatMessage> messages = redisChatMemoryStore.getMessages(appId);
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
        aiCodeGeneratorService service = serviceCache.getIfPresent(appId.toString() + "_" + CodeGenTypeEnum.MULTI_FILE);
        if (service != null) {
            // Redis 可能已过期（TTL）而 Caffeine 未过期，导致内存里拿到的 service 其 ChatMemory 从 Redis 读不到任何消息
            // 此时需失效缓存并重新创建，以便 turnHistoryToMemory 从 MySQL 重新加载历史到 Redis
            if (appId > 0) {
                if (isRedisMemoryEmpty(appId)) {
                    log.warn("Redis 对话记忆已空（可能过期），将重建 AI 服务并从 MySQL 重新加载历史，appId={}", appId);
                    // 失效缓存并重新创建
                    serviceCache.invalidate(appId.toString() + "_" + CodeGenTypeEnum.MULTI_FILE);
                    service = null;
                }
            }
            if (service != null) {
                log.info("从缓存中获取 AI 服务实例，appId: {}", appId);
                return service;
            }
        }

        // 否则，创建一个新的 aiCodeGeneratorService
        service = createAiCodeGeneratorServiceForEachApp(appId, CodeGenTypeEnum.MULTI_FILE);
        log.info("创建一个新的 AI 服务实例，appId: {}, service: {}", appId, service);
        serviceCache.put(appId.toString() + "_" + CodeGenTypeEnum.MULTI_FILE, service);

        return service;
    }

}
