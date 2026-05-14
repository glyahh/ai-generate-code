package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.VO.ConversationMemoryInjectResult;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

/**
 * 会话记忆状态服务。
 */
public interface ConversationMemoryStateService {

    /**
     * 轮次完成后更新 memory_state、ref、snapshot。
     *
     * @param appId           应用 id
     * @param roundId         轮次 id（chat_history.id）
     * @param userId          用户 id
     * @param codeGenTypeEnum 代码生成类型
     * @param workflowMode    是否 workflow
     * @param bufferChars     本轮输出字符数
     * @param elapsedMs       轮次耗时毫秒
     * @return 无
     */
    void onRoundCompleted(Long appId, Long roundId, Long userId, CodeGenTypeEnum codeGenTypeEnum, boolean workflowMode, int bufferChars, long elapsedMs);

    /**
     * 加载 memory_state 并执行按需文件注入。
     * 会话记忆核心
     *
     * @param appId           应用 id
     * @param chatMemory      聊天内存
     * @param codeGenTypeEnum 代码生成类型
     * @param maxCount        历史消息上限
     * @return 注入结果
     */
    ConversationMemoryInjectResult loadConversationMemoryStateAndInject(Long appId, MessageWindowChatMemory chatMemory, CodeGenTypeEnum codeGenTypeEnum, int maxCount);

    /**
     * 自动执行 ref/snapshot 清理任务, 不用外部调用
     *
     * @return 无
     */
    void cleanupMemoryRefsAndSnapshots();
}
