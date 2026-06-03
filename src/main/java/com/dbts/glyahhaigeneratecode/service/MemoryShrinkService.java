package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.Entity.MemoryShrink;
import com.dbts.glyahhaigeneratecode.model.DTO.ConversationSummaryPair;
import com.mybatisflex.core.service.IService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * memory_shrink 服务。
 * <p>
 * 会话压缩写 shrink 表 -> 聚合 sourceChatHistoryIds / 有效轮数 -> 供 AI Redis 重建与合并阈值判断
 */
public interface MemoryShrinkService extends IService<MemoryShrink> {

    /**
     * 幂等确保 memory_shrink 表存在，缺失时执行 DDL 创建
     */
    void ensureTableExists();

    /**
     * 将两轮合并摘要写入 shrink（USER+AI 各一行），并记录被合并的 chat_history id
     *
     * @param appId                 应用 id
     * @param userId                用户 id
     * @param anchorCreateTime      时间轴锚点（通常取最早一轮 createTime）
     * @param userSummary           用户侧摘要正文
     * @param aiSummary             AI 侧摘要正文
     * @param sourceChatHistoryIds  被合并的 chat_history 主键列表（通常 4 条）
     */
    void insertSummaryPair(Long appId, Long userId, LocalDateTime anchorCreateTime,
                           String userSummary, String aiSummary, List<Long> sourceChatHistoryIds);

    /**
     * 按 appId + chatHistoryId + shrinkType 幂等 upsert 单条 AI 截断结果
     *
     * @param appId            应用 id
     * @param userId           用户 id
     * @param chatHistoryId    源 chat_history 行 id
     * @param anchorCreateTime 时间轴锚点（源行 createTime）
     * @param message          截断后的 AI 正文
     * @param messageType      消息类型（通常为 ai）
     */
    void upsertMessageTruncate(Long appId, Long userId, Long chatHistoryId,
                               LocalDateTime anchorCreateTime, String message, String messageType);

    /**
     * 按 anchorCreateTime 升序列出该应用下全部 shrink 行
     *
     * @param appId 应用 id
     * @return 压缩行列表，无数据时为空列表
     */
    List<MemoryShrink> listByAppIdOrderByAnchorAsc(Long appId);

    /**
     * 汇总所有 conversation_summary 行关联的 chat_history id，用于排除已合并原文
     *
     * @param appId 应用 id
     * @return 已纳入 summary 的 chat_history id 集合
     */
    Set<Long> collectAllMergedSourceChatHistoryIds(Long appId);

    /**
     * 计算 AI 合并阈值用的有效 USER 轮数（summary 中 USER + 未合并的 chat_history USER）
     *
     * @param appId           应用 id
     * @param mergedSourceIds 已合并的 chat_history id 集合，可为空
     * @return 有效用户轮数
     */
    int countEffectiveUserRounds(Long appId, Set<Long> mergedSourceIds);

    /**
     * 统计 shrink 表中 conversation_summary 类型的 USER 条数
     *
     * @param appId 应用 id
     * @return summary USER 行数
     */
    int countSummaryUserRounds(Long appId);

    /**
     * 统计未合并进 summary 的 chat_history USER 条数
     *
     * @param appId           应用 id
     * @param mergedSourceIds 已合并的 chat_history id，可为 null
     * @return 未合并 USER 轮数
     */
    int countUnmergedDbUserRounds(Long appId, Set<Long> mergedSourceIds);

    /**
     * 读取 conversation_summary（若存在多对则打 warn 并折叠为一对视图）
     *
     * @param appId 应用 id
     * @return 摘要对，无 summary 时 empty
     */
    Optional<ConversationSummaryPair> getConversationSummaryPair(Long appId);

    /**
     * 删除该应用全部 conversation_summary 后写入唯一一对摘要
     *
     * @param appId                 应用 id
     * @param userId                用户 id
     * @param anchorCreateTime      时间轴锚点
     * @param userSummary           用户侧摘要
     * @param aiSummary             AI 侧摘要
     * @param sourceChatHistoryIds  被合并的 chat_history id 并集
     */
    void replaceConversationSummary(Long appId, Long userId, LocalDateTime anchorCreateTime,
                                    String userSummary, String aiSummary, List<Long> sourceChatHistoryIds);
}
