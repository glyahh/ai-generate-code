package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.config.ConversationMemoryProperties;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryFileNoteSupport;
import com.dbts.glyahhaigeneratecode.mapper.ConversationMemoryStateMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.ConversationMemoryState;
import com.dbts.glyahhaigeneratecode.model.memory.FileNoteEntry;
import com.dbts.glyahhaigeneratecode.service.ConversationMemoryFileNoteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具写盘后 fileNote：pending 队列 + 单轮一次 LLM 批量摘要。
 * writeFile/modifyFile 成功登记路径与变更片段 -> 轮次收口 flush 取出 pending 并一次 LLM 批量摘要 -> 合并 DB 已有 note 序列化为 fileNotesJson 交给 onRoundCompleted 持久化。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationMemoryFileNoteServiceImpl implements ConversationMemoryFileNoteService {

    private final ConversationMemoryStateMapper conversationMemoryStateMapper;
    private final ConversationMemoryProperties properties;
    private final ObjectMapper objectMapper;

    @Resource(name = "fileNoteChatModel")
    private ChatModel fileNoteChatModel;

    // 线程安全, 多线程高并发专用, 多线程同时增删改查 (分段锁 / CAS 无锁算法)
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, String>> pendingByApp = new ConcurrentHashMap<>();

    // 其他线程在锁外做 if (batchPromptTemplate != null) 时，能立刻看到已赋值结果，不会一直用「半初始化」或旧 null
    // JVM 中，多个线程可以同时读写同一个共享变量,线程不直接操作主内存，而是操作自己的副本 → 导致看不见最新值
    // 一般用于变量被多个线程控制,并且只进行原子操作
    private volatile String batchPromptTemplate;



    @Override
    public void registerPendingFileChange(Long appId, String relativePath, String changeHint) {
        // 1. 校验 fileNote 开关、appId 与路径非空，不满足则结束登记
        if (!properties.isFileNoteEnabled() || appId == null || appId <= 0 || StrUtil.isBlank(relativePath)) {
            return;
        }
        // 2. 规范化相对路径，拒绝含 .. 的路径穿越
        String normalized = relativePath.trim().replace('\\', '/');
        if (normalized.contains("..")) {
            return;
        }
        // 3. 截断变更片段至配置上限，得到写入 pending 的 hint
        String hint = ConversationMemoryFileNoteSupport.truncateHint(
                StrUtil.blankToDefault(changeHint, ""),
                properties.getFileNoteInputChars());
        // 4. 按 appId 放入 pending 队列，同一路径本轮以最新 hint 覆盖
        pendingByApp
                // pendingByApp 里找有没有这个 appId, 没有则生成
                .computeIfAbsent(appId, id -> new ConcurrentHashMap<>())
                .put(normalized, hint);
    }


    @Override
    public String flushPendingFileNotes(Long appId, Long roundId) {
        // 1. 校验开关与 appId，未启用则返回 null 表示无 fileNotesJson 可写
        if (!properties.isFileNoteEnabled() || appId == null || appId <= 0) {
            return null;
        }
        // 2. 原子取出并清空本轮 pending，无待摘要路径则返回 null
        ConcurrentHashMap<String, String> pending = pendingByApp.remove(appId);
        if (pending == null || pending.isEmpty()) {
            return null;
        }

        // 3. 从 DB 加载该 app 已有 fileNotes，供合并与失败时回退
        Map<String, FileNoteEntry> existing = loadExistingNotes(appId);
        List<Map.Entry<String, String>> entries = new ArrayList<>(pending.entrySet());
        // 4. 按配置上限截断路径数，避免单轮 LLM 输入过大
        int maxPaths = Math.max(1, properties.getFileNoteMaxPathsPerRound());
        if (entries.size() > maxPaths) {
            log.warn("fileNote pending 路径数 {} 超过上限 {}，已截断，appId={}", entries.size(), maxPaths, appId);
            entries = entries.subList(0, maxPaths);
        }

        Map<String, String> llmNotes = Map.of();
        try {
            // 5. 调用小模型批量摘要，得到新的 path -> 中文说明
            llmNotes = callBatchSummary(entries);
        } catch (Exception e) {
            log.warn("fileNote 批量摘要失败，保留旧 note，appId={}, roundId={}", appId, roundId, e);
            try {
                // 6. 摘要失败时仅序列化已有 note 返回，不丢历史描述
                return ConversationMemoryFileNoteSupport.serializeFileNotes(existing, objectMapper);
            } catch (Exception serializeEx) {
                log.warn("fileNote 序列化旧 note 失败，appId={}, roundId={}", appId, roundId, serializeEx);
                return null;
            }
        }

        // 7. 将 LLM 新说明与已有 note 按路径合并，并写入 roundId 与字数上限
        Map<String, FileNoteEntry> merged = ConversationMemoryFileNoteSupport.mergeNotes(
                existing,
                llmNotes,
                roundId,
                properties.getFileNoteMaxNoteChars());

        try {
            // 8. 序列化为 fileNotesJson 字符串，交给 onRoundCompleted 写入 memory_state
            String json = ConversationMemoryFileNoteSupport.serializeFileNotes(merged, objectMapper);
            log.info("fileNote flush ok appId={} roundId={} pendingCount={} mergedPaths={}",
                    appId, roundId, entries.size(), llmNotes.size());
            return json;
        } catch (Exception e) {
            log.warn("fileNote 序列化失败，appId={}, roundId={}", appId, roundId, e);
            return null;
        }
    }

    private Map<String, FileNoteEntry> loadExistingNotes(Long appId) {
        try {
            // 1. 按 appId 查询 conversation_memory_state 单行
            QueryWrapper queryWrapper = new QueryWrapper();
            queryWrapper.eq(ConversationMemoryState::getAppId, appId);
            ConversationMemoryState row = conversationMemoryStateMapper.selectOneByQuery(queryWrapper);
            // 2. 无行或无 fileNotesJson 时返回空 Map，表示尚无历史文件说明
            if (row == null || StrUtil.isBlank(row.getFileNotesJson())) {
                return new LinkedHashMap<>();
            }
            // 3. 反序列化 JSON 为 path -> FileNoteEntry，供本轮合并
            return ConversationMemoryFileNoteSupport.parseFileNotesJson(row.getFileNotesJson(), objectMapper);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, String> callBatchSummary(List<Map.Entry<String, String>> entries) {
        if (entries.isEmpty()) {
            return Map.of();
        }
        // 1. 拼接本轮待摘要路径与变更片段，形成 LLM 用户侧输入块
        StringBuilder input = new StringBuilder();
        for (Map.Entry<String, String> e : entries) {
            input.append("---\n路径: ").append(e.getKey()).append("\n");
            if (StrUtil.isNotBlank(e.getValue())) {
                input.append("变更片段:\n").append(e.getValue()).append("\n");
            } else {
                input.append("变更片段: （无，请根据路径推断职责）\n");
            }
        }
        // 2. 加载批量摘要模板并与待说明文件列表组成完整 prompt
        String prompt = loadBatchPromptTemplate() + "\n\n待说明文件：\n" + input;
        // 3. 调用 fileNoteChatModel 一次对话，得到原始 JSON 文本
        String raw = fileNoteChatModel.chat(prompt);
        // 4. 解析模型输出为 path -> 中文说明 Map
        return ConversationMemoryFileNoteSupport.parseBatchNoteResponse(raw, objectMapper);
    }

    private String loadBatchPromptTemplate() {
        // 1. 快路径：模板已加载则直接返回，避免重复读 classpath
        if (batchPromptTemplate != null) {
            return batchPromptTemplate;
        }
        synchronized (this) {
            // 2. 双重检查：防止多线程同时进入时重复加载
            if (batchPromptTemplate != null) {
                return batchPromptTemplate;
            }
            try (InputStream in = new ClassPathResource("Prompt/file_note_batch.txt").getInputStream()) {
                // 3. 从 Prompt/file_note_batch.txt 读取批量摘要系统说明
                batchPromptTemplate = IoUtil.read(in, StandardCharsets.UTF_8);
            } catch (Exception e) {
                // 4. 读文件失败时使用内置兜底文案，保证 callBatchSummary 仍可调用
                batchPromptTemplate = "为每个路径输出 1-3 句中文说明，只输出 JSON 对象。";
                log.warn("加载 file_note_batch.txt 失败，使用兜底 prompt", e);
            }
            return batchPromptTemplate;
        }
    }
}
