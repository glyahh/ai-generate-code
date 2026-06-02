package com.dbts.glyahhaigeneratecode.model.Entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话记忆压缩行（仅 AI 上下文，不用于用户回显）。
 * <p>
 * 压缩算法产出摘要/截断正文 -> 持久化到 memory_shrink -> AI Redis 重建时按 anchorCreateTime 合并时间轴
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("memory_shrink")
public class MemoryShrink implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("appId")
    private Long appId;

    @Column("userId")
    private Long userId;

    private String message;

    @Column("messageType")
    private String messageType;

    @Column("shrinkType")
    private String shrinkType;

    @Column("sourceChatHistoryIds")
    private String sourceChatHistoryIds;

    @Column("chatHistoryId")
    private Long chatHistoryId;

    @Column("anchorCreateTime")
    private LocalDateTime anchorCreateTime;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
