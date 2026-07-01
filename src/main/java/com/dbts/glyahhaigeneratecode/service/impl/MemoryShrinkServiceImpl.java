package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.mapper.ChatHistoryMapper;
import com.dbts.glyahhaigeneratecode.mapper.MemoryShrinkMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.Entity.MemoryShrink;
import com.dbts.glyahhaigeneratecode.model.DTO.ConversationSummaryPair;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.model.enums.MemoryShrinkTypeEnum;
import com.dbts.glyahhaigeneratecode.service.MemoryShrinkService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * memory_shrink 服务实现。
 * <p>
 * 校验/建表 -> 写入 summary 或 truncate 行 -> 查询聚合 sourceIds 与有效轮数 -> 供 ChatHistory 双轨压缩链路使用
 */
@Service
@Slf4j
public class MemoryShrinkServiceImpl extends ServiceImpl<MemoryShrinkMapper, MemoryShrink> implements MemoryShrinkService {

    private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
    };

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ChatHistoryMapper chatHistoryMapper;

    /**
     * 幂等确保 memory_shrink 表存在，缺失时执行 DDL 创建
     */
    @Override
    public void ensureTableExists() {
        // 1. 查 information_schema 判断表是否已存在
        Integer exists = this.getMapper().countMemoryShrinkTableExists();
        if (exists == null || exists <= 0) {
            // 2. 不存在则 CREATE TABLE IF NOT EXISTS，得到可写入 shrink 的表结构
            this.getMapper().createMemoryShrinkTableIfMissing();
            log.info("memory_shrink 表已创建或已存在");
        }
    }

    /**
     * 将两轮合并摘要写入 shrink（USER+AI 各一行），chat_history 原文不改动
     *
     * @param appId                 应用 id
     * @param userId                用户 id
     * @param anchorCreateTime      时间轴锚点
     * @param userSummary           用户侧摘要
     * @param aiSummary             AI 侧摘要
     * @param sourceChatHistoryIds  被合并的 chat_history id 列表
     */
    @Override
    public void insertSummaryPair(Long appId, Long userId, LocalDateTime anchorCreateTime,
                                  String userSummary, String aiSummary, List<Long> sourceChatHistoryIds) {
        // 方法大纲：
        // 1. 确保 memory_shrink 表存在并将 source id 序列化为 JSON
        // 2. 写入 USER/AI 各一行 conversation_summary

        // 1. 确保 memory_shrink 表存在并将 source id 序列化为 JSON
        ensureTableExists();
        LocalDateTime now = LocalDateTime.now();
        String sourceJson = writeSourceIdsJson(sourceChatHistoryIds);
        MemoryShrink userRow = MemoryShrink.builder()
                .appId(appId)
                .userId(userId)
                .message(userSummary)
                .messageType(ChatHistoryMessageTypeEnum.USER.getValue())
                .shrinkType(MemoryShrinkTypeEnum.CONVERSATION_SUMMARY.getValue())
                .sourceChatHistoryIds(sourceJson)
                .anchorCreateTime(anchorCreateTime)
                .createTime(anchorCreateTime)
                .updateTime(now)
                .build();
        MemoryShrink aiRow = MemoryShrink.builder()
                .appId(appId)
                .userId(userId)
                .message(aiSummary)
                .messageType(ChatHistoryMessageTypeEnum.AI.getValue())
                .shrinkType(MemoryShrinkTypeEnum.CONVERSATION_SUMMARY.getValue())
                .sourceChatHistoryIds(sourceJson)
                .anchorCreateTime(anchorCreateTime)
                .createTime(anchorCreateTime)
                .updateTime(now)
                .build();
        // 2. 先落 USER 摘要行再落 AI 摘要行，供时间轴重建时按 anchor 排序读取
        this.save(userRow);
        this.save(aiRow);
    }

    /**
     * 按 appId + chatHistoryId + message_truncate 幂等 upsert 单条 AI 截断结果
     *
     * @param appId            应用 id
     * @param userId           用户 id
     * @param chatHistoryId    源 chat_history 行 id
     * @param anchorCreateTime 时间轴锚点
     * @param message          截断后正文
     * @param messageType      消息类型
     */
    @Override
    public void upsertMessageTruncate(Long appId, Long userId, Long chatHistoryId,
                                      LocalDateTime anchorCreateTime, String message, String messageType) {
        // 方法大纲：
        // 1. 校验必填字段，按 appId + chatHistoryId + message_truncate 查是否已有行
        // 2. 存在则更新截断正文，不存在则 insert 新 shrink 行

        // 1. 校验必填字段，按 appId + chatHistoryId + message_truncate 查是否已有行
        if (appId == null || chatHistoryId == null || StrUtil.isBlank(message)) {
            return;
        }
        ensureTableExists();
        QueryWrapper q = new QueryWrapper();
        q.eq(MemoryShrink::getAppId, appId);
        q.eq(MemoryShrink::getChatHistoryId, chatHistoryId);
        q.eq(MemoryShrink::getShrinkType, MemoryShrinkTypeEnum.MESSAGE_TRUNCATE.getValue());
        MemoryShrink existing = this.getOne(q);
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            // 2. 命中已有行则更新 message 与锚点时间，避免同 chat_history 重复 insert
            existing.setMessage(message);
            existing.setMessageType(messageType);
            existing.setAnchorCreateTime(anchorCreateTime);
            existing.setUpdateTime(now);
            this.updateById(existing);
            return;
        }
        // 3. 无已有行时 insert 新 truncate 记录，sourceChatHistoryIds 仅含当前 chat_history id
        MemoryShrink row = MemoryShrink.builder()
                .appId(appId)
                .userId(userId)
                .message(message)
                .messageType(messageType)
                .shrinkType(MemoryShrinkTypeEnum.MESSAGE_TRUNCATE.getValue())
                .sourceChatHistoryIds(writeSourceIdsJson(List.of(chatHistoryId)))
                .chatHistoryId(chatHistoryId)
                .anchorCreateTime(anchorCreateTime)
                .createTime(anchorCreateTime)
                .updateTime(now)
                .build();
        this.save(row);
    }

    /**
     * 按 anchorCreateTime 升序列出该应用下全部 shrink 行
     *
     * @param appId 应用 id
     * @return 压缩行列表
     */
    @Override
    public List<MemoryShrink> listByAppIdOrderByAnchorAsc(Long appId) {
        // 方法大纲：
        // 1. 确保表存在后按 anchorCreateTime/id 升序列出该应用全部 shrink 行

        // 1. 确保表存在后按 anchorCreateTime/id 升序列出该应用全部 shrink 行
        ensureTableExists();
        QueryWrapper q = new QueryWrapper();
        q.eq(MemoryShrink::getAppId, appId);
        q.orderBy(MemoryShrink::getAnchorCreateTime, true);
        q.orderBy(MemoryShrink::getId, true);
        return this.list(q);
    }

    /**
     * 汇总所有 conversation_summary 关联的 chat_history id
     *
     * @param appId 应用 id
     * @return 已合并 id 集合
     */
    @Override
    public Set<Long> collectAllMergedSourceChatHistoryIds(Long appId) {
        // 方法大纲：
        // 1. 遍历 shrink 行，仅汇总 conversation_summary 的 sourceChatHistoryIds JSON

        // 1. 遍历 shrink 行，仅汇总 conversation_summary 的 sourceChatHistoryIds JSON
        List<MemoryShrink> rows = listByAppIdOrderByAnchorAsc(appId);
        Set<Long> ids = new HashSet<>();
        for (MemoryShrink row : rows) {
            if (!MemoryShrinkTypeEnum.CONVERSATION_SUMMARY.getValue().equals(row.getShrinkType())) {
                continue;
            }
            ids.addAll(parseSourceIds(row.getSourceChatHistoryIds()));
        }
        return ids;
    }

    /**
     * 计算有效 USER 轮数 = summary USER + 未合并的 chat_history USER
     *
     * @param appId           应用 id
     * @param mergedSourceIds 已合并 id 集合
     * @return 有效轮数
     */
    @Override
    public int countEffectiveUserRounds(Long appId, Set<Long> mergedSourceIds) {
        // 方法大纲：
        // 1. 统计 shrink 中 summary USER 条数
        // 2. 统计 chat_history 中排除已合并 id 后的 USER 条数并相加

        // 1. 统计 shrink 中 summary USER 条数
        int summaryUsers = countSummaryUserRounds(appId);
        // 2. 统计 chat_history 中排除已合并 id 后的 USER 条数并相加
        QueryWrapper q = new QueryWrapper();
        q.eq(ChatHistory::getAppId, appId);
        q.eq(ChatHistory::getMessageType, ChatHistoryMessageTypeEnum.USER.getValue());
        if (mergedSourceIds != null && !mergedSourceIds.isEmpty()) {
            q.notIn(ChatHistory::getId, mergedSourceIds);
        }
        int dbUsers = (int) chatHistoryMapper.selectCountByQuery(q);
        return summaryUsers + dbUsers;
    }

    /**
     * 统计 shrink 中 conversation_summary 的 USER 条数
     *
     * @param appId 应用 id
     * @return summary USER 行数
     */
    @Override
    public int countSummaryUserRounds(Long appId) {
        // 1. 确保表存在后按 shrinkType=conversation_summary 统计 USER 行数
        ensureTableExists();
        return this.getMapper().countUserRowsByShrinkType(appId, MemoryShrinkTypeEnum.CONVERSATION_SUMMARY.getValue());
    }

    /**
     * 统计 chat_history 中尚未纳入 memory_shrink 合并的 USER 条数
     *
     * @param appId           应用 id
     * @param mergedSourceIds 已合并进 summary 的 chat_history id 集合，可为 null
     * @return 未合并 USER 消息条数
     */
    @Override
    public int countUnmergedDbUserRounds(Long appId, Set<Long> mergedSourceIds) {
        // 方法大纲：
        // 1. 构造 USER 类型 count 查询，排除 mergedSourceIds 后返回条数

        // 1. 构造 USER 类型 count 查询，排除 mergedSourceIds 后返回条数
        QueryWrapper q = new QueryWrapper();
        q.eq(ChatHistory::getAppId, appId);
        q.eq(ChatHistory::getMessageType, ChatHistoryMessageTypeEnum.USER.getValue());
        if (mergedSourceIds != null && !mergedSourceIds.isEmpty()) {
            q.notIn(ChatHistory::getId, mergedSourceIds);
        }
        return (int) chatHistoryMapper.selectCountByQuery(q);
    }

    /**
     * 读取该应用折叠后的 conversation_summary 视图（最早一对 user/ai 摘要 + 全部已合并 source id）
     *
     * @param appId 应用 id
     * @return 存在有效摘要对时返回 Optional；无 summary 或 user/ai 不完整时 empty
     */
    @Override
    public Optional<ConversationSummaryPair> getConversationSummaryPair(Long appId) {
        // 方法大纲：
        // 1. 校验 summary USER 行数，多条时打 warn 仍按最早一对折叠
        // 2. 按 创建时间这里使用anchor锚点 升序扫描 shrink，取首对 user/ai 摘要与全部 merged source id

        // 1. 校验 summary USER 行数，多条时打 warn 仍按最早一对折叠
        ensureTableExists();
        int summaryUserRows = countSummaryUserRounds(appId);
        if (summaryUserRows <= 0) {
            return Optional.empty();
        }
        if (summaryUserRows > 1) {
            log.warn("memory_shrink 存在多条 conversation_summary，将按最早一对折叠视图，appId={}, summaryUserRows={}",
                    appId, summaryUserRows);
        }
        // 2. 按 anchor 升序扫描 shrink，取首对 user/ai 摘要与全部 merged source id
        List<MemoryShrink> rows = listByAppIdOrderByAnchorAsc(appId);
        String userSummary = null;
        String aiSummary = null;
        LocalDateTime anchor = null;
        for (MemoryShrink row : rows) {
            if (!MemoryShrinkTypeEnum.CONVERSATION_SUMMARY.getValue().equals(row.getShrinkType())) {
                continue;
            }
            if (userSummary == null && ChatHistoryMessageTypeEnum.USER.getValue().equals(row.getMessageType())) {
                userSummary = row.getMessage();
                anchor = row.getAnchorCreateTime();
            }
            if (userSummary != null && aiSummary == null && ChatHistoryMessageTypeEnum.AI.getValue().equals(row.getMessageType())) {
                aiSummary = row.getMessage();
            }
        }
        if (userSummary == null || aiSummary == null) {
            return Optional.empty();
        }
        // 3. 聚合全部 conversation_summary 的 source id，封装为 ConversationSummaryPair 返回
        List<Long> sourceIds = new ArrayList<>(collectAllMergedSourceChatHistoryIds(appId));
        return Optional.of(new ConversationSummaryPair(userSummary, aiSummary, anchor, sourceIds));
    }

    /**
     * 全量替换该应用的 conversation_summary：先删旧 summary 再写入新 user/ai 摘要对
     *
     * @param appId                 应用 id
     * @param userId                用户 id
     * @param anchorCreateTime      时间轴锚点
     * @param userSummary           新的用户侧摘要
     * @param aiSummary             新的 AI 侧摘要
     * @param sourceChatHistoryIds  本次合并覆盖的 chat_history id 列表
     */
    @Override
    public void replaceConversationSummary(Long appId, Long userId, LocalDateTime anchorCreateTime,
                                           String userSummary, String aiSummary, List<Long> sourceChatHistoryIds) {
        // 方法大纲：
        // 1. 删除该 app 下全部 conversation_summary 行
        // 2. 写入新的 user/ai 摘要对

        // 1. 删除该 app 下全部 conversation_summary 行
        ensureTableExists();
        deleteAllConversationSummaries(appId);
        // 2. 写入新的 user/ai 摘要对
        insertSummaryPair(appId, userId, anchorCreateTime, userSummary, aiSummary, sourceChatHistoryIds);
    }

    /**
     * 删除指定应用下所有 shrinkType=conversation_summary 的记录
     *
     * @param appId 应用 id
     */
    private void deleteAllConversationSummaries(Long appId) {
        // 1. 按 appId + shrinkType 条件批量 remove，为 replace 或重建 summary 清空旧数据
        QueryWrapper q = new QueryWrapper();
        q.eq(MemoryShrink::getAppId, appId);
        q.eq(MemoryShrink::getShrinkType, MemoryShrinkTypeEnum.CONVERSATION_SUMMARY.getValue());
        this.remove(q);
    }

    /**
     * 将 sourceChatHistoryIds 序列化为 JSON 字符串落库
     *
     * @param ids chat_history 主键列表
     * @return JSON 数组字符串，失败时返回 "[]"
     */
    private String writeSourceIdsJson(List<Long> ids) {
        // 1. 用 ObjectMapper 将 id 列表序列化为 JSON 数组字符串，失败时回落 "[]"
        try {
            return objectMapper.writeValueAsString(ids == null ? Collections.emptyList() : ids);
        } catch (Exception e) {
            log.warn("序列化 sourceChatHistoryIds 失败", e);
            return "[]";
        }
    }

    /**
     * 从 JSON 字符串解析 sourceChatHistoryIds
     *
     * @param json 数据库中的 JSON 字段
     * @return id 列表，解析失败时返回空列表
     */
    private List<Long> parseSourceIds(String json) {
        // 1. 空 JSON 直接返回空列表，否则反序列化为 Long 列表，失败时返回空列表
        if (StrUtil.isBlank(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, LONG_LIST_TYPE);
        } catch (Exception e) {
            log.warn("解析 sourceChatHistoryIds 失败 json={}", json, e);
            return Collections.emptyList();
        }
    }
}
