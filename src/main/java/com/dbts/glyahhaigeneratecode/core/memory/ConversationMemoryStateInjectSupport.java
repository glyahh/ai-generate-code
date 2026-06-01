package com.dbts.glyahhaigeneratecode.core.memory;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.mapper.ConversationMemoryStateMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.ConversationMemoryState;
import com.dbts.glyahhaigeneratecode.model.memory.FileNoteEntry;
import com.dbts.glyahhaigeneratecode.service.ConversationMemoryFileNoteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 会话记忆注入支撑：fileNote JSON 解析/落库与 [memory_index]/[memory_file_note] 写入 ChatMemory。
 * flush pending fileNote 或回读 DB -> 解析 fileNotes 映射 -> 拼装 tag 正文注入 SystemMessage。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ConversationMemoryStateInjectSupport {

    private final ConversationMemoryStateMapper conversationMemoryStateMapper;
    private final ConversationMemoryFileNoteService conversationMemoryFileNoteService;
    private final ObjectMapper objectMapper;

    /**
     * 注入 [memory_index] 与 [memory_file_note]（在 [memory_inject] 之前）。
     *
     * @param chatMemory    Redis ChatMemory 窗口
     * @param changedFiles  上一轮变更路径列表
     * @param fileNotes     路径到 fileNote 的映射
     * @return 注入的 SystemMessage 条数
     */
    public int injectMemoryTaggedMessages(
            MessageWindowChatMemory chatMemory,
            List<String> changedFiles,
            Map<String, FileNoteEntry> fileNotes) {
        if (chatMemory == null) {
            return 0;
        }
        int count = 0;
        // 1. 判断 state 中是否有可注入的变更路径或 fileNote，用于决定是否写入 policy
        boolean hasStateIndex = changedFiles != null && !changedFiles.isEmpty();
        boolean hasFileNotes = fileNotes != null && !fileNotes.isEmpty();
        if (hasStateIndex || hasFileNotes) {
            // 2. 先注入 [memory_policy]，引导模型优先阅读后续 index/fileNote
            chatMemory.add(SystemMessage.from(ConversationMemoryInjectTexts.buildMemoryStatePriorityMessage()));
            count++;
        }
        // 3. 将 changedFilesJson 对应的上一轮变更路径写入 [memory_index]
        String indexMsg = ConversationMemoryInjectTexts.buildMemoryIndexMessage(changedFiles);
        if (StrUtil.isNotBlank(indexMsg)) {
            chatMemory.add(SystemMessage.from(indexMsg));
            count++;
        }
        // 4. 将 fileNotesJson 解析结果写入 [memory_file_note]
        String fileNoteMsg = ConversationMemoryInjectTexts.buildMemoryFileNoteMessage(fileNotes);
        if (StrUtil.isNotBlank(fileNoteMsg)) {
            chatMemory.add(SystemMessage.from(fileNoteMsg));
            count++;
        }
        return count;
    }

    /**
     * 将 cm:state 或 DB 中的 fileNotesJson 字段解析为路径到条目的映射
     *
     * @param fileNotesObj Redis state 中的 fileNotesJson 值（可能非 String）
     * @return 路径到 FileNoteEntry 的映射；空或解析失败时返回空 Map
     */
    public Map<String, FileNoteEntry> parseFileNotes(Object fileNotesObj) {
        if (fileNotesObj == null) {
            return Collections.emptyMap();
        }
        return ConversationMemoryFileNoteSupport.parseFileNotesJson(String.valueOf(fileNotesObj), objectMapper);
    }

    /**
     * flush 本轮 pending fileNote；无 pending 时保留 DB 已有 JSON。
     *
     * @param appId   应用 id
     * @param roundId 轮次 id（chat_history.id）
     * @return 可写入 memory_state 的 fileNotesJson；无 pending 且 DB 无值时可能为 null
     */
    public String resolveFileNotesJsonForUpsert(Long appId, Long roundId) {
        try {
            String flushed = conversationMemoryFileNoteService.flushPendingFileNotes(appId, roundId);
            if (StrUtil.isNotBlank(flushed)) {
                return flushed;
            }
        } catch (Exception e) {
            log.warn("fileNote flush 失败，保留 DB 原值，appId={}, roundId={}", appId, roundId, e);
        }
        return loadFileNotesJsonFromDb(appId);
    }

    /**
     * 从 conversation_memory_state 读取当前 fileNotesJson 原文
     *
     * @param appId 应用 id
     * @return fileNotesJson 字符串；无行或无字段时返回 null
     */
    public String loadFileNotesJsonFromDb(Long appId) {
        try {
            QueryWrapper queryWrapper = new QueryWrapper();
            queryWrapper.eq(ConversationMemoryState::getAppId, appId);
            ConversationMemoryState row = conversationMemoryStateMapper.selectOneByQuery(queryWrapper);
            return row == null ? null : row.getFileNotesJson();
        } catch (Exception e) {
            return null;
        }
    }
}
