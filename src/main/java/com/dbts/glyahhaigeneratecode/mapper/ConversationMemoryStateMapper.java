package com.dbts.glyahhaigeneratecode.mapper;

import com.dbts.glyahhaigeneratecode.model.Entity.ConversationMemoryState;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 会话记忆状态 映射层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
public interface ConversationMemoryStateMapper extends BaseMapper<ConversationMemoryState> {

    /**
     * 按 appId 幂等写入或更新一行（依赖 uk_app_id）。
     */
    @Insert("""
            INSERT INTO conversation_memory_state(appId, latestRoundId, latestSnapshotId, softSummary, hardSummary, changedFilesJson, fileNotesJson, createdAt, updatedAt)
            VALUES(#{appId}, #{latestRoundId}, #{latestSnapshotId}, #{softSummary}, #{hardSummary}, #{changedFilesJson}, #{fileNotesJson}, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                latestRoundId=VALUES(latestRoundId),
                latestSnapshotId=VALUES(latestSnapshotId),
                softSummary=VALUES(softSummary),
                hardSummary=VALUES(hardSummary),
                changedFilesJson=VALUES(changedFilesJson),
                fileNotesJson=VALUES(fileNotesJson),
                updatedAt=NOW()
            """)
    int upsertByAppId(@Param("appId") Long appId,
                      @Param("latestRoundId") Long latestRoundId,
                      @Param("latestSnapshotId") Long latestSnapshotId,
                      @Param("softSummary") String softSummary,
                      @Param("hardSummary") String hardSummary,
                      @Param("changedFilesJson") String changedFilesJson,
                      @Param("fileNotesJson") String fileNotesJson);
}
