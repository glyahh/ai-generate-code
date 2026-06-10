package com.dbts.glyahhaigeneratecode.core.memory;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.constant.ChatHistoryConstant;
import com.dbts.glyahhaigeneratecode.core.support.ChatHistorySchemaMigrationSupport;
import com.dbts.glyahhaigeneratecode.mapper.ChatHistoryMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.model.DTO.ConversationSummaryPair;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.MemoryShrinkService;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.dbts.glyahhaigeneratecode.constant.ChatHistoryMemoryCompactionConstant.EMPTY_AI_MEMORY_PLACEHOLDER;

/**
 * 从 memory_shrink + chat_history 组装 AI 侧 LangChain4j ChatMemory。
 * <p>
 * 读 shrink 摘要/截断 + 未合并 chat_history 原文 -> 按时间轴合并 -> compact 非主 AI -> 写入 Redis 并 upsert truncate
 */
@Component
@Slf4j
public class ChatHistoryAiMemoryRebuildSupport {

    @Resource
    private MemoryShrinkService memoryShrinkService;

    @Resource
    private ChatHistoryMapper chatHistoryMapper;

    @Resource
    @Lazy
    private AppService appService;

    @Resource
    private ChatMemoryStore chatMemoryStore;

    @Resource
    private ChatAiMemoryRedisSupport chatAiMemoryRedisSupport;

    /**
     * 判断 LangChain4j Redis 中是否已有该应用的 AI 压缩上下文
     *
     * @param appId 应用 id
     * @return true 表示 Redis hit，可跳过 DB 重建
     */
    public boolean hasAiMemoryInRedis(Long appId) {
        // 方法大纲：
        // 1. 校验 appId 基础合法性，避免无效 key 查询 Redis
        // 2. 读取 LangChain4j 存储中的消息列表并判断是否命中
        // 3. 兜底捕获异常并按 miss 返回 false

        // 1. 校验 appId 基础合法性，避免无效 key 查询 Redis
        if (appId == null || appId <= 0) {
            return false;
        }
        try {
            // 2. 读取 LangChain4j 存储中的消息列表并判断是否命中
            List<ChatMessage> messages = chatMemoryStore.getMessages(appId);
            return messages != null && !messages.isEmpty();
        } catch (Exception e) {
            // 3. 兜底捕获异常并按 miss 返回 false
            return false;
        }
    }

    /**
     * Redis miss 时从 shrink + chat_history 重建 AI ChatMemory 并刷新 TTL
     *
     * @param appId                   应用 id
     * @param messageWindowChatMemory 目标 LangChain4j 内存窗口
     * @param maxCount                预加载最大条数
     * @param codeGenTypeEnum         代码生成类型（决定 compact 与豁免规则）
     * @return 写入 ChatMemory 的消息条数
     */
    public int rebuildAiChatMemoryFromShrink(Long appId, MessageWindowChatMemory messageWindowChatMemory,
                                             int maxCount, CodeGenTypeEnum codeGenTypeEnum) {
        // 方法大纲：
        // 1. 校验入参并基于 shrink + chat_history 组装时间轴
        // 2. 解析 codeGenType 与系统提示词，准备主 AI 豁免规则
        // 3. 逐条回填 User/AI 消息到窗口，并按需回写 message_truncate
        // 4. 刷新 Redis TTL 并返回恢复条数

        // 1. 校验入参并基于 shrink + chat_history 组装时间轴
        if (appId == null || appId <= 0 || messageWindowChatMemory == null || maxCount <= 0) {
            return 0;
        }
        // 1. 收集已合并 id，组装 shrink + 未合并 chat_history 时间轴
        Set<Long> mergedSourceIds = memoryShrinkService.collectAllMergedSourceChatHistoryIds(appId);
        List<AiMemoryTimelineItem> timeline = buildAiTimeline(appId, mergedSourceIds, maxCount);
        if (timeline.isEmpty()) {
            return 0;
        }

        // 2. 解析 codeGenType 与系统提示词，准备主 AI 豁免规则
        CodeGenTypeEnum appCodeGenType = resolveAppCodeGenType(appId, codeGenTypeEnum);
        String systemPromptForFilter = resolveSystemPromptFilter(appCodeGenType);

        messageWindowChatMemory.clear();

        // 3. 逐条回填 User/AI 消息到窗口（不再 compact / 不再 upsert message_truncate）
        int restored = 0;
        Long userIdForTruncate = null;
        for (int i = 0; i < timeline.size(); i++) {
            AiMemoryTimelineItem item = timeline.get(i);
            if (item.userId != null) {
                userIdForTruncate = item.userId;
            }
            if (ChatHistoryMessageTypeEnum.USER.getValue().equals(item.messageType)) {
                messageWindowChatMemory.add(new UserMessage(item.message));
                restored++;
                continue;
            }
            if (ChatHistoryMessageTypeEnum.AI.getValue().equals(item.messageType)) {
                if (StrUtil.isNotBlank(systemPromptForFilter)
                        && StrUtil.isNotBlank(item.message)
                        && item.message.trim().equals(systemPromptForFilter.trim())) {
                    continue;
                }
                String safeAi = StrUtil.blankToDefault(item.message, EMPTY_AI_MEMORY_PLACEHOLDER);
                messageWindowChatMemory.add(new AiMessage(safeAi));
                restored++;
            }
        }
        // 4. 刷新 Redis TTL 并返回恢复条数
        chatAiMemoryRedisSupport.refreshAiMemoryTtl(appId);
        log.info("AI 记忆已从 shrink+chat_history 重建，appId={}, restored={}", appId, restored);
        return restored;
    }

    /**
     * 合并 shrink 摘要行、truncate 映射与未合并 chat_history，截取最近 maxCount 条时间轴项
     *
     * @param appId           应用 id
     * @param mergedSourceIds 已纳入 summary 的 chat_history id
     * @param maxCount        条数上限
     * @return 按时间排序后的时间轴项
     */
    private List<AiMemoryTimelineItem> buildAiTimeline(Long appId, Set<Long> mergedSourceIds, int maxCount) {
        // 方法大纲：
        // 1. 读取 shrink 行并提取 AI 截断映射
        // 2. 将 conversation_summary 转为时间轴项
        // 3. 查询未合并 chat_history 并与截断映射合流
        // 4. 统一排序后截取最近 maxCount 条

        // 1. 不再读取 MESSAGE_TRUNCATE 截断版
        List<AiMemoryTimelineItem> items = new ArrayList<>();
        // 2. 将 conversation_summary 折叠为唯一一对时间轴项
        Optional<ConversationSummaryPair> summaryPair = memoryShrinkService.getConversationSummaryPair(appId);
        // 如果summaryPair不为空指针的话
        summaryPair.ifPresent(pair -> {
            items.add(new AiMemoryTimelineItem(
                    pair.anchorCreateTime(),
                    ChatHistoryMessageTypeEnum.USER.getValue(),
                    pair.userSummary(),
                    null,
                    null,
                    true));
            items.add(new AiMemoryTimelineItem(
                    pair.anchorCreateTime(),
                    ChatHistoryMessageTypeEnum.AI.getValue(),
                    pair.aiSummary(),
                    null,
                    null,
                    true));
        });

        // 3. 查询未合并 chat_history 并与截断映射合流（保留全部未合并轮，上限 MEMORY_PRELOAD）
        int dbRowLimit = Math.max(maxCount, ChatHistoryConstant.MEMORY_PRELOAD_MESSAGE_ROWS);
        QueryWrapper q = new QueryWrapper();
        q.eq(ChatHistory::getAppId, appId);
        if (mergedSourceIds != null && !mergedSourceIds.isEmpty()) {
            q.notIn(ChatHistory::getId, mergedSourceIds);
        }
        q.orderBy(ChatHistory::getCreateTime, false);
        q.limit(dbRowLimit);
        List<ChatHistory> recent = chatHistoryMapper.selectListByQuery(q);
        if (recent != null) {
            for (int i = recent.size() - 1; i >= 0; i--) {
                ChatHistory h = recent.get(i);
                ChatHistoryMessageTypeEnum type = ChatHistoryMessageTypeEnum.getEnumByValue(h.getMessageType());
                if (type != ChatHistoryMessageTypeEnum.USER && type != ChatHistoryMessageTypeEnum.AI) {
                    continue;
                }
                String message = h.getMessage();
                items.add(new AiMemoryTimelineItem(
                        h.getCreateTime(),
                        h.getMessageType(),
                        message,
                        h.getId(),
                        h.getUserId(),
                        false));
            }
        }

        // 4. 统一排序；条数超过预加载上限时仅截断最旧部分，避免丢掉「上一轮完整」
        items.sort(Comparator.comparing((AiMemoryTimelineItem o) -> o.sortTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(o -> o.fromShrink ? 0 : 1));
        if (items.size() <= dbRowLimit) {
            return items;
        }
        // 超过预加载上限时仅截断最旧部分，避免丢掉「上一轮完整」
        return new ArrayList<>(items.subList(items.size() - dbRowLimit, items.size()));
    }

    /**
     * 在时间轴上定位「本轮主 AI」下标，压缩时保留全文
     *
     * @param timeline 时间轴项列表
     * @param type     代码生成类型
     * @return 应豁免 compact 的 AI 下标；-1 表示不豁免
     */
    private int indexExemptAiInTimeline(List<AiMemoryTimelineItem> timeline, CodeGenTypeEnum type) {
        // 方法大纲：
        // 1. 仅在 HTML/MULTI_FILE 场景启用主 AI 豁免
        // 2. 先逆序找到最后一个 USER 下标作为分界
        // 3. 在分界后的 AI 中选择消息最长的一条作为豁免项

        // 1. 仅在 HTML/MULTI_FILE 场景启用主 AI 豁免
        if (type != CodeGenTypeEnum.HTML && type != CodeGenTypeEnum.MULTI_FILE) {
            return -1;
        }
        // 2. 先逆序找到最后一个 USER 下标作为分界
        int lastUserIdx = -1;
        for (int i = timeline.size() - 1; i >= 0; i--) {
            if (ChatHistoryMessageTypeEnum.USER.getValue().equals(timeline.get(i).messageType)) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx < 0) {
            return -1;
        }
        // 3. 在分界后的 AI 中选择消息最长的一条作为豁免项
        int bestAiIdx = -1;
        int bestLen = -1;
        for (int i = lastUserIdx + 1; i < timeline.size(); i++) {
            if (!ChatHistoryMessageTypeEnum.AI.getValue().equals(timeline.get(i).messageType)) {
                continue;
            }
            int len = StrUtil.length(timeline.get(i).message);
            if (len > bestLen) {
                bestLen = len;
                bestAiIdx = i;
            }
        }
        return bestAiIdx;
    }

    /**
     * 解析应用的 codeGenType，入参优先于 DB
     *
     * @param appId    应用 id
     * @param fallback 调用方传入的类型，非 null 时直接返回
     * @return 代码生成类型，无法解析时可能为 null
     */
    private CodeGenTypeEnum resolveAppCodeGenType(Long appId, CodeGenTypeEnum fallback) {
        // 方法大纲：
        // 1. 优先使用调用方显式传入的 codeGenType
        // 2. fallback 为空时回查 app 表并解析枚举
        // 3. 查询异常或无结果时返回 null

        // 1. 优先使用调用方显式传入的 codeGenType
        if (fallback != null) {
            return fallback;
        }
        // 2. fallback 为空时回查 app 表并解析枚举
        try {
            App app = appService.getById(appId);
            if (app != null) {
                return CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
            }
        } catch (Exception ignored) {
            // 3. 查询异常或无结果时返回 null
            // ignore
        }
        return null;
    }

    /**
     * 读取与 codeGenType 对应的系统提示词文本，用于过滤重复 AI 行
     *
     * @param codeGenTypeEnum 代码生成类型
     * @return 系统提示词全文；无对应类型或读失败时返回 null
     */
    private String resolveSystemPromptFilter(CodeGenTypeEnum codeGenTypeEnum) {
        // 方法大纲：
        // 1. 校验 codeGenType 并映射对应提示词路径
        // 2. 路径无效时直接返回 null
        // 3. 从 classpath 读取提示词全文作为过滤基准

        // 1. 校验 codeGenType 并映射对应提示词路径
        if (codeGenTypeEnum == null) {
            return null;
        }
        String promptPath = switch (codeGenTypeEnum) {
            case HTML -> "Prompt/Single_File_Prompt.txt";
            case MULTI_FILE -> "Prompt/Various_File_Prompt.txt";
            case VUE -> "Prompt/Vue_File_Prompt.txt";
            default -> null;
        };
        if (StrUtil.isBlank(promptPath)) {
            return null;
        }
        // 3. 从 classpath 读取提示词全文作为过滤基准
        try {
            return ChatHistorySchemaMigrationSupport.readPromptFromClasspath(promptPath);
        } catch (Exception e) {
            return null;
        }
    }
}
