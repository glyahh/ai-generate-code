package com.dbts.glyahhaigeneratecode.mapper;

import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 对话历史 映射层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
public interface ChatHistoryMapper extends BaseMapper<ChatHistory> {

    @Select("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_history' AND COLUMN_NAME = #{columnName}
            """)
    Integer countChatHistoryInformationSchemaColumn(@Param("columnName") String columnName);

    @Select("""
            SELECT DATA_TYPE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_history' AND COLUMN_NAME = 'message'
            """)
    String selectChatHistoryMessageDataType();

    @Update("""
            ALTER TABLE chat_history ADD COLUMN auditAction varchar(16) NOT NULL DEFAULT 'SKIP' COMMENT '审查动作：ALLOW/REJECT/SKIP' AFTER userId
            """)
    int alterChatHistoryAddAuditAction();

    @Update("""
            ALTER TABLE chat_history ADD COLUMN auditHitRule varchar(64) NOT NULL DEFAULT 'NONE' COMMENT '命中审查规则编码' AFTER auditAction
            """)
    int alterChatHistoryAddAuditHitRule();

    @Update("""
            ALTER TABLE chat_history MODIFY COLUMN message LONGTEXT NOT NULL COMMENT '消息'
            """)
    int alterChatHistoryMessageToLongText();
}
