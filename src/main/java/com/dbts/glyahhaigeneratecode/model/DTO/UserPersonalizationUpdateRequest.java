package com.dbts.glyahhaigeneratecode.model.DTO;

import lombok.Data;

/**
 * 用户个性化配置更新请求 DTO。
 * 两个风格字段各自可选，传空字符串或不传表示清空对应风格。
 *
 * @author glyahh
 */
@Data
public class UserPersonalizationUpdateRequest {
    private String appStyle;
    private String answerStyle;
}