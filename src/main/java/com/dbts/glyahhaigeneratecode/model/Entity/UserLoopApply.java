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
 * UserLoopApply 实体类。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_loop_apply")
public class UserLoopApply implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("loopId")
    private Long loopId;

    @Column("userId")
    private Long userId;

    private Integer operate;

    private Integer status;

    @Column("applyReason")
    private String applyReason;

    @Column("reviewUserId")
    private Long reviewUserId;

    @Column("reviewRemark")
    private String reviewRemark;

    @Column("reviewTime")
    private LocalDateTime reviewTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;
}
