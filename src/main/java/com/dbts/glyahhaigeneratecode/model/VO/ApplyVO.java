package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.Data;

import java.io.Serializable;

/**
 * 申请回显视图
 * 用于管理员查看待处理的用户申请信息
 */
@Data
public class ApplyVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 申请记录 id
     */
    private Long applyId;

    /**
     * 申请用户 id
     */
    private Long userId;

    /**
     * 申请用户头像
     */
    private String userAvatar;

    /**
     * 操作类型：1-申请将自己的应用设置为精选应用；2-申请成为管理员
     */
    private Integer operate;

    /**
     * 申请关联的应用 id（operate=2 时为空）
     */
    private Long appId;

    /**
     * 申请理由
     */
    private String reason;
}

