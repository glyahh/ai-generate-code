package com.dbts.glyahhaigeneratecode.constant;

/**
 * 对话历史相关常量（含超长对话压缩策略）
 */
public interface ChatHistoryConstant {

    /**
     * Redis 中用户消息条数（轮数）超过该阈值后，启动「最早两轮合并为一轮摘要」并写回 DB + 重建 Redis。
     * 与 {@link #CHAT_MEMORY_MAX_MESSAGES}、{@link #MEMORY_PRELOAD_MESSAGE_ROWS} 配合；阈值 3 表示约 3 轮用户发问后再触发合并。
     */
    int MAX_ROUNDS_BEFORE_SUMMARY = 3;

    /**
     * {@link dev.langchain4j.memory.chat.MessageWindowChatMemory} 窗口上限（条），含 user/ai/tool 等。
     */
    int CHAT_MEMORY_MAX_MESSAGES = 80;

    /**
     * 从 MySQL 预加载进 Redis 的最大条数（配合 {@code turnHistoryToMemory} 的 limit），宜大于「可见轮数×2」并预留 tool/error 占位。
     */
    int MEMORY_PRELOAD_MESSAGE_ROWS = 40;

    /**
     * 每次压缩时，将最早几轮合并为一轮（2 轮 → 1 轮，即 4 条消息 → 2 条）。
     */
    int ROUNDS_TO_MERGE = 2;

    /**
     * 合并时取的最早消息条数（2 轮 = 2 用户 + 2 AI = 4 条）。
     */
    int MESSAGES_PER_MERGE = ROUNDS_TO_MERGE * 2;
}
