package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.config.ConversationMemoryProperties;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.constant.ConversationMemoryConstant;
import com.dbts.glyahhaigeneratecode.mapper.ChatHistoryMapper;
import com.dbts.glyahhaigeneratecode.mapper.ConversationMemoryRefMapper;
import com.dbts.glyahhaigeneratecode.mapper.ConversationMemoryStateMapper;
import com.dbts.glyahhaigeneratecode.mapper.SnapshotHistoryMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.Entity.ConversationMemoryState;
import com.dbts.glyahhaigeneratecode.model.Entity.SnapshotHistory;
import com.dbts.glyahhaigeneratecode.model.VO.ConversationMemoryInjectResult;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ConversationMemoryStateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 会话记忆状态服务实现。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationMemoryStateServiceImpl implements ConversationMemoryStateService {

    private final ConversationMemoryStateMapper conversationMemoryStateMapper;
    private final ConversationMemoryRefMapper conversationMemoryRefMapper;
    private final SnapshotHistoryMapper snapshotHistoryMapper;
    private final ChatHistoryMapper chatHistoryMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationMemoryProperties properties;


    /**
     * 轮次完成后更新 memory_state/ref/snapshot 并写 Redis 热缓存。
     *
     * @param appId           应用 id
     * @param roundId         轮次 id（chat_history.id）
     * @param userId          用户 id
     * @param codeGenTypeEnum 代码生成类型
     * @param workflowMode    是否 workflow
     * @param bufferChars     本轮输出字符数
     * @param elapsedMs       轮次耗时毫秒
     * @return 无
     */
    @Override
    public void onRoundCompleted(Long appId, Long roundId, Long userId, CodeGenTypeEnum codeGenTypeEnum, boolean workflowMode, int bufferChars, long elapsedMs) {
        if (appId == null || appId <= 0 || roundId == null || roundId <= 0) {
            return;
        }

        long start = System.currentTimeMillis();
        String summarizeLevel = "none";
        long snapshotId = 0L;
        int manifestFilesCount = 0;
        int changedFilesCount = 0;
        int refArchivedCount = 0;
        String redisStatus = "miss";
        String dbStatus = "ok";
        try {
            // 1. 目录稳定性检查，防止 workflow 落盘尚未完成就生成 manifest。
            Path projectRoot = resolveProjectRoot(appId, codeGenTypeEnum);
            awaitStableDirectory(projectRoot);

            // 2. 构建当前 manifest，并读取上一轮快照做 diff。
            ManifestBundle current = buildManifest(projectRoot);
            manifestFilesCount = current.items().size();
            ManifestBundle previous = findLatestManifest(appId);
            List<String> changedFiles = diffChangedFiles(previous, current);
            changedFilesCount = changedFiles.size();

            // 3. 保存 snapshot_history 真相源。
            snapshotId = insertSnapshotHistory(appId, roundId, current);

            // 4. 滚动维护 memory_state（软/硬摘要 + changedFiles）。
            SummaryBundle summaryBundle = buildSummaryBundle(appId);
            summarizeLevel = summaryBundle.level();
            upsertConversationMemoryState(appId, roundId, snapshotId, summaryBundle, changedFiles);

            // 5. 超长 changed file 归档为 ref，避免主状态膨胀。
            refArchivedCount = archiveLargeChangedFilesIfNeeded(appId, roundId, projectRoot, changedFiles);

            // 6. Redis 热缓存：state/ref/page 分层写入并分别设置 TTL。
            try {
                cacheMemoryStateToRedis(appId);
                redisStatus = "hit";
            } catch (Exception redisEx) {
                redisStatus = "fallback";
                log.warn("会话记忆 Redis 回填失败，允许后续 DB 回源，appId={}, roundId={}", appId, roundId, redisEx);
            }
        } catch (Exception e) {
            dbStatus = "skip";
            log.warn("onRoundCompleted 持久化失败但已隔离主链路，appId={}, roundId={}", appId, roundId, e);
        } finally {
            // 7. 固化可观测字段，便于排查 manifest/摘要/Redis 抖动问题。
            long finalElapsed = System.currentTimeMillis() - start;
            log.info("onRoundCompleted metrics appId={} roundId={} snapshotId={} manifestFilesCount={} changedFilesCount={} bufferChars={} summarizeLevel={} refArchivedCount={} elapsedMs={} redisHit={} dbFallback={} workflowMode={} userId={}",
                    appId, roundId, snapshotId, manifestFilesCount, changedFilesCount, bufferChars, summarizeLevel,
                    refArchivedCount, Math.max(elapsedMs, finalElapsed), redisStatus, dbStatus, workflowMode, userId);
        }
    }

    /**
     * 加载 memory_state 并执行按需 readFile 分页注入。
     *
     * @param appId           应用 id
     * @param chatMemory      聊天内存
     * @param codeGenTypeEnum 代码生成类型
     * @param maxCount        历史消息上限
     * @return 注入结果
     */
    @Override
    public ConversationMemoryInjectResult loadConversationMemoryStateAndInject (Long appId, MessageWindowChatMemory chatMemory, CodeGenTypeEnum codeGenTypeEnum, int maxCount) {
        if (appId == null || appId <= 0) {
            return ConversationMemoryInjectResult.builder()
                    .source("db")
                    .injectedMessageCount(0)
                    .changedFiles(Collections.emptyList())
                    .build();
        }

        // 1. 先读 Redis，miss 再回源 DB。
        // 将redis的string(Json)格式转成map
        Map<String, Object> state = loadStateFromRedisOrDb(appId);
        List<String> changedFiles = parseChangedFiles(state.get("changedFilesJson"));

        // 2. 按优先级排序注入文件列表：入口/配置/路由 -> changedFiles -> 其余补齐。
        Path root = resolveProjectRoot(appId, codeGenTypeEnum);
        List<Path> candidates = collectInjectCandidates(root, changedFiles);

        // 3. 双阈预算：字符与 token 近似预算（chars/4），取更紧约束。
        int remainingChars = ConversationMemoryConstant.DEFAULT_INJECT_CHAR_BUDGET;
        int remainingTokens = ConversationMemoryConstant.DEFAULT_INJECT_TOKEN_BUDGET;

        int injected = 0;
        // 遍历所有代码改变的路径
        for (Path file : candidates) {
            if (remainingChars <= 0 || remainingTokens <= 0) {
                break;
            }
            try {
                // 获取相对路径
                String relative = root.relativize(file).toString().replace('\\', '/');
                // 根据路径到temp/下读取前9000字符的内容
                String content = readFilePageWithCache(appId, relative, ConversationMemoryConstant.DEFAULT_PAGE_SIZE);
                if (StrUtil.isBlank(content)) {
                    continue;
                }
                int chars = content.length();
                // token近似计算
                int tokens = Math.max(1, chars / 4);
                if (chars > remainingChars || tokens > remainingTokens) {
                    continue;
                }
                // 1. 将按需读取的文件片段真正注入 MessageWindowChatMemory(ai最后系统性思考的内容)，闭环 C 要求。
                // 2. 使用 SystemMessage 承载参考资料，避免被模型误当作「用户新指令」。
                addInjectedFileToMemory(chatMemory, root, file, content);
                // 更新剩余的tokens
                remainingChars -= chars;
                remainingTokens -= tokens;

                injected++;
            } catch (Exception ignore) {
                // 单文件失败不影响注入主流程
            }
        }

        String source = "redis";
        if (state.isEmpty()) {
            source = "db";
        }
        return ConversationMemoryInjectResult.builder()
                // 从何处拿到的数据
                .source(source)
                .injectedMessageCount(Math.min(injected, Math.max(0, maxCount)))
                .changedFiles(changedFiles)
                .build();
    }

    /**
     * 将文件片段以 {@link SystemMessage} 注入聊天内存（参考资料语义，避免与用户指令混淆）。
     *
     * @param chatMemory 聊天内存
     * @param root 项目根目录
     * @param file 当前文件
     * @param content 读取到的分页内容
     * @return 无
     */
    private void addInjectedFileToMemory(MessageWindowChatMemory chatMemory, Path root, Path file, String content) {
        if (chatMemory == null || root == null || file == null || StrUtil.isBlank(content)) {
            return;
        }
        String relative = root.relativize(file).toString().replace('\\', '/');
        String header = relative;
        if (header.length() > ConversationMemoryConstant.INJECT_FILE_HEADER_MAX_LENGTH) {
            header = header.substring(0, ConversationMemoryConstant.INJECT_FILE_HEADER_MAX_LENGTH);
        }
        // 1. 注入格式保持可读，便于模型识别来源文件与分页内容。
        // 2. 使用 [memory_inject] 前缀标记，避免与用户原始输入混淆。
        String injectedMessage = "[memory_inject] file=" + header + "\n" + content;
        chatMemory.add(SystemMessage.from(injectedMessage));
    }

    /**
     * 定时清理 ref/snapshot 旧数据，失败不影响主链路。
     *
     * @return 无
     */
    @Override
    @Scheduled(cron = "0 0/30 * * * ?")
    public void cleanupMemoryRefsAndSnapshots() {
        try {
            conversationMemoryRefMapper.deleteByCreatedBeforeDays(properties.getRefKeepDaysPerApp());

            conversationMemoryRefMapper.deleteExcessRowsPerApp(properties.getRefKeepCountPerApp());

            List<Long> appIds = conversationMemoryRefMapper.selectDistinctAppIds();
            if (appIds != null) {
                for (Long appId : appIds) {
                    Long totalBytes = conversationMemoryRefMapper.sumContentBytesByAppId(appId);
                    if (totalBytes == null || totalBytes <= properties.getRefKeepBytesPerApp()) {
                        continue;
                    }
                    long overflow = totalBytes - properties.getRefKeepBytesPerApp();
                    conversationMemoryRefMapper.deleteOldestUntilBytesRemoved(appId, overflow);
                }
            }

            snapshotHistoryMapper.deleteOlderThan30Days();
        } catch (Exception e) {
            log.warn("会话记忆清理任务失败（已隔离），error={}", e.getMessage(), e);
        }
    }

    /**
     * 等待目录元信息稳定，避免生成中的文件被半读。
     *
     * @param root 代码目录
     * @return 无
     */
    private void awaitStableDirectory(Path root) {
        if (root == null) {
            return;
        }
        int retryTimes = Math.max(1, properties.getManifestStableRetryTimes());
        long sleepMs = Math.max(50L, properties.getManifestStableRetrySleepMs());
        long lastCount = -1;
        long lastMtime = -1;
        for (int i = 0; i < retryTimes; i++) {
            DirMetrics metrics = collectDirMetrics(root);
            if (metrics.fileCount() == lastCount && metrics.latestMtime() == lastMtime) {
                return;
            }
            lastCount = metrics.fileCount();
            lastMtime = metrics.latestMtime();
            if (i < retryTimes - 1) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 统计目录文件数与最新修改时间用于稳定性判定。
     *
     * @param root 代码目录
     * @return 目录指标
     */
    private DirMetrics collectDirMetrics(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return new DirMetrics(0, 0);
        }
        long count = 0;
        long maxMtime = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            count = files.size();
            for (Path path : files) {
                try {
                    long mtime = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
                    if (mtime > maxMtime) {
                        maxMtime = mtime;
                    }
                } catch (IOException ignore) {
                    // 单文件失败忽略
                }
            }
        } catch (Exception ignore) {
            // 目录不可读时返回默认值
        }
        return new DirMetrics(count, maxMtime);
    }

    /**
     * 解析项目根目录。
     *
     * @param appId           应用 id
     * @param codeGenTypeEnum 代码生成类型
     * @return 项目根路径
     */
    private Path resolveProjectRoot(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        if (codeGenTypeEnum == CodeGenTypeEnum.VUE) {
            return Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId);
        }
        String type = codeGenTypeEnum == null ? CodeGenTypeEnum.MULTI_FILE.getValue() : codeGenTypeEnum.getValue();
        return Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, type + "_" + appId);
    }

    /**
     * 生成当前代码目录 manifest。
     *
     * @param root 代码目录
     * @return manifest 包
     */
    private ManifestBundle buildManifest(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return new ManifestBundle(Collections.emptyList());
        }
        List<ManifestItem> items = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> !isIgnoredPath(root, path))
                    .filter(path -> isTextCodeFile(path))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
            for (Path file : files) {
                try {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    long size = Files.size(file);
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    String lang = detectLang(relative);
                    String hash = sha256Hex(Files.readAllBytes(file));
                    items.add(new ManifestItem(relative, hash, size, mtime, lang));
                } catch (Exception ignore) {
                    // 单文件读取失败跳过
                }
            }
        } catch (Exception e) {
            log.warn("构建 manifest 失败，root={}", root, e);
        }
        return new ManifestBundle(items);
    }

    /**
     * 从 snapshot_history 读取最新 manifest。
     *
     * @param appId 应用 id
     * @return manifest 包
     */
    private ManifestBundle findLatestManifest(Long appId) {
        try {
            QueryWrapper queryWrapper = new QueryWrapper();
            queryWrapper.eq(SnapshotHistory::getAppId, appId);
            queryWrapper.orderBy(SnapshotHistory::getId, false);
            queryWrapper.limit(1);
            List<SnapshotHistory> list = snapshotHistoryMapper.selectListByQuery(queryWrapper);
            if (list == null || list.isEmpty()) {
                return null;
            }
            String json = list.getFirst().getManifestJson();
            if (StrUtil.isBlank(json)) {
                return null;
            }
            List<ManifestItem> items = objectMapper.readValue(json, new TypeReference<>() {
            });
            return new ManifestBundle(items == null ? Collections.emptyList() : items);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 计算前后 manifest changedFiles。
     *
     * @param previous 上一轮 manifest
     * @param current  当前 manifest
     * @return changedFiles
     */
    private List<String> diffChangedFiles(ManifestBundle previous, ManifestBundle current) {
        if (current == null || current.items().isEmpty()) {
            return Collections.emptyList();
        }
        if (previous == null || previous.items().isEmpty()) {
            return current.items().stream().map(ManifestItem::path).limit(20).toList();
        }
        Map<String, ManifestItem> prevMap = previous.items().stream()
                .collect(Collectors.toMap(ManifestItem::path, item -> item, (a, b) -> a));
        List<String> changed = new ArrayList<>();
        for (ManifestItem item : current.items()) {
            ManifestItem prev = prevMap.get(item.path());
            if (prev == null || !Objects.equals(prev.hash(), item.hash())) {
                changed.add(item.path());
            }
        }
        return changed;
    }

    /**
     * 写入 snapshot_history。
     *
     * @param appId    应用 id
     * @param roundId  轮次 id
     * @param manifest manifest
     * @return snapshotId
     */
    private long insertSnapshotHistory(Long appId, Long roundId, ManifestBundle manifest) {
        try {
            String json = objectMapper.writeValueAsString(manifest.items());
            SnapshotHistory row = SnapshotHistory.builder()
                    .appId(appId)
                    .roundId(roundId)
                    .manifestJson(json)
                    .filesCount(manifest.items().size())
                    .createdAt(LocalDateTime.now())
                    .build();
            snapshotHistoryMapper.insert(row);
            Long id = row.getId();
            return id == null ? 0L : id;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 构建软/硬摘要。
     *
     * @param appId 应用 id
     * @return 摘要结果
     */
    private SummaryBundle buildSummaryBundle(Long appId) {
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(ChatHistory::getAppId, appId);
        queryWrapper.eq(ChatHistory::getMessageType, "user");
        queryWrapper.eq(ChatHistory::getIsDelete, 0);
        long count = chatHistoryMapper.selectCountByQuery(queryWrapper);
        if (count >= 24) {
            return new SummaryBundle("hard", "最近高频编辑阶段，建议优先依据 changedFiles 与报错定位做增量修改。", "会话较长：建议按 changedFiles 优先读取并逐页 readFile。");
        }
        if (count >= 12) {
            return new SummaryBundle("soft", "会话进入中长上下文阶段，建议优先按最新变更文件定位。", null);
        }
        return new SummaryBundle("none", null, null);
    }

    /**
     * 更新 memory_state。
     *
     * @param appId        应用 id
     * @param roundId      轮次 id
     * @param snapshotId   快照 id
     * @param summaryBundle 摘要
     * @param changedFiles changedFiles
     * @return 无
     */
    private void upsertConversationMemoryState(Long appId, Long roundId, long snapshotId, SummaryBundle summaryBundle, List<String> changedFiles) throws Exception {
        String changedFilesJson = objectMapper.writeValueAsString(changedFiles == null ? Collections.emptyList() : changedFiles);
        conversationMemoryStateMapper.upsertByAppId(
                appId,
                roundId,
                snapshotId <= 0 ? null : snapshotId,
                summaryBundle.softSummary(),
                summaryBundle.hardSummary(),
                changedFilesJson
        );
    }

    /**
     * 归档超长 changed file 到 ref。
     *
     * @param appId       应用 id
     * @param roundId     轮次 id
     * @param root        项目目录
     * @param changedFiles changedFiles
     * @return 归档数量
     */
    private int archiveLargeChangedFilesIfNeeded(Long appId, Long roundId, Path root, List<String> changedFiles) {
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
                if (content.length() < 8000) {
                    continue;
                }
                String refId = "ref-" + appId + "-" + roundId + "-" + sha256Hex(relative + ":" + content.substring(0, Math.min(2000, content.length())));
                long bytes = content.getBytes(StandardCharsets.UTF_8).length;
                conversationMemoryRefMapper.insertIgnore(appId, roundId, refId, relative, content, bytes);
                cacheRefToRedis(refId, content);
                archived++;
            } catch (Exception ignore) {
                // 单文件归档失败不影响主流程
            }
        }
        return archived;
    }

    /**
     * 缓存 memory_state 到 Redis。
     *
     * @param appId 应用 id
     * @return 无
     */
    private void cacheMemoryStateToRedis(Long appId) throws Exception {
        Map<String, Object> state = loadStateFromDb(appId);
        if (state.isEmpty()) {
            return;
        }
        String key = buildStateKey(appId);
        String value = objectMapper.writeValueAsString(state);
        stringRedisTemplate.opsForValue().set(key, value, properties.getStateTtlSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 读取 state（先 Redis 后 DB）。
     *
     * @param appId 应用 id
     * @return 状态 map
     */
    private Map<String, Object> loadStateFromRedisOrDb(Long appId) {
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
        Map<String, Object> dbState = loadStateFromDb(appId);
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
    private Map<String, Object> loadStateFromDb(Long appId) {
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
            return map;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * 解析 changedFiles JSON。
     *
     * @param changedFilesObj changedFiles 字段
     * @return 文件列表
     */
    private List<String> parseChangedFiles(Object changedFilesObj) {
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
     * 收集注入候选文件并按优先级排序。
     *
     * @param root         项目目录
     * @param changedFiles changedFiles
     * @return 文件列表
     */
    private List<Path> collectInjectCandidates(Path root, List<String> changedFiles) {
        if (root == null || !Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        List<Path> all;
        try (Stream<Path> walk = Files.walk(root)) {
            all = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> !isIgnoredPath(root, path))
                    .filter(this::isTextCodeFile)
                    .toList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
        Set<String> changedSet = new HashSet<>(changedFiles == null ? Collections.emptyList() : changedFiles);
        List<Path> first = new ArrayList<>();
        List<Path> second = new ArrayList<>();
        List<Path> third = new ArrayList<>();
        for (Path path : all) {
            String relative = root.relativize(path).toString().replace('\\', '/');
            String lower = relative.toLowerCase(Locale.ROOT);
            if (lower.endsWith("main.java")
                    || lower.contains("application.yml")
                    || lower.contains("application.yaml")
                    || lower.contains("router")
                    || lower.endsWith("index.html")
                    || lower.endsWith("app.vue")) {
                first.add(path);
                continue;
            }
            if (changedSet.contains(relative)) {
                second.add(path);
                continue;
            }
            third.add(path);
        }
        List<Path> merged = new ArrayList<>(first.size() + second.size() + third.size());
        merged.addAll(first);
        merged.addAll(second);
        merged.addAll(third);
        return merged;
    }

    /**
     * 分页读取文件并使用 Redis page 缓存。
     *
     * @param appId    应用 id
     * @param relative 相对路径
     * @param pageSize 分页大小
     * @return 文件前一页
     */
    private String readFilePageWithCache(Long appId, String relative, int pageSize) throws IOException {

        // 1. Redis 分页缓存 key：cm:page:{appId}:{相对路径}:{页号}，当前仅缓存第 0 页（文件头一段）
        String key = "cm:page:" + appId + ":" + relative + ":0";
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(cached)) {
            return cached;
        }

        // 2. 落盘根目录 + 相对路径 → 绝对路径；normalize + startsWith 防止跳出输出根目录
        Path root = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR);
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
            return "";
        }

        // 3. 读全文后截取前 pageSize 字符作为「第一页」，避免一次把大文件塞进内存上下文
        // 直接截断分页
        String content = Files.readString(candidate, StandardCharsets.UTF_8);
        String page = content.length() <= pageSize ? content : content.substring(0, pageSize);

        // 4. 回写 Redis 短 TTL，减轻同一路径重复读盘
        stringRedisTemplate.opsForValue().set(key, page, properties.getPageTtlSeconds(), TimeUnit.SECONDS);
        return page;
    }

    /**
     * 缓存 ref 内容。
     *
     * @param refId   ref id
     * @param content 内容
     * @return 无
     */
    private void cacheRefToRedis(String refId, String content) {
        if (StrUtil.isBlank(refId) || content == null) {
            return;
        }
        String key = "cm:ref:" + refId;
        stringRedisTemplate.opsForValue().set(key, content, properties.getRefTtlSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 构建 state key。
     *
     * @param appId 应用 id
     * @return redis key
     */
    private String buildStateKey(Long appId) {
        return "cm:state:" + appId;
    }

    /**
     * 判断路径是否应忽略。
     *
     * @param root 根目录
     * @param path 文件路径
     * @return true-忽略；false-保留
     */
    private boolean isIgnoredPath(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path segment : relative) {
            if (ConversationMemoryConstant.SNAPSHOT_IGNORE_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否文本代码文件。
     *
     * @param path 文件
     * @return true-文本代码；false-其他
     */
    private boolean isTextCodeFile(Path path) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
        return ConversationMemoryConstant.TEXT_FILE_EXTS.contains(ext);
    }

    /**
     * 推断文件语言标签。
     *
     * @param relative 相对路径
     * @return 语言名
     */
    private String detectLang(String relative) {
        if (relative == null) {
            return "text";
        }
        String lower = relative.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            return "java";
        }
        if (lower.endsWith(".vue")) {
            return "vue";
        }
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            return "typescript";
        }
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) {
            return "javascript";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "html";
        }
        if (lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".less")) {
            return "css";
        }
        if (lower.endsWith(".json")) {
            return "json";
        }
        return "text";
    }

    /**
     * 计算 SHA-256 十六进制。
     *
     * @param bytes 字节数组
     * @return 哈希串
     */
    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 计算 SHA-256 十六进制。
     *
     * @param text 文本
     * @return 哈希串
     */
    private String sha256Hex(String text) {
        return sha256Hex(StrUtil.blankToDefault(text, "").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 目录指标。
     */
    private record DirMetrics(long fileCount, long latestMtime) {
    }

    /**
     * 摘要包。
     */
    private record SummaryBundle(String level, String softSummary, String hardSummary) {
    }

    /**
     * Manifest 包。
     */
    private record ManifestBundle(List<ManifestItem> items) {
    }

    /**
     * Manifest 条目。
     */
    private record ManifestItem(String path, String hash, long size, long mtime, String lang) {
    }
}
