package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.constant.ChatHistoryConstant;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话历史服务相关测试（不启动 Spring 容器，避免依赖 Redis/DB）。
 * 合并与 Redis 重建的集成测试可在本地启动 Redis + MySQL 后，通过实际对话或单独调用
 * {@link ChatHistoryService#trySummarizeOldestRoundsIfNeeded(Long, Long)} 验证。
 */
class ChatHistoryServiceImplTest {

    /**
     * 常量与压缩策略一致：超过 {@link ChatHistoryConstant#MAX_ROUNDS_BEFORE_SUMMARY} 轮触发合并，每次合并 2 轮为 1 轮。
     */
    @Test
    void summaryConstants_consistentWithMergeLogic() {
        assertTrue(ChatHistoryConstant.MAX_ROUNDS_BEFORE_SUMMARY >= 1);
        assertTrue(ChatHistoryConstant.ROUNDS_TO_MERGE == 2);
        assertTrue(ChatHistoryConstant.MESSAGES_PER_MERGE == 4);
        assertTrue(ChatHistoryConstant.CHAT_MEMORY_MAX_MESSAGES >= ChatHistoryConstant.MEMORY_PRELOAD_MESSAGE_ROWS);
    }
}
