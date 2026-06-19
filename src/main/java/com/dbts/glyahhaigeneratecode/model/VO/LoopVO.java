package com.dbts.glyahhaigeneratecode.model.VO;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Loop 视图对象。
 */
@Data
public class LoopVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String loopName;

    private String description;

    private String cover;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private Integer priority;

    private String workflowJson;

    private String compiledPrompt;

    private String sourceType;

    private String visibility;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
