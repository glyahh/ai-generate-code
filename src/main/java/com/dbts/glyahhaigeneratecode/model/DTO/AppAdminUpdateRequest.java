package com.dbts.glyahhaigeneratecode.model.DTO;

import lombok.Data;

import java.io.Serializable;

/**
 * 管理员更新应用请求
 */
@Data
public class AppAdminUpdateRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 应用封面
     */
    private String cover;

    /**
     * 优先级（用于精选排序）
     */
    private Integer priority;

    private static final long serialVersionUID = 1L;
}

