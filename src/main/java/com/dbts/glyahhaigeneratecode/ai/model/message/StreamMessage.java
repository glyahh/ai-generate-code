package com.dbts.glyahhaigeneratecode.ai.model.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式消息响应基类
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StreamMessage {
    // 返回的类型 AI响应 工具请求 工具执行结果
    private String type;
}
