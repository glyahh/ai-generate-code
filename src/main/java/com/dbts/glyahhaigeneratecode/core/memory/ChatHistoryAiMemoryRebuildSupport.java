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
import com.dbts.glyahhaigeneratecode.service.UserPersonalizationService;
import com.dbts.glyahhaigeneratecode.service.support.LoopInjectService;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
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

/**
 * 从 memory_shrink + chat_history 组装 AI 侧 LangChain4j ChatMemory。
 * <p>
 * Redis miss 时读 shrink 摘要 + 未合并 chat_history 原文 -> 按时间轴合并 -> 写入 Redis 并刷新 TTL。
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
    private ChatAiMemoryRedisSupport chatAiMemoryRedisSupport;

    @Resource
    private UserPersonalizationService userPersonalizationService;

    @Resource
    private LoopInjectService loopInjectService;

    /**
     * Redis miss 时从 shrink + chat_history 重建 AI ChatMemory 并刷新 TTL。
     * <p>
     * 组装时间轴 -> clear 窗口 -> 注入会话级风格 SystemMessage（不计入 restored） -> 逐条回填 USER/AI 消息 -> refresh TTL。
     *
     * @param appId                   应用 id
     * @param messageWindowChatMemory 目标 LangChain4j 内存窗口
     * @param maxCount                预加载最大条数
     * @param codeGenTypeEnum         代码生成类型
     * @return 写入 ChatMemory 的（非 SystemMessage）消息条数
     */
    public int rebuildAiChatMemoryFromShrink(Long appId, MessageWindowChatMemory messageWindowChatMemory,
                                             int maxCount, CodeGenTypeEnum codeGenTypeEnum) {
        if (appId == null || appId <= 0 || messageWindowChatMemory == null || maxCount <= 0) {
            return 0;
        }
        Set<Long> mergedSourceIds = memoryShrinkService.collectAllMergedSourceChatHistoryIds(appId);
        List<AiMemoryTimelineItem> timeline = buildAiTimeline(appId, mergedSourceIds, maxCount);
        if (timeline.isEmpty()) {
            return 0;
        }

        CodeGenTypeEnum appCodeGenType = resolveAppCodeGenType(appId, codeGenTypeEnum);
        String systemPromptForFilter = resolveSystemPromptFilter(appCodeGenType);

        messageWindowChatMemory.clear();

        // 从时间轴尾部取最近非 null userId，用于构建会话级风格 SystemMessage
        Long sessionUserId = null;
        for (int i = timeline.size() - 1; i >= 0; i--) {
            if (timeline.get(i).userId != null) {
                sessionUserId = timeline.get(i).userId;
                break;
            }
        }
        // 在 clear 之后、对话消息之前注入风格 SystemMessage，保证它对后续消息生效
        String sessionStyle = buildSessionStyleMessage(sessionUserId);
        if (StrUtil.isNotBlank(sessionStyle)) {
            messageWindowChatMemory.add(SystemMessage.from(sessionStyle));
            // 风格注入不记入 restored（restored 仅统计实际对话消息条数）
        }

        // 按时间轴顺序回填 USER/AI 消息
        int restored = 0;
        for (int i = 0; i < timeline.size(); i++) {
            AiMemoryTimelineItem item = timeline.get(i);
            if (ChatHistoryMessageTypeEnum.USER.getValue().equals(item.messageType)) {
                // shrink 摘要行原样填入；非 shrink 行用 XML 包裹，标明原始用户输入
                String userMsg = item.fromShrink ? item.message : MemoryMessageXmlSupport.wrapUserOriginal(item.message);
                if (!item.fromShrink && item.loopId != null) {
                    userMsg = injectLoopSkillForRebuild(userMsg, item.userId, appId, item.loopId);
                }
                messageWindowChatMemory.add(new UserMessage(userMsg));
                restored++;
                continue;
            }
            if (ChatHistoryMessageTypeEnum.AI.getValue().equals(item.messageType)) {
                // 跳过与系统 Prompt 完全一致的 AI 消息——它被 LangChain4j 视为重复注入
                if (StrUtil.isNotBlank(systemPromptForFilter)
                        && StrUtil.isNotBlank(item.message)
                        && item.message.trim().equals(systemPromptForFilter.trim())) {
                    continue;
                }
                // 空 AI 消息用占位符，避免 AiMessage(null) 被 LangChain4j 拒绝
                String safeAi = ChatHistorySchemaMigrationSupport.compactAiMessageForMemory(item.message, appCodeGenType);
                messageWindowChatMemory.add(new AiMessage(safeAi));
                restored++;
            }
        }

        chatAiMemoryRedisSupport.refreshAiMemoryTtl(appId);
        log.info("AI 记忆已从 shrink+chat_history 重建，appId={}, restored={}", appId, restored);
        return restored;
    }

    /**
     * 合并 shrink 摘要对 + 未合并 chat_history，截取最近 maxCount 条时间轴项。
     * <p>
     * 注意：shrink 摘要行无 chatHistoryId，排序时 through 与原文同时间锚点但优先靠前（fromShrink=0），
     * 保证同一轮内摘要替代原文进入前缀而不额外占用窗口位置。
     *
     * @param appId           应用 id
     * @param mergedSourceIds 已纳入 summary 的 chat_history id
     * @param maxCount        条数上限
     * @return 按时间排序后的时间轴项
     */
    private List<AiMemoryTimelineItem> buildAiTimeline(Long appId, Set<Long> mergedSourceIds, int maxCount) {
        List<AiMemoryTimelineItem> items = new ArrayList<>();

        // 将 conversation_summary 折叠为唯一一对时间轴项（USER + AI 各一条摘要）
        // 防止 ConversationSummaryPair 为 null
        Optional<ConversationSummaryPair> summaryPair = memoryShrinkService.getConversationSummaryPair(appId);
        summaryPair.ifPresent(pair -> {
            items.add(new AiMemoryTimelineItem(
                    pair.anchorCreateTime(),
                    ChatHistoryMessageTypeEnum.USER.getValue(),
                    pair.userSummary(),
                    null,
                    null,
                    null,
                    true));
            items.add(new AiMemoryTimelineItem(
                    pair.anchorCreateTime(),
                    ChatHistoryMessageTypeEnum.AI.getValue(),
                    pair.aiSummary(),
                    null,
                    null,
                    null,
                    true));
        });

        // 查询未合并 chat_history（保留全部未合并轮，上限 MEMORY_PRELOAD）
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
                items.add(new AiMemoryTimelineItem(
                        h.getCreateTime(),
                        h.getMessageType(),
                        h.getMessage(),
                        h.getId(),
                        h.getUserId(),
                        h.getLoopId(),
                        false));
            }
        }

        // 统一排序。条数超过预加载上限时截断最旧部分，避免丢掉「上一轮完整」
        items.sort(Comparator.comparing((AiMemoryTimelineItem o) -> o.sortTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(o -> o.fromShrink ? 0 : 1));
        if (items.size() <= dbRowLimit) {
            return items;
        }
        return new ArrayList<>(items.subList(items.size() - dbRowLimit, items.size()));
    }

    /**
     * 解析应用的 codeGenType。非 null 的入参优先，否则回查 app 表。
     *
     * @param appId    应用 id
     * @param fallback 调用方传入的类型
     * @return 代码生成类型，无法解析时返回 null
     */
    private CodeGenTypeEnum resolveAppCodeGenType(Long appId, CodeGenTypeEnum fallback) {
        if (fallback != null) {
            return fallback;
        }
        try {
            App app = appService.getById(appId);
            if (app != null) {
                return CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
            }
        } catch (Exception ignored) {
            // 查表失败返回 null，调用方自行兜底
        }
        return null;
    }

    /**
     * 读取与 codeGenType 对应的系统提示词文本，用于过滤重复 AI 行。
     * 无对应类型或读失败时返回 null，跳过过滤。
     *
     * @param codeGenTypeEnum 代码生成类型，决定映射哪个系统提示词文件
     * @return 系统提示词全文，无可读文件时返回 null
     */
    private String resolveSystemPromptFilter(CodeGenTypeEnum codeGenTypeEnum) {
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
        try {
            return ChatHistorySchemaMigrationSupport.readPromptFromClasspath(promptPath);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构建会话级风格 SystemMessage：组装 &lt;inject_prompt&gt; + &lt;user_style&gt; XML 块。
     * <p>
     * 该消息在 clear 之后、对话消息之前注入，LangChain4j 保证它对后续所有对话生效。
     *
     * @param userId 用户 id
     * @return 风格 XML 文本，无有效风格时返回 null
     */
    private String buildSessionStyleMessage(Long userId) {
        if (userId == null || userId <= 0) return null;
        try {
            String appStyle = userPersonalizationService.getCachedAppStyle(userId);
            String answerStyle = userPersonalizationService.getCachedAnswerStyle(userId);
            boolean hasApp = StrUtil.isNotBlank(appStyle);
            boolean hasAns = StrUtil.isNotBlank(answerStyle);
            if (!hasApp && !hasAns) return null;
            String injectMeta = MemoryMessageXmlSupport.buildInjectPromptMeta();
            String styleBlock = MemoryMessageXmlSupport.buildUserStyleBlock(appStyle, answerStyle);
            return injectMeta + "\n" + styleBlock;
        } catch (Exception e) {
            log.warn("读取用户风格失败，跳过会话级注入，userId={}", userId, e);
            return null;
        }
    }

    private String injectLoopSkillForRebuild(String userMsg, Long userId, Long appId, Long loopId) {
        try {
            return loopInjectService.injectIfPresent(userMsg, userId, appId, loopId);
        } catch (Exception e) {
            log.warn("閲嶅缓 AI 璁板繂鏃?Loop 娉ㄥ叆澶辫触锛屽凡璺宠繃锛宎ppId={}, userId={}, loopId={}",
                    appId, userId, loopId, e);
            return userMsg;
        }
    }
}
