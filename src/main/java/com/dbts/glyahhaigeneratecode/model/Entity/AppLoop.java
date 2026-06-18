package com.dbts.glyahhaigeneratecode.model.Entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AppLoop 实体类。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_loop")
public class AppLoop implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column("app_id")
    private Long appId;

    @Column("loop_id")
    private Long loopId;

    @Column("added_from")
    private String addedFrom;

    @Column("create_time")
    private LocalDateTime createTime;
}
