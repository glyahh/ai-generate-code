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
 * 会话记忆大文本归档：对单轮变更中超长文件做 DB 全文备份，refId 唯一，供治理与按需回源。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("conversation_memory_ref")
public class ConversationMemoryRef implements Serializable {

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
     * 轮次 id（对应 chat_history.id）
     */
    @Column("roundId")
    private Long roundId;

    /**
     * 业务唯一键（如 ref-{appId}-{roundId}-{hash}），与 uk_ref_id 一致
     */
    @Column("refId")
    private String refId;

    /**
     * 相对项目根的文件路径（如 package-lock.json）。
     * 由 {@link com.dbts.glyahhaigeneratecode.service.impl.ConversationMemoryStateServiceImpl#archiveLargeChangedFilesIfNeeded}
     * 从生成目录磁盘读取后写入，非 npm 直接写库。
     */
    @Column("filePath")
    private String filePath;

    /**
     * 归档时的文件全文（LONGTEXT），与磁盘上该路径文件内容一致。
     * 同步热缓存到 Redis 键 {@code cm:ref:{refId}}，过期后从此列回源。
     */
    @Column("content")
    private String content;

    /**
     * 内容字节数（UTF-8），用于按 app 总字节治理
     */
    @Column("contentBytes")
    private Long contentBytes;

    /**
     * 归档时间
     */
    @Column("createdAt")
    private LocalDateTime createdAt;
}
