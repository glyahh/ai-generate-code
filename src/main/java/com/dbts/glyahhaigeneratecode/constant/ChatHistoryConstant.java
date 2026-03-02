package com.dbts.glyahhaigeneratecode.constant;

/**
 * 对话历史相关常量（含超长对话压缩策略）
 */
public interface ChatHistoryConstant {

    /**
     * 超过该轮数后启动「前两轮合并为一轮」的压缩机制，避免 Redis 超限并节省 Token。
     * 与 Redis maxMessages(25) 配合：预留系统提示词等空间，实际有效轮数控制在此以内。
     */
    int MAX_ROUNDS_BEFORE_SUMMARY = 20;

    /**
     * 每次压缩时，将最早几轮合并为一轮（2 轮 → 1 轮，即 4 条消息 → 2 条）。
     */
    int ROUNDS_TO_MERGE = 2;

    /**
     * 合并时取的最早消息条数（2 轮 = 2 用户 + 2 AI = 4 条）。
     */
    int MESSAGES_PER_MERGE = ROUNDS_TO_MERGE * 2;
}
