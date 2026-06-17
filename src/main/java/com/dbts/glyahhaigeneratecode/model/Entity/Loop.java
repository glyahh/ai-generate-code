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
 * Loop 实体类。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("loop")
public class Loop implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("loopName")
    private String loopName;

    private String description;

    private String cover;

    @Column("userId")
    private Long userId;

    private Integer priority;

    @Column("workflowJson")
    private String workflowJson;

    @Column("compiledPrompt")
    private String compiledPrompt;

    @Column("sourceType")
    private String sourceType;

    private String visibility;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;
}
