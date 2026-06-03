package com.dbts.glyahhaigeneratecode.model.DTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * memory_shrink 中唯一的 conversation_summary 对（USER + AI 摘要）。
 *
 * @param userSummary           用户侧摘要
 * @param aiSummary             AI 侧摘要
 * @param anchorCreateTime      时间轴锚点（取最早合并轮）
 * @param sourceChatHistoryIds  已纳入摘要的 chat_history id 列表
 */
public record ConversationSummaryPair(
        String userSummary,
        String aiSummary,
        LocalDateTime anchorCreateTime,
        List<Long> sourceChatHistoryIds
) {
}
