package com.dbts.glyahhaigeneratecode.core.summary;

import cn.hutool.cache.Cache;
import cn.hutool.cache.CacheUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AiMessageSummaryService {

    private static final Pattern TOOL_SELECT_PATTERN = Pattern.compile("^\\[选择工具]\\s*(.+)$");
    private static final Pattern TOOL_WRITE_PATTERN = Pattern.compile("^\\[工具调用]\\s*写入文件\\s*(.+)$");
    private static final Pattern TOOL_MODIFY_PATTERN = Pattern.compile("^\\[工具调用]\\s*修改文件\\s*(.+)$");
    private static final Pattern TOOL_DELETE_PATTERN = Pattern.compile("^\\[工具调用]\\s*删除文件\\s*(.+)$");
    private static final Pattern TOOL_DIR_READ_PATTERN = Pattern.compile("^\\[工具调用]\\s*读取目录\\s*(.+)$");
    private static final Pattern TOOL_EXEC_GENERIC_PATTERN = Pattern.compile("^\\[工具调用]\\s*(.+)$");

    private final Cache<Long, Map<Long, SummaryCacheEntry>> summaryCache =
            CacheUtil.newTimedCache(5 * 60 * 1000);

    public record SummaryCacheEntry(String summaryText, String naturalLanguage, List<ToolCallInfo> toolCalls) {}

    public SummaryResult parseAiMessage(String message) {
        if (StrUtil.isBlank(message)) {
            return SummaryResult.builder().totalToolCalls(0).naturalLanguageChunks(List.of()).build();
        }
        int writeCount = 0, modifyCount = 0, deleteCount = 0, dirReadCount = 0;
        List<String> nlChunks = new ArrayList<>();
        String[] lines = message.split("\n");
        boolean insideCodeBlock = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) { insideCodeBlock = !insideCodeBlock; continue; }
            if (insideCodeBlock) continue;
            if (TOOL_SELECT_PATTERN.matcher(trimmed).find()) continue;
            if (TOOL_WRITE_PATTERN.matcher(trimmed).find()) { writeCount++; continue; }
            if (TOOL_MODIFY_PATTERN.matcher(trimmed).find()) { modifyCount++; continue; }
            if (TOOL_DELETE_PATTERN.matcher(trimmed).find()) { deleteCount++; continue; }
            if (TOOL_DIR_READ_PATTERN.matcher(trimmed).find()) { dirReadCount++; continue; }
            if (TOOL_EXEC_GENERIC_PATTERN.matcher(trimmed).find()) continue;
            if (StrUtil.isNotBlank(trimmed)) nlChunks.add(trimmed);
        }
        return SummaryResult.builder()
                .totalToolCalls(writeCount + modifyCount + deleteCount + dirReadCount)
                .writeFileCount(writeCount).modifyFileCount(modifyCount)
                .deleteFileCount(deleteCount).dirReadCount(dirReadCount)
                .naturalLanguageChunks(nlChunks).build();
    }

    public String buildUserSummary(SummaryResult result) {
        if (result == null || result.getTotalToolCalls() == 0) return "[AI回复已完成] 共调用 0 次工具";
        StringBuilder sb = new StringBuilder("[AI回复已完成] 共调用 ");
        sb.append(result.getTotalToolCalls()).append(" 次工具（");
        boolean hasPrev = false;
        if (result.getWriteFileCount() > 0) { sb.append("写入 ").append(result.getWriteFileCount()); hasPrev = true; }
        if (result.getModifyFileCount() > 0) { if (hasPrev) sb.append("，"); sb.append("修改 ").append(result.getModifyFileCount()); hasPrev = true; }
        if (result.getDeleteFileCount() > 0) { if (hasPrev) sb.append("，"); sb.append("删除 ").append(result.getDeleteFileCount()); hasPrev = true; }
        if (result.getDirReadCount() > 0) { if (hasPrev) sb.append("，"); sb.append("读取目录 ").append(result.getDirReadCount()); }
        sb.append("）");
        return sb.toString();
    }

    public String extractNaturalLanguage(String message) {
        if (StrUtil.isBlank(message)) return "";
        SummaryResult result = parseAiMessage(message);
        List<String> chunks = result.getNaturalLanguageChunks();
        return (chunks == null || chunks.isEmpty()) ? "" : String.join("\n", chunks);
    }

    public SummaryCacheEntry getOrCompute(Long appId, Long historyId, String message) {
        Map<Long, SummaryCacheEntry> appCache = summaryCache.get(appId);
        if (appCache != null) {
            SummaryCacheEntry entry = appCache.get(historyId);
            if (entry != null) return entry;
        }
        SummaryResult result = parseAiMessage(message);
        String summaryText = buildUserSummary(result);
        String nlText = result.getNaturalLanguageChunks() != null
                ? String.join("\n", result.getNaturalLanguageChunks()) : "";
        List<ToolCallInfo> toolCalls = new ArrayList<>();
        SummaryCacheEntry entry = new SummaryCacheEntry(summaryText, nlText, toolCalls);
        Map<Long, SummaryCacheEntry> cacheMap = appCache;
        if (cacheMap == null) { cacheMap = new ConcurrentHashMap<>(); summaryCache.put(appId, cacheMap); }
        cacheMap.put(historyId, entry);
        return entry;
    }

    public void invalidateCache(Long appId) {
        if (appId != null) { summaryCache.remove(appId); log.debug("AI 消息摘要缓存已失效，appId={}", appId); }
    }
}
