package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.Data;

/**
 * 用户个性化配置 VO。
 * 返回给前端时两个字段均为可选。
 *
 * @author glyahh
 */
@Data
public class UserPersonalizationVO {
    /** 应用风格 prompt */
    private String appStyle;
    /** 回答风格 prompt */
    private String answerStyle;
}