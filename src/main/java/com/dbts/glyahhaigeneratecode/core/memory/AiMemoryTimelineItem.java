package com.dbts.glyahhaigeneratecode.core.memory;

import java.time.LocalDateTime;

/** AI 重建时间轴上的单条消息视图（summary 行无 chatHistoryId） */
final class AiMemoryTimelineItem {
    final LocalDateTime sortTime;
    final String messageType;
    final String message;
    final Long chatHistoryId;
    final Long userId;
    final boolean fromShrink;

    /**
     * 构造时间轴消息项，统一承载 summary 与 chat_history 两类来源字段
     *
     * @param sortTime      用于排序的时间
     * @param messageType   消息类型（USER/AI）
     * @param message       消息正文
     * @param chatHistoryId chat_history 主键，summary 行为 null
     * @param userId        用户 id
     * @param fromShrink    是否来自 memory_shrink
     */
    AiMemoryTimelineItem(LocalDateTime sortTime, String messageType, String message,
                         Long chatHistoryId, Long userId, boolean fromShrink) {
        // 1. 写入时间轴条目的全部只读字段，供外部按统一结构排序与重建
        this.sortTime = sortTime;
        this.messageType = messageType;
        this.message = message;
        this.chatHistoryId = chatHistoryId;
        this.userId = userId;
        this.fromShrink = fromShrink;
    }
}
