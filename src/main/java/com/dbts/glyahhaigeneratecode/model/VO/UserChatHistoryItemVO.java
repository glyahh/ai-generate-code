package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 普通用户侧：对话历史列表项（仅当前登录用户可见）
 */
@Data
public class UserChatHistoryItemVO implements Serializable {

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
     * 应用名称
     */
    private String appName;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * AI 消息摘要行
     */
    private String summaryText;

    /**
     * AI 消息自然语言正文
     */
    private String naturalLanguage;

    private static final long serialVersionUID = 1L;
}

