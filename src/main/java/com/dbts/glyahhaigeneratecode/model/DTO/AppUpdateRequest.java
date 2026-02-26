package com.dbts.glyahhaigeneratecode.model.DTO;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户更新应用请求（目前仅支持修改应用名称）
 */
@Data
public class AppUpdateRequest implements Serializable {

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



    private static final long serialVersionUID = 1L;
}

