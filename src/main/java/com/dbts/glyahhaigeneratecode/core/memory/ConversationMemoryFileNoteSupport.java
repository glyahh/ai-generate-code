package com.dbts.glyahhaigeneratecode.core.memory;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.model.memory.FileNoteEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * fileNotesJson 解析、LLM 输出解析与 merge。
 * DB 中 fileNotesJson 字符串 -> 反序列化为 path 映射 -> LLM 批量输出解析与清洗 -> 按路径合并写回可序列化 Map。
 */
public final class ConversationMemoryFileNoteSupport {

    // 批量摘要模型输出中 markdown 围栏内的 JSON
    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    // fileNote 入库前脱敏：api-key、password 等键值对
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(api[_-]?key|secret|password|token|authorization)\\s*[:=]\\s*\\S+");
    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private ConversationMemoryFileNoteSupport() {
    }

    /**
     * 将 memory_state 中的 fileNotesJson 反序列化为路径到条目的映射
     *
     * @param json         fileNotesJson 字符串
     * @param objectMapper JSON 解析器
     * @return 路径到 FileNoteEntry 的映射；空或解析失败时返回空 Map
     */
    public static Map<String, FileNoteEntry> parseFileNotesJson(String json, ObjectMapper objectMapper) {
        if (StrUtil.isBlank(json)) {
            return emptyNoteMap();
        }
        try {
            Map<String, FileNoteEntry> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            return parsed != null ? new LinkedHashMap<>(parsed) : emptyNoteMap();
        } catch (Exception ignored) {
            return emptyNoteMap();
        }
    }

    /**
     * 将合并后的 fileNote 映射序列化为可写入 DB 的 JSON 字符串
     *
     * @param notes          路径到 FileNoteEntry 的映射
     * @param objectMapper   JSON 序列化器
     * @return fileNotesJson 字符串；无条目时返回 null
     * @throws Exception 序列化失败时抛出
     */
    public static String serializeFileNotes(Map<String, FileNoteEntry> notes, ObjectMapper objectMapper) throws Exception {
        if (notes == null || notes.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(notes);
    }

    /**
     * 从模型输出提取 path -> note 映射。
     *
     * @param raw          模型原始输出（可含 markdown 代码围栏）
     * @param objectMapper JSON 解析器
     * @return 路径到中文说明的映射；无法解析时返回空 Map
     */
    public static Map<String, String> parseBatchNoteResponse(String raw, ObjectMapper objectMapper) {
        if (StrUtil.isBlank(raw)) {
            return Map.of();
        }
        String text = raw.trim();
        // 1. 若模型用 ```json 包裹，先取出围栏内文本
        Matcher fence = JSON_FENCE.matcher(text);
        if (fence.find()) {
            text = fence.group(1).trim();
        }
        // 2. 截取首尾花括号之间的 JSON 对象
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Map.of();
        }
        try {
            // 3. 反序列化为 path -> 中文说明
            Map<String, String> map = objectMapper.readValue(text.substring(start, end + 1), new TypeReference<>() {
            });
            return map != null ? map : Map.of();
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    /**
     * 将本轮 LLM 摘要与已有 fileNote 按路径合并，未出现在 newNotesByPath 中的路径保留原说明
     *
     * @param existing       已有路径到 FileNoteEntry 的映射
     * @param newNotesByPath 本轮 LLM 输出的路径到中文说明
     * @param roundId        当前轮次 id（chat_history.id）
     * @param maxNoteChars   单条说明最大字符数
     * @return 合并后的完整映射，供序列化写入 memory_state
     */
    public static Map<String, FileNoteEntry> mergeNotes(
            Map<String, FileNoteEntry> existing,
            Map<String, String> newNotesByPath,
            Long roundId,
            int maxNoteChars) {
        // 1. 复制已有 note，未在本轮 newNotesByPath 中的路径将保留
        Map<String, FileNoteEntry> merged = existing == null ? emptyNoteMap() : new LinkedHashMap<>(existing);
        if (newNotesByPath == null || newNotesByPath.isEmpty()) {
            return merged;
        }
        String updatedAt = LocalDateTime.now().format(ISO_LOCAL);
        for (Map.Entry<String, String> e : newNotesByPath.entrySet()) {
            if (StrUtil.isBlank(e.getKey()) || StrUtil.isBlank(e.getValue())) {
                continue;
            }
            // 2. 清洗后写入本轮路径说明，并记录 roundId 与更新时间
            String note = sanitizeNote(e.getValue(), maxNoteChars);
            if (StrUtil.isBlank(note)) {
                continue;
            }
            merged.put(e.getKey().trim().replace('\\', '/'), new FileNoteEntry(note, roundId, updatedAt));
        }
        return merged;
    }

    /**
     * 清洗单条 fileNote 文本：脱敏敏感字段、压平空白并按上限截断
     *
     * @param note     原始说明文本
     * @param maxChars 最大字符数；不大于 0 时不截断
     * @return 可入库的说明字符串；空白输入返回空串
     */
    public static String sanitizeNote(String note, int maxChars) {
        if (StrUtil.isBlank(note)) {
            return "";
        }
        String cleaned = SENSITIVE.matcher(note).replaceAll("[已省略敏感字段]").replaceAll("\\s+", " ").trim();
        return maxChars > 0 && cleaned.length() > maxChars ? cleaned.substring(0, maxChars) : cleaned;
    }

    /**
     * 截断工具登记用的变更片段，控制传入批量摘要模型的输入长度
     *
     * @param hint     变更片段或 diff 摘要
     * @param maxChars 最大字符数；不大于 0 时不截断
     * @return 截断后的片段；hint 为 null 时返回空串
     */
    public static String truncateHint(String hint, int maxChars) {
        if (hint == null) {
            return "";
        }
        if (maxChars <= 0 || hint.length() <= maxChars) {
            return hint;
        }
        return hint.substring(0, maxChars);
    }

    /**
     * 返回可变的空 fileNote 映射，供解析失败或空输入时复用
     *
     * @return 空的 LinkedHashMap
     */
    private static Map<String, FileNoteEntry> emptyNoteMap() {
        return new LinkedHashMap<>();
    }
}
