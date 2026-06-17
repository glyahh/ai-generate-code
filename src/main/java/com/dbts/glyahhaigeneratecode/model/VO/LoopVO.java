package com.dbts.glyahhaigeneratecode.model.VO;

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

    private Long id;

    private String loopName;

    private String description;

    private String cover;

    private Long userId;

    private Integer priority;

    private String workflowJson;

    private String compiledPrompt;

    private String sourceType;

    private String visibility;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
