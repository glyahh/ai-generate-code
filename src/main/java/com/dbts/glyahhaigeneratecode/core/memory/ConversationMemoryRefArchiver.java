package com.dbts.glyahhaigeneratecode.core.memory;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.config.ConversationMemoryProperties;
import com.dbts.glyahhaigeneratecode.mapper.ConversationMemoryRefMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 会话记忆 ref 归档。
 * <p>将本轮超长 changed file 全文落到 MySQL conversation_memory_ref（真相源）
 * 并同步写入 Redis cm:ref:{refId}（热缓存，TTL 失效后从 DB 回源）。</p>
 * <p>Spring bean：注入 refMapper / redis / properties。</p>
 */
@Component
@RequiredArgsConstructor
public class ConversationMemoryRefArchiver {

    /** 触发 ref 归档的最小文件字节数。 */
    public static final int ARCHIVE_MIN_BYTES = 8000;

    /** 哈希采样前缀长度（用于生成稳定 refId）。 */
    public static final int HASH_SAMPLE_PREFIX_LENGTH = 2000;

    private final ConversationMemoryRefMapper conversationMemoryRefMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ConversationMemoryProperties properties;

    /**
     * 归档超长 changed file 到 ref。
     *
     * @param appId        应用 id
     * @param roundId      轮次 id
     * @param root         项目目录
     * @param changedFiles changedFiles
     * @return 归档数量
     */
    public int archiveLargeChangedFilesIfNeeded(Long appId, Long roundId, Path root, List<String> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty() || root == null || !Files.isDirectory(root)) {
            return 0;
        }
        int archived = 0;
        for (String relative : changedFiles) {
            try {
                Path path = root.resolve(relative).normalize();
                if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                    continue;
                }
                String content = Files.readString(path, StandardCharsets.UTF_8);
                if (content.length() < ARCHIVE_MIN_BYTES) {
                    continue;
                }
                String refId = "ref-" + appId + "-" + roundId + "-"
                        + ConversationMemoryManifestSupport.sha256Hex(
                                relative + ":" + content.substring(0, Math.min(HASH_SAMPLE_PREFIX_LENGTH, content.length())));
                long bytes = content.getBytes(StandardCharsets.UTF_8).length;
                // 写入 MySQL conversation_memory_ref：持久化本轮变更中的大文件全文（如 package-lock.json）。
                // relative -> filePath，content -> content；真相源在 DB，Redis 仅为热缓存。
                conversationMemoryRefMapper.insertIgnore(appId, roundId, refId, relative, content, bytes);

                // TODO (memory-v4): 实现按 refId/filePath 从 cm:ref 或 conversation_memory_ref 读回并注入 ChatMemory；
                // 当前仅归档，模型上下文不消费 ref。

                // 写入 Redis cm:ref:{refId}：与上表 content 同文，TTL 见 conversation.memory.ref-ttl-seconds，过期后从 DB 回源。
                cacheRefToRedis(refId, content);
                archived++;
            } catch (Exception ignore) {
                // 单文件归档失败不影响主流程
            }
        }
        return archived;
    }

    private void cacheRefToRedis(String refId, String content) {
        if (StrUtil.isBlank(refId) || content == null) {
            return;
        }
        // Redis 热缓存：键 cm:ref:{refId}，值为归档文件全文（与 conversation_memory_ref.content 一致）。
        String key = "cm:ref:" + refId;
        stringRedisTemplate.opsForValue().set(key, content, properties.getRefTtlSeconds(), TimeUnit.SECONDS);
    }
}
