package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户申请历史视图
 * 用于普通用户查看自己的申请记录及审核结果
 */
@Data
public class ApplyHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 申请记录 id
     */
    private String applyId;

    /**
     * 操作类型：1-申请将自己的应用设置为精选应用；2-申请成为管理员
     */
    private Integer operate;

    /**
     * 申请关联的应用 id（operate=2 时为空）
     */
    private String appId;

    /**
     * 申请关联的应用名称（operate=2 时为空）
     */
    private String appName;

    /**
     * 申请的应用展示优先级（operate=1 时有效）
     */
    private Integer appPropriety;

    /**
     * 申请理由
     */
    private String applyReason;

    /**
     * 处理状态：0-待处理；1-通过；2-拒绝
     */
    private Integer status;

    /**
     * 审核备注（拒绝理由）
     */
    private String reviewRemark;

    /**
     * 申请创建时间
     */
    private LocalDateTime createTime;

    /**
     * 审核时间
     */
    private LocalDateTime reviewTime;
}

