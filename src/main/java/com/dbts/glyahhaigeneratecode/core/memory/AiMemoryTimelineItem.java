package com.dbts.glyahhaigeneratecode.core.memory;

import java.time.LocalDateTime;

/**
 * AI memory rebuild timeline item.
 */
final class AiMemoryTimelineItem {
    final LocalDateTime sortTime;
    final String messageType;
    final String message;
    final Long chatHistoryId;
    final Long userId;
    final Long loopId;
    final boolean fromShrink;

    AiMemoryTimelineItem(LocalDateTime sortTime, String messageType, String message,
                         Long chatHistoryId, Long userId, Long loopId, boolean fromShrink) {
        this.sortTime = sortTime;
        this.messageType = messageType;
        this.message = message;
        this.chatHistoryId = chatHistoryId;
        this.userId = userId;
        this.loopId = loopId;
        this.fromShrink = fromShrink;
    }
}
