package com.dbts.glyahhaigeneratecode.core.memory;

import com.dbts.glyahhaigeneratecode.config.ConversationMemoryProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI ChatMemory Redis TTL 刷新（通过回写消息触发 LangChain4j Store 续期）。
 * <p>
 * 读取 LangChain4j Redis 现有 messages -> updateMessages 原样回写 -> Store 侧重设 chatTtlSeconds 过期时间
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatAiMemoryRedisSupport {

    private final ChatMemoryStore chatMemoryStore;
    private final ConversationMemoryProperties properties;

    /**
     * 刷新 AI 轨 ChatMemory（memoryId=appId）的 Redis TTL，不改动消息内容
     *
     * @param appId 应用 id
     */
    public void refreshAiMemoryTtl(Long appId) {
        if (appId == null || appId <= 0) {
            return;
        }
        try {
            // 1. 读取当前 Redis 中的压缩上下文
            List<ChatMessage> messages = chatMemoryStore.getMessages(appId);
            if (messages == null || messages.isEmpty()) {
                return;
            }
            // 2. 原样 updateMessages，触发 LangChain4j Store 续期（TTL=chatTtlSeconds）
            chatMemoryStore.updateMessages(appId, messages);
            log.debug("AI ChatMemory TTL 已刷新，appId={}, ttlSeconds={}", appId, properties.getChatTtlSeconds());
        } catch (Exception e) {
            log.warn("刷新 AI ChatMemory TTL 失败，appId={}", appId, e);
        }
    }
}
