package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.mapper.ChatHistoryMapper;
import com.dbts.glyahhaigeneratecode.mapper.MemoryShrinkMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.Entity.MemoryShrink;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
            existing.setMessage(message);
            existing.setMessageType(messageType);
            existing.setAnchorCreateTime(anchorCreateTime);
            existing.setUpdateTime(now);
            this.updateById(existing);
            return;
        }
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
        int summaryUsers = countSummaryUserRounds(appId);
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
        ensureTableExists();
        return this.getMapper().countUserRowsByShrinkType(appId, MemoryShrinkTypeEnum.CONVERSATION_SUMMARY.getValue());
    }

    /**
     * 将 sourceChatHistoryIds 序列化为 JSON 字符串落库
     *
     * @param ids chat_history 主键列表
     * @return JSON 数组字符串，失败时返回 "[]"
     */
    private String writeSourceIdsJson(List<Long> ids) {
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
