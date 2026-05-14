package com.dbts.glyahhaigeneratecode.model.Entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话记忆状态：每个应用一行，汇总最近轮次、快照指针、摘要文案与变更文件列表 JSON。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("conversation_memory_state")
public class ConversationMemoryState implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键（自增）
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 应用 id（uk_app_id，每 app 唯一一行）
     */
    @Column("appId")
    private Long appId;

    /**
     * 最近写入的轮次 id（chat_history.id）
     */
    @Column("latestRoundId")
    private Long latestRoundId;

    /**
     * 对应当前 manifest 写入的 snapshot_history.id；无快照时可为 null
     */
    @Column("latestSnapshotId")
    private Long latestSnapshotId;

    /**
     * 中长会话档位下的策略提示文案（可为 null）
     */
    @Column("softSummary")
    private String softSummary;

    /**
     * 更长会话档位下的补充策略提示（可为 null）
     */
    @Column("hardSummary")
    private String hardSummary;

    /**
     * 本轮及近期变更文件路径列表的 JSON 数组字符串
     */
    @Column("changedFilesJson")
    private String changedFilesJson;

    /**
     * 行更新时间
     */
    @Column("updatedAt")
    private LocalDateTime updatedAt;

    /**
     * 行创建时间
     */
    @Column("createdAt")
    private LocalDateTime createdAt;
}
