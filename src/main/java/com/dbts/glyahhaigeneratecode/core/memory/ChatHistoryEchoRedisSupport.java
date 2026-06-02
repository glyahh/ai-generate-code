package com.dbts.glyahhaigeneratecode.core.memory;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.config.ConversationMemoryProperties;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 用户回显全文 Redis 缓存（chat:echo_memory:{appId}），与 AI ChatMemory 分离。
 * <p>
 * chat_history 全文灌入 Redis -> 历史分页/导出优先读缓存 -> 新消息或 summary 后 invalidate 回源 MySQL
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatHistoryEchoRedisSupport {

    /** echo 轨 Redis key 前缀，完整 key 为 chat:echo_memory:{appId} */
    public static final String ECHO_MEMORY_KEY_PREFIX = "chat:echo_memory:";

    private static final TypeReference<List<ChatHistory>> CHAT_HISTORY_LIST_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationMemoryProperties properties;

    /**
     * 构造 echo_memory 完整 Redis key
     *
     * @param appId 应用 id
     * @return chat:echo_memory:{appId}
     */
    public String buildKey(Long appId) {
        return ECHO_MEMORY_KEY_PREFIX + appId;
    }

    /**
     * 从 Redis 读取该应用全文历史；命中时滑动续期 TTL
     *
     * @param appId 应用 id
     * @return 全文列表；miss 或异常时返回 null
     */
    public List<ChatHistory> getCachedFullHistory(Long appId) {
        if (appId == null || appId <= 0) {
            return null;
        }
        try {
            // 1. 按 key 读取 JSON 全文列表
            String json = stringRedisTemplate.opsForValue().get(buildKey(appId));
            if (StrUtil.isBlank(json)) {
                return null;
            }
            List<ChatHistory> list = objectMapper.readValue(json, CHAT_HISTORY_LIST_TYPE);
            // 2. 命中后 EXPIRE 重置，得到滑动过期的 echo 缓存
            refreshTtl(appId);
            log.debug("echo_memory 命中，appId={}, size={}", appId, list != null ? list.size() : 0);
            return list;
        } catch (Exception e) {
            log.warn("读取 echo_memory 失败，appId={}", appId, e);
            return null;
        }
    }

    /**
     * 将 chat_history 全文写入 echo_memory 并设置 TTL
     *
     * @param appId     应用 id
     * @param histories 全文行列表（来自 MySQL）
     */
    public void putFullHistory(Long appId, List<ChatHistory> histories) {
        if (appId == null || appId <= 0 || histories == null) {
            return;
        }
        try {
            // 1. 按 createTime、id 升序排序，保证与 DB 时间轴一致
            List<ChatHistory> sorted = histories.stream()
                    .sorted(Comparator.comparing(ChatHistory::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(ChatHistory::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            String json = objectMapper.writeValueAsString(sorted);
            // 2. SET key + echoMemoryTtlSeconds，供后续分页在内存中切片
            stringRedisTemplate.opsForValue().set(
                    buildKey(appId),
                    json,
                    properties.getEchoMemoryTtlSeconds(),
                    TimeUnit.SECONDS);
            log.debug("echo_memory 已写入，appId={}, size={}", appId, sorted.size());
        } catch (Exception e) {
            log.warn("写入 echo_memory 失败，appId={}", appId, e);
        }
    }

    /**
     * 删除 echo 缓存，迫使下次历史接口从 MySQL 重建
     *
     * @param appId 应用 id
     */
    public void invalidate(Long appId) {
        if (appId == null || appId <= 0) {
            return;
        }
        try {
            stringRedisTemplate.delete(buildKey(appId));
            log.debug("echo_memory 已失效，appId={}", appId);
        } catch (Exception e) {
            log.warn("删除 echo_memory 失败，appId={}", appId, e);
        }
    }

    /**
     * 对已有 echo key 执行 EXPIRE，滑动刷新 TTL
     *
     * @param appId 应用 id
     */
    public void refreshTtl(Long appId) {
        if (appId == null || appId <= 0) {
            return;
        }
        try {
            stringRedisTemplate.expire(buildKey(appId), properties.getEchoMemoryTtlSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("刷新 echo_memory TTL 失败，appId={}", appId, e);
        }
    }

    /**
     * 将 null 列表转为空列表，避免 NPE
     *
     * @param list 可能为 null 的列表
     * @return 非 null 列表
     */
    public List<ChatHistory> emptyIfNull(List<ChatHistory> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
