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
 * 每轮 manifest 快照历史：按应用、轮次持久化一次代码树清单（JSON），供会话记忆注入与回溯。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("snapshot_history")
public class SnapshotHistory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键（自增）
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 应用 id
     */
    @Column("appId")
    private Long appId;

    /**
     * 轮次 id（对应当前写入的 chat_history 轮次）
     */
    @Column("roundId")
    private Long roundId;

    /**
     * manifest 条目列表的 JSON 字符串（序列化自 ManifestBundle 等）
     */
    @Column("manifestJson")
    private String manifestJson;

    /**
     * 快照内文件条目数量（与 manifest 解析后条数一致）
     */
    @Column("filesCount")
    private Integer filesCount;

    /**
     * 记录创建时间（插入时由库或 ORM 填充）
     */
    @Column("createdAt")
    private LocalDateTime createdAt;
}
