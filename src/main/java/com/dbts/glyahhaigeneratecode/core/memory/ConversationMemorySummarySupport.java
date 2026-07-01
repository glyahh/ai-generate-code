package com.dbts.glyahhaigeneratecode.core.memory;

import com.dbts.glyahhaigeneratecode.mapper.ChatHistoryMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.mybatisflex.core.query.QueryWrapper;

/**
 * 会话软/硬摘要评估工具。
 * <p>仅写入 conversation_memory_state / cm:state，下游
 * {@link ConversationMemoryStateService#loadConversationMemoryStateAndInject}
 * 尚未将摘要注入模型。</p>
 */
public final class ConversationMemorySummarySupport {

    /** 触发硬摘要的用户轮数阈值。 */
    public static final long HARD_SUMMARY_USER_THRESHOLD = 24L;

    /** 触发软摘要的用户轮数阈值。 */
    public static final long SOFT_SUMMARY_USER_THRESHOLD = 12L;

    private ConversationMemorySummarySupport() {
    }

    /**
     * 摘要包。
     */
    public record SummaryBundle(String level, String softSummary, String hardSummary) {
    }

    /**
     * 根据 chat_history 中 USER 消息条数评估软/硬摘要。
     *
     * @param appId  应用 id
     * @param mapper 对话历史 Mapper
     * @return 摘要包
     */
    public static SummaryBundle buildSummaryBundle(Long appId, ChatHistoryMapper mapper) {
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(ChatHistory::getAppId, appId);
        queryWrapper.eq(ChatHistory::getMessageType, ChatHistoryMessageTypeEnum.USER.getValue());
        queryWrapper.eq(ChatHistory::getIsDelete, 0);
        long count = mapper.selectCountByQuery(queryWrapper);
        if (count >= HARD_SUMMARY_USER_THRESHOLD) {
            return new SummaryBundle("hard",
                    "最近高频编辑阶段，建议优先依据 changedFiles 与报错定位做增量修改。",
                    "会话较长：建议按 changedFiles 优先读取并逐页 readFile。");
        }
        if (count >= SOFT_SUMMARY_USER_THRESHOLD) {
            return new SummaryBundle("soft",
                    "会话进入中长上下文阶段，建议优先按最新变更文件定位。",
                    null);
        }
        return new SummaryBundle("none", null, null);
    }
}
