package com.dbts.glyahhaigeneratecode.constant;

/**
 * 对话历史相关常量（含超长对话压缩策略）
 */
public interface ChatHistoryConstant {

    /**
     * Redis 中用户消息条数（轮数）超过该阈值后，启动「最早两轮合并为一轮摘要」并写回 Redis（不删 DB 原文）。
     * 与 {@code MessageWindowChatMemory#maxMessages(80)} 配合：控制上下文膨胀；阈值 3 表示最多保留约 3 轮用户发问后再触发合并。
     */
    int MAX_ROUNDS_BEFORE_SUMMARY = 3;

    /**
     * 每次压缩时，将最早几轮合并为一轮（2 轮 → 1 轮，即 4 条消息 → 2 条）。
     */
    int ROUNDS_TO_MERGE = 2;

    /**
     * 合并时取的最早消息条数（2 轮 = 2 用户 + 2 AI = 4 条）。
     */
    int MESSAGES_PER_MERGE = ROUNDS_TO_MERGE * 2;
}
