package com.dbts.glyahhaigeneratecode.model.DTO;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Loop 更新请求。
 */
@Data
public class LoopUpdateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Loop ID（必填）
     */
    private Long id;

    /**
     * 名称
     */
    private String loopName;

    /**
     * 市场简介
     */
    private String description;

    /**
     * 封面 URL
     */
    private String cover;

    /**
     * 标准模板步骤 JSON（非空时触发重编译）
     */
    private String workflowJson;

    /**
     * 可见性：public/private
     */
    private String visibility;

    /**
     * 优先级（仅管理员使用）
     */
    private Integer priority;
}
