package com.dbts.glyahhaigeneratecode.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 对话历史常量测试，保证压缩策略参数与代码一致。
 */
class ChatHistoryConstantTest {

    @Test
    void maxRoundsBeforeSummary_isThreeSlotWindow() {
        assertEquals(3, ChatHistoryConstant.MAX_ROUNDS_BEFORE_SUMMARY);
    }

    @Test
    void targetUnmergedDbUserRounds_isTwo() {
        assertEquals(2, ChatHistoryConstant.TARGET_UNMERGED_DB_USER_ROUNDS);
    }

    @Test
    void roundsToMerge_is2() {
        assertEquals(2, ChatHistoryConstant.ROUNDS_TO_MERGE);
    }

    @Test
    void messagesPerMerge_is4() {
        assertEquals(4, ChatHistoryConstant.MESSAGES_PER_MERGE);
    }
}
