package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对话历史视图
 */
@Data
public class ChatHistoryVO implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 消息内容
     */
    private String message;

    /**
     * 消息类型：user / ai / error
     */
    private String messageType;

    /**
     * 应用 id
     */
    private Long appId;

    /**
     * 用户 id
     */
    private Long userId;

    /**
     * 审查动作：ALLOW / REJECT / SKIP
     */
    private String auditAction;

    /**
     * 命中的审查规则编码，未命中时为 NONE
     */
    private String auditHitRule;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    private static final long serialVersionUID = 1L;
}
