package com.dbts.glyahhaigeneratecode.model.DTO;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户应用 / 权限申请请求
 */
@Data
public class UserAppApplyRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 申请关联的应用 id，申请管理员时可为空
     */
    private Long appId;

    /**
     * 应用展示优先级（仅在申请精选应用时使用）
     */
    private Integer appPropriety;

    /**
     * 操作类型：1-申请将自己的应用设置为精选应用；2-申请成为管理员
     */
    private Integer operate;

    /**
     * 申请理由
     */
    private String applyReason;
}

