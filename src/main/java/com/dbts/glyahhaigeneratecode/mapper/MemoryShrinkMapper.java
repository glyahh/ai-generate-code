package com.dbts.glyahhaigeneratecode.mapper;

import com.dbts.glyahhaigeneratecode.model.Entity.MemoryShrink;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * memory_shrink 映射层。
 * <p>
 * 探测/建表 DDL -> MyBatis-Flex CRUD -> 供 MemoryShrinkService 读写压缩行
 */
public interface MemoryShrinkMapper extends BaseMapper<MemoryShrink> {

    /**
     * 查询当前库是否已有 memory_shrink 表
     *
     * @return 表存在时 &gt; 0，否则 0 或 null
     */
    @Select("""
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = 'memory_shrink'
            """)
    Integer countMemoryShrinkTableExists();

    /**
     * 幂等创建 memory_shrink 表及索引
     */
    @Update("""
            CREATE TABLE IF NOT EXISTS memory_shrink (
                id BIGINT NOT NULL COMMENT 'id' PRIMARY KEY,
                appId BIGINT NOT NULL COMMENT '应用id',
                userId BIGINT NOT NULL COMMENT '用户id',
                message LONGTEXT NOT NULL COMMENT '压缩后消息',
                messageType VARCHAR(32) NOT NULL COMMENT 'user/ai',
                shrinkType VARCHAR(64) NOT NULL COMMENT 'conversation_summary/message_truncate',
                sourceChatHistoryIds JSON NULL COMMENT '关联 chat_history.id 列表',
                chatHistoryId BIGINT NULL COMMENT 'truncate 源行 id',
                anchorCreateTime DATETIME NOT NULL COMMENT '时间轴锚点',
                createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                updateTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                isDelete TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除',
                INDEX idx_app_anchor (appId, anchorCreateTime),
                UNIQUE KEY uk_app_chat_shrink (appId, chatHistoryId, shrinkType)
            ) COMMENT '会话记忆压缩表' COLLATE = utf8mb4_unicode_ci
            """)
    void createMemoryShrinkTableIfMissing();

    /**
     * 按应用与 shrinkType 统计 USER 类型行数（用于有效轮数）
     *
     * @param appId      应用 id
     * @param shrinkType 压缩类型，如 conversation_summary
     * @return USER 行数
     */
    @Select("""
            SELECT COUNT(*) FROM memory_shrink
            WHERE appId = #{appId} AND shrinkType = #{shrinkType}
              AND messageType = 'user' AND isDelete = 0
            """)
    int countUserRowsByShrinkType(@Param("appId") Long appId, @Param("shrinkType") String shrinkType);
}
