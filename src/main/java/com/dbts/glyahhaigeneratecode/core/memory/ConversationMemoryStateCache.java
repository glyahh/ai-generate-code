package com.dbts.glyahhaigeneratecode.core.memory;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.config.ConversationMemoryProperties;
import com.dbts.glyahhaigeneratecode.mapper.ConversationMemoryStateMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.ConversationMemoryState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 会话 memory_state 缓存。
 * <p>统一读写：Redis cm:state:{appId} 为热缓存，DB conversation_memory_state 为真相源；
 * 读路径 Redis miss 时回源 DB 并回填 Redis；写路径覆盖式 upsert。</p>
 * <p>Spring bean：注入 stateMapper / redis / objectMapper / properties。</p>
 */
@Component
@RequiredArgsConstructor
public class ConversationMemoryStateCache {

    /** Redis state key 前缀。 */
    public static final String STATE_KEY_PREFIX = "cm:state:";

    private static final String AI_MEMORY_VERSION_KEY_PREFIX = "cm:ai-memory-version:";

    private final ConversationMemoryStateMapper conversationMemoryStateMapper;
    private final StringRedisTemplate stringRedisTemplate;
    /**
     * -- GETTER --
     *  暴露内部 ObjectMapper，供同包 ManifestSupport 等只读 JSON 序列化场景复用，避免重复注入。
     */
    @Getter
    private final ObjectMapper objectMapper;
    private final ConversationMemoryProperties properties;

    /**
     * 读取 state（先 Redis 后 DB），miss 时回源 DB 并回填 Redis。
     *
     * @param appId 应用 id
     * @return 状态 map；DB 也为空时返回空 map
     */
    public Map<String, Object> loadFromRedisOrDb(Long appId) {
        String key = buildStateKey(appId);
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(cached)) {
                return objectMapper.readValue(cached, new TypeReference<>() {
                });
            }
        } catch (Exception ignore) {
            // Redis 失败回源 DB
        }
        Map<String, Object> dbState = loadFromDb(appId);
        if (!dbState.isEmpty()) {
            try {
                stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(dbState), properties.getStateTtlSeconds(), TimeUnit.SECONDS);
            } catch (Exception ignore) {
                // 回填失败忽略
            }
        }
        return dbState;
    }

    /**
     * 从 DB 读取 memory_state。
     *
     * @param appId 应用 id
     * @return 状态 map
     */
    public Map<String, Object> loadFromDb(Long appId) {
        try {
            QueryWrapper queryWrapper = new QueryWrapper();
            queryWrapper.eq(ConversationMemoryState::getAppId, appId);
            ConversationMemoryState row = conversationMemoryStateMapper.selectOneByQuery(queryWrapper);
            if (row == null) {
                return Collections.emptyMap();
            }
            Map<String, Object> map = new HashMap<>();
            map.put("latestRoundId", row.getLatestRoundId());
            map.put("latestSnapshotId", row.getLatestSnapshotId());
            map.put("softSummary", row.getSoftSummary());
            map.put("hardSummary", row.getHardSummary());
            map.put("changedFilesJson", row.getChangedFilesJson());
            map.put("fileNotesJson", row.getFileNotesJson());
            return map;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * 缓存 memory_state 到 Redis。
     *
     * @param appId 应用 id
     * @throws Exception JSON 序列化或 Redis 写入失败时抛出
     */
    public void cacheToRedis(Long appId) throws Exception {
        Map<String, Object> state = loadFromDb(appId);
        if (state.isEmpty()) {
            return;
        }
        String key = buildStateKey(appId);
        String value = objectMapper.writeValueAsString(state);
        stringRedisTemplate.opsForValue().set(key, value, properties.getStateTtlSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 更新 memory_state 数据库（覆盖式 upsert）。
     *
     * @param appId        应用 id
     * @param roundId      本轮 roundId
     * @param snapshotId   关联的 snapshotHistory.id（&lt;=0 时记 null）
     * @param summaryBundle 软/硬摘要
     * @param changedFiles changedFiles 列表
     * @param fileNotesJson fileNotesJson 字符串（nullable）
     */
    public void upsert(Long appId, Long roundId, long snapshotId,
                       ConversationMemorySummarySupport.SummaryBundle summaryBundle,
                       List<String> changedFiles, String fileNotesJson) throws Exception {
        String changedFilesJson = objectMapper.writeValueAsString(changedFiles == null ? Collections.emptyList() : changedFiles);
        conversationMemoryStateMapper.upsertByAppId(
                appId,
                roundId,    
                snapshotId <= 0 ? null : snapshotId,
                summaryBundle.softSummary(),
                summaryBundle.hardSummary(),
                changedFilesJson,
                fileNotesJson
        );
    }

    /**
     * 解析 changedFiles JSON。
     *
     * @param changedFilesObj changedFiles 字段（可能为 String 或 List）
     * @return 文件列表
     */
    public List<String> parseChangedFiles(Object changedFilesObj) {
        if (changedFilesObj == null) {
            return Collections.emptyList();
        }
        String json = String.valueOf(changedFilesObj);
        if (StrUtil.isBlank(json)) {
            return Collections.emptyList();
        }
        try {
            List<String> files = objectMapper.readValue(json, new TypeReference<>() {
            });
            return files == null ? Collections.emptyList() : files;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 构建 state key。
     *
     * @param appId 应用 id
     * @return redis key
     */
    // Centralized AI-visible memory version used by cached service freshness checks.
    public String loadAiVisibleMemoryVersion(Long appId) {
        if (appId == null || appId <= 0) {
            return "invalid";
        }
        try {
            String version = stringRedisTemplate.opsForValue().get(buildAiMemoryVersionKey(appId));
            return StrUtil.blankToDefault(version, "0");
        } catch (Exception e) {
            return "0";
        }
    }

    public String bumpAiVisibleMemoryVersion(Long appId, String reason) {
        if (appId == null || appId <= 0) {
            return "invalid";
        }
        String version = System.currentTimeMillis() + ":" + UUID.randomUUID() + ":" + StrUtil.blankToDefault(reason, "unknown");
        try {
            stringRedisTemplate.opsForValue().set(
                    buildAiMemoryVersionKey(appId),
                    version,
                    properties.getStateTtlSeconds(),
                    TimeUnit.SECONDS);
        } catch (Exception ignore) {
            // Version is a freshness hint. If Redis is down, callers still fall back to Redis-empty rebuild checks.
        }
        return version;
    }

    private String buildStateKey(Long appId) {
        return STATE_KEY_PREFIX + appId;
    }

    private String buildAiMemoryVersionKey(Long appId) {
        return AI_MEMORY_VERSION_KEY_PREFIX + appId;
    }
}
