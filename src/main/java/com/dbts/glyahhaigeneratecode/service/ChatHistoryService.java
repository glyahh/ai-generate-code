package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.DTO.ChatHistoryQueryRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
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
     * 分页查询某个应用的对话历史（基于时间游标，向前加载）
     *
     * @param appId          应用 id
     * @param pageSize       每次加载条数
     * @param lastCreateTime 游标时间（传 null 加载最新的，传具体时间加载早于该时间的消息）
     * @param loginUser      当前登录用户（用于权限校验）
     * @return 分页结果
     */
    Page<ChatHistory> listAppChatHistoryByPage(Long appId, int pageSize, LocalDateTime lastCreateTime, User loginUser);

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
     * 当对话轮数超过 {@link com.dbts.glyahhaigeneratecode.constant.ChatHistoryConstant#MAX_ROUNDS_BEFORE_SUMMARY} 时，
     * 将最早的两轮用 AI 总结为一轮，并仅在 Redis 中用 2 条摘要消息替换前 4 条原始消息，用于压缩上下文（不修改 DB）。
     * 由对话完成后（如流式回复 doOnComplete）调用，无需权限校验（内部按 appId 操作）。
     *
     * @param appId  应用 id
     * @param userId 用户 id（用于写入总结记录的 userId）
     */
    void trySummarizeOldestRoundsIfNeeded(Long appId, Long userId);

    /**
     * 在线压缩 Redis ChatMemory 中的超长历史 AI 消息（仅影响模型上下文，不改 DB 历史文本）。
     * 主要用于 HTML / MULTI_FILE 场景下，缓存命中时也能降低后续请求 token。
     *
     * @param appId           应用 id
     * @param codeGenTypeEnum 代码生成类型
     */
    void compactMemoryMessagesIfNeeded(Long appId, CodeGenTypeEnum codeGenTypeEnum);
}
