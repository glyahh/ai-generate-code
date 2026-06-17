package com.dbts.glyahhaigeneratecode.model.DTO;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Loop 创建请求。
 */
@Data
public class LoopAddRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 名称（必填，1-128 字符）
     */
    private String loopName;

    /**
     * 市场简介（选填，最长 512 字符）
     */
    private String description;

    /**
     * 封面 URL
     */
    private String cover;

    /**
     * 标准模板步骤 JSON（必填）
     */
    private String workflowJson;

    /**
     * 来源类型：created/imported
     */
    private String sourceType;

    /**
     * 可见性：public/private
     */
    private String visibility;
}
