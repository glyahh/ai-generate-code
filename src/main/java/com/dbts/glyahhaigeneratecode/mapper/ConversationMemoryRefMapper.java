package com.dbts.glyahhaigeneratecode.mapper;

import com.dbts.glyahhaigeneratecode.model.Entity.ConversationMemoryRef;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会话记忆 ref 归档 映射层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
public interface ConversationMemoryRefMapper extends BaseMapper<ConversationMemoryRef> {

    @Insert("""
            INSERT IGNORE INTO conversation_memory_ref(appId, roundId, refId, filePath, content, contentBytes, createdAt)
            VALUES(#{appId}, #{roundId}, #{refId}, #{filePath}, #{content}, #{contentBytes}, NOW())
            """)
    int insertIgnore(@Param("appId") Long appId,
                     @Param("roundId") Long roundId,
                     @Param("refId") String refId,
                     @Param("filePath") String filePath,
                     @Param("content") String content,
                     @Param("contentBytes") long contentBytes);

    /**
     * 删除早于指定天数的 ref。
     */
    int deleteByCreatedBeforeDays(@Param("days") int days);

    /**
     * 每 app 仅保留最近 N 条（按 createdAt、id 倒序编号后删除超出部分）。
     */
    int deleteExcessRowsPerApp(@Param("keepCount") int keepCount);

    /**
     * 有 ref 记录的 appId 去重列表。
     */
    List<Long> selectDistinctAppIds();

    /**
     * 某 app 下 ref 内容字节总和。
     */
    Long sumContentBytesByAppId(@Param("appId") Long appId);

    /**
     * 按累计字节从最旧开始删，直到删掉 overflow 字节（用于单 app 超限治理）。
     */
    int deleteOldestUntilBytesRemoved(@Param("appId") Long appId, @Param("overflow") long overflow);
}
