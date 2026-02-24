package com.dbts.glyahhaigeneratecode.model.DTO;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用对话生成代码请求
 */
@Data
public class ChatToGenCodeRequest implements Serializable {

    /**
     * 应用 id
     */
    private Long appId;

    /**
     * 用户输入内容（本次对话消息）
     */
    private String message;

    private static final long serialVersionUID = 1L;
}
