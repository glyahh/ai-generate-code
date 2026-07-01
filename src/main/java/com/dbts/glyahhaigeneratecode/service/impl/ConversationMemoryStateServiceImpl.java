package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.config.ConversationMemoryProperties;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryDirStabilitySupport;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryManifestSupport;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryManifestSupport.ManifestBundle;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryRefArchiver;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryStateCache;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryStateInjectSupport;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemorySummarySupport;
import com.dbts.glyahhaigeneratecode.mapper.ChatHistoryMapper;
import com.dbts.glyahhaigeneratecode.mapper.ConversationMemoryRefMapper;
import com.dbts.glyahhaigeneratecode.mapper.SnapshotHistoryMapper;
import com.dbts.glyahhaigeneratecode.model.VO.ConversationMemoryInjectResult;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ConversationMemoryStateService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationMemoryStateServiceImpl implements ConversationMemoryStateService {

    private final ConversationMemoryStateCache stateCache;
    private final ConversationMemoryRefArchiver refArchiver;
    private final ConversationMemoryStateInjectSupport injectSupport;
    private final ChatHistoryMapper chatHistoryMapper;
    private final SnapshotHistoryMapper snapshotHistoryMapper;
    private final ConversationMemoryRefMapper conversationMemoryRefMapper;
    private final ConversationMemoryProperties properties;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void onRoundCompleted(Long appId, Long roundId, Long userId, CodeGenTypeEnum codeGenTypeEnum,
                                 boolean workflowMode, int bufferChars, long elapsedMs) {
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
        String fileNoteStatus = "none";
        try {
            Path projectRoot = ConversationMemoryManifestSupport.resolveProjectRoot(appId, codeGenTypeEnum);
            ConversationMemoryDirStabilitySupport.awaitStableDirectory(
                    projectRoot,
                    properties.getManifestStableRetryTimes(),
                    properties.getManifestStableRetrySleepMs());

            ManifestBundle current = ConversationMemoryManifestSupport.buildManifest(projectRoot);
            manifestFilesCount = current.items().size();
            ManifestBundle previous = ConversationMemoryManifestSupport.findLatestManifest(
                    appId, snapshotHistoryMapper, stateCache.getObjectMapper());
            List<String> changedFiles = ConversationMemoryManifestSupport.diffChangedFiles(previous, current);
            changedFilesCount = changedFiles.size();

            String fileNotesJson = injectSupport.resolveFileNotesJsonForUpsert(appId, roundId);
            fileNoteStatus = fileNotesJson == null ? "unchanged" : "ok";
            ConversationMemorySummarySupport.SummaryBundle summaryBundle =
                    ConversationMemorySummarySupport.buildSummaryBundle(appId, chatHistoryMapper);
            summarizeLevel = summaryBundle.level();

            long[] snapshotIdHolder = new long[]{0L};
            transactionTemplate.executeWithoutResult(status -> {
                long insertedSnapshotId = ConversationMemoryManifestSupport.insertSnapshotHistory(
                        appId, roundId, current, snapshotHistoryMapper, stateCache.getObjectMapper());
                if (insertedSnapshotId <= 0) {
                    throw new IllegalStateException("insert snapshot history failed");
                }
                snapshotIdHolder[0] = insertedSnapshotId;
                try {
                    stateCache.upsert(appId, roundId, insertedSnapshotId, summaryBundle, changedFiles, fileNotesJson);
                } catch (Exception e) {
                    throw new IllegalStateException("upsert conversation memory state failed", e);
                }
            });
            snapshotId = snapshotIdHolder[0];

            // refArchivedCount = refArchiver.archiveLargeChangedFilesIfNeeded(appId, roundId, projectRoot, changedFiles);

            try {
                stateCache.cacheToRedis(appId);
                stateCache.bumpAiVisibleMemoryVersion(appId, "round_completed:" + roundId + ":" + snapshotId);
                redisStatus = "hit";
            } catch (Exception redisEx) {
                redisStatus = "fallback";
                log.warn("conversation memory redis backfill failed, fallback to DB, appId={}, roundId={}",
                        appId, roundId, redisEx);
            }
        } catch (Exception e) {
            dbStatus = "skip";
            log.warn("onRoundCompleted persistence failed and was isolated, appId={}, roundId={}", appId, roundId, e);
        } finally {
            long finalElapsed = System.currentTimeMillis() - start;
            log.info("onRoundCompleted metrics appId={} roundId={} snapshotId={} manifestFilesCount={} changedFilesCount={} bufferChars={} summarizeLevel={} refArchivedCount={} fileNoteStatus={} elapsedMs={} redisHit={} dbFallback={} workflowMode={} userId={}",
                    appId, roundId, snapshotId, manifestFilesCount, changedFilesCount, bufferChars, summarizeLevel,
                    refArchivedCount, fileNoteStatus, Math.max(elapsedMs, finalElapsed), redisStatus, dbStatus,
                    workflowMode, userId);
        }
    }

    @Override
    public ConversationMemoryInjectResult loadConversationMemoryStateAndInject(Long appId,
                                                                               MessageWindowChatMemory chatMemory,
                                                                               CodeGenTypeEnum codeGenTypeEnum,
                                                                               int maxCount) {
        if (appId == null || appId <= 0) {
            return ConversationMemoryInjectResult.builder()
                    .source("db")
                    .injectedMessageCount(0)
                    .changedFiles(Collections.emptyList())
                    .build();
        }

        Map<String, Object> state = stateCache.loadFromRedisOrDb(appId);
        List<String> changedFiles = stateCache.parseChangedFiles(state.get("changedFilesJson"));
        int taggedInjected = injectSupport.injectMemoryTaggedMessages(
                chatMemory,
                changedFiles,
                injectSupport.parseFileNotes(state.get("fileNotesJson")));

        String source = state.isEmpty() ? "db" : "redis";
        return ConversationMemoryInjectResult.builder()
                .source(source)
                .injectedMessageCount(Math.min(taggedInjected, Math.max(0, maxCount)))
                .changedFiles(changedFiles)
                .build();
    }

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
            log.warn("conversation memory cleanup failed and was isolated, error={}", e.getMessage(), e);
        }
    }
}
