package com.dbts.glyahhaigeneratecode.LangGraph4j.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 检查代码生成是否质量过关
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityResult implements Serializable {
    
    @Serial
    private static final long serialVersionUID = 1L;
    
    /**
     * 是否通过质检
     */
    private Boolean isValid;
    
    /**
     * 错误列表
     */
    private List<String> errors;
    
    /**
     * 改进建议
     */
    private List<String> suggestions;
}
