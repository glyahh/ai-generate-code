package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.DTO.ChatHistoryQueryRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.UserChatHistoryItemVO;
import com.dbts.glyahhaigeneratecode.model.VO.AppChatHistoryPageVO;
import com.dbts.glyahhaigeneratecode.model.VO.ChatHistoryVO;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话历史 服务层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
public interface ChatHistoryService extends IService<ChatHistory> {

    /**
     * 添加一条对话消息
     *
     * @param appId       应用 id
     * @param message     消息内容
     * @param messageType 消息类型（user/ai/error）
     * @param userId      用户 id
     * @return 是否保存成功
     */
    boolean addChatMessage(Long appId, String message, String messageType, Long userId);

    /**
     * 添加一条对话消息并返回主键 id（roundId 来源）
     *
     * @param appId       应用 id
     * @param message     消息内容
     * @param messageType 消息类型（user/ai/error）
     * @param userId      用户 id
     * @return 保存后的消息主键 id，失败返回 null
     */
    Long addChatMessageAndReturnId(Long appId, String message, String messageType, Long userId);

    /**
     * 添加一条带审查字段的对话消息
     *
     * @param appId        应用 id
     * @param message      消息内容
     * @param messageType  消息类型（user/ai/error）
     * @param userId       用户 id
     * @param auditAction  审查动作（ALLOW / REJECT / SKIP）
     * @param auditHitRule 命中的规则编码（如 NONE / SENSITIVE_WORD）
     * @return 是否保存成功
     */
    boolean addChatMessage(Long appId, String message, String messageType, Long userId, String auditAction, String auditHitRule);

    /**
     * 添加一条带审查字段的对话消息并返回主键 id（roundId 来源）
     *
     * @param appId        应用 id
     * @param message      消息内容
     * @param messageType  消息类型（user/ai/error）
     * @param userId       用户 id
     * @param auditAction  审查动作（ALLOW / REJECT / SKIP）
     * @param auditHitRule 命中的规则编码（如 NONE / SENSITIVE_WORD）
     * @return 保存后的消息主键 id，失败返回 null
     */
    Long addChatMessageAndReturnId(Long appId, String message, String messageType, Long userId, String auditAction, String auditHitRule);

    /**
     * 分页查询某个应用的对话历史（基于时间游标，向前加载）
     *
     * @param appId          应用 id
     * @param pageSize       每次加载条数
     * @param lastCreateTime 游标时间（传 null 加载最新的，传具体时间加载早于该时间的消息）
     * @param loginUser      当前登录用户（用于权限校验）
     * @return 分页结果
     */
    AppChatHistoryPageVO listAppChatHistoryByPage(Long appId, int pageSize, LocalDateTime lastCreateTime, User loginUser);

    /**
     * 【用户】分页查询当前登录用户的对话历史（支持 messageType/appId 筛选，按时间降序）
     *
     * @param queryRequest 查询请求（忽略其中的 userId）
     * @param loginUser    当前登录用户
     * @return 分页结果（带应用名称）
     */
    Page<UserChatHistoryItemVO> listMyChatHistoryByPage(ChatHistoryQueryRequest queryRequest, User loginUser);

    /**
     * 根据应用 id 删除所有对话历史（逻辑删除）
     *
     * @param appId 应用 id
     * @return 是否删除成功
     */
    boolean removeByAppId(Long appId);

    /**
     * 构建 QueryWrapper
     *
     * @param queryRequest 查询请求
     * @return QueryWrapper
     */
    QueryWrapper buildQueryWrapper(ChatHistoryQueryRequest queryRequest);

    /**
     * 将实体转换为 VO
     *
     * @param chatHistory 对话历史实体
     * @return 对话历史 VO
     */
    ChatHistoryVO getChatHistoryVO(ChatHistory chatHistory);

    /**
     * 批量将实体转换为 VO
     *
     * @param chatHistoryList 对话历史实体列表
     * @return 对话历史 VO 列表
     */
    List<ChatHistoryVO> getChatHistoryVOList(List<ChatHistory> chatHistoryList);

    /**
     * 将对话历史转为内存
     *
     * @param addId                   应用 id
     * @param messageWindowChatMemory 聊天内存
     * @param maxCount                最大数量
     * @return 转换后的数量
     */
    int turnHistoryToMemory (Long addId, MessageWindowChatMemory messageWindowChatMemory, int maxCount);

    /**
     * 加载会话 memory_state 并按需注入文件内容到 Redis ChatMemory
     *
     * @param appId                   应用 id
     * @param messageWindowChatMemory 聊天内存
     * @param maxCount                最大历史条数
     * @param codeGenTypeEnum         代码生成类型
     * @return 注入后的内存消息条数
     */
    int loadConversationMemoryStateAndInject(Long appId, MessageWindowChatMemory messageWindowChatMemory, int maxCount, CodeGenTypeEnum codeGenTypeEnum);

    /**
     * 查询某应用全部对话历史（用于导出到本地，仅应用创建者或管理员可调用）
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户（用于权限校验）
     * @return 按创建时间升序的对话历史 VO 列表
     */
    List<ChatHistoryVO> listAllByAppIdForExport(Long appId, User loginUser);

    /**
     * 统计某应用的对话轮数（用户一问 + AI 一答为一轮，按用户消息条数统计）
     * 仅应用创建者或管理员可调用。
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户（用于权限校验）
     * @return 对话轮数，非负整数
     */
    int countRoundsByAppId(Long appId, User loginUser);

    /**
     * 当 DB 中用户轮数超过 {@link com.dbts.glyahhaigeneratecode.constant.ChatHistoryConstant#MAX_ROUNDS_BEFORE_SUMMARY} 时，
     * 将最早两轮（4 条）用 AI 总结为 1 轮摘要：逻辑删除原文、插入 2 条带 audit 的摘要行，并在全部合并完成后按 DB 重建 Redis ChatMemory。
     * 由对话入口或流式完成后调用，无需权限校验（内部按 appId 操作）。
     *
     * @param appId  应用 id
     * @param userId 用户 id（用于写入总结记录的 userId）
     */
    default void trySummarizeOldestRoundsIfNeeded(Long appId, Long userId) {
        trySummarizeOldestRoundsIfNeeded(appId, userId, "unknown");
    }

    /**
     * 带触发来源标记的会话级总结压缩，便于线上定位由谁触发。
     *
     * @param appId         应用 id
     * @param userId        用户 id
     * @param triggerReason 触发来源（如 entry_normal / entry_workflow）
     */
    void trySummarizeOldestRoundsIfNeeded(Long appId, Long userId, String triggerReason);

    /**
     * 在线压缩 Redis ChatMemory 中的超长历史 AI 消息（仅影响模型上下文，不改 DB 历史文本）。
     * 主要用于 HTML / MULTI_FILE 场景下，缓存命中时也能降低后续请求 token。
     *
     * @param appId           应用 id
     * @param codeGenTypeEnum 代码生成类型
     */
    default void compactMemoryMessagesIfNeeded(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        compactMemoryMessagesIfNeeded(appId, codeGenTypeEnum, "unknown");
    }

    /**
     * 带触发来源标记的消息级在线截断压缩。
     *
     * @param appId           应用 id
     * @param codeGenTypeEnum 代码生成类型
     * @param triggerReason   触发来源（如 workflow_quality_pass / cache_hit）
     */
    void compactMemoryMessagesIfNeeded(Long appId, CodeGenTypeEnum codeGenTypeEnum, String triggerReason);

    /**
     * 刷新 AI ChatMemory Redis TTL（memoryId=appId）。
     *
     * @param appId 应用 id
     */
    void refreshAiChatMemoryTtl(Long appId);

    /**
     * 工作流重试前，定向清理 Redis ChatMemory 里上一轮失败生成的 AI 长消息（仅删失败产物，不清空整段会话）。
     *
     * @param appId           应用 id
     * @param codeGenTypeEnum 代码生成类型
     * @return 是否执行了删除
     */
    default boolean removeLatestFailedAiMessageForRetry(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        return removeLatestFailedAiMessageForRetry(appId, codeGenTypeEnum, "unknown");
    }

    /**
     * 带触发来源标记的失败轮无效产物清理。
     *
     * @param appId           应用 id
     * @param codeGenTypeEnum 代码生成类型
     * @param triggerReason   触发来源（如 workflow_retry / workflow_retry_exhausted）
     * @return 是否执行了删除
     */
    boolean removeLatestFailedAiMessageForRetry(Long appId, CodeGenTypeEnum codeGenTypeEnum, String triggerReason);

    /**
     * 按 (appId, userId, message, type=USER) 删除最新一条用户消息，用于异常回滚。
     *
     * @param appId   应用 id
     * @param userId  用户 id
     * @param message 用户消息原文
     * @return 是否删除成功
     */
    boolean removeUserMessageByContent(Long appId, Long userId, String message);

    /**
     * 对话轮次结束后的收口：快照、差异、摘要状态、ref 归档与缓存回填
     *
     * @param appId           应用 id
     * @param roundId         本轮 roundId（即用户消息 chat_history.id）
     * @param userId          用户 id
     * @param codeGenTypeEnum 代码生成类型
     * @param workflowMode    是否 workflow 模式
     * @return 无
     */
    void onRoundCompleted(Long appId, Long roundId, Long userId, CodeGenTypeEnum codeGenTypeEnum, boolean workflowMode);


    /**
     * 对话轮次结束后的收口（带实时指标）。
     *
     * @param appId           应用 id
     * @param roundId         本轮 roundId（即用户消息 chat_history.id）
     * @param userId          用户 id
     * @param codeGenTypeEnum 代码生成类型
     * @param workflowMode    是否 workflow 模式
     * @param bufferChars     本轮输出字符数
     * @param elapsedMs       本轮耗时（毫秒）
     * @return 无
     */
    default void onRoundCompleted(Long appId, Long roundId, Long userId, CodeGenTypeEnum codeGenTypeEnum,
                                  boolean workflowMode, int bufferChars, long elapsedMs) {
        onRoundCompleted(appId, roundId, userId, codeGenTypeEnum, workflowMode);
    }

    /**
     * workflow 入口是否应触发会话级总结压缩。
     * 仅在“非首轮 + 上一轮 workflow 成功”场景返回 true。
     *
     * @param appId 应用 id
     * @return 是否触发会话级总结
     */
    boolean shouldSummarizeBeforeWorkflowGeneration(Long appId);
}
