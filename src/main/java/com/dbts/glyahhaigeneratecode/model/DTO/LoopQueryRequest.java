package com.dbts.glyahhaigeneratecode.model.DTO;

import com.dbts.glyahhaigeneratecode.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * Loop 分页查询请求。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class LoopQueryRequest extends PageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 模糊搜索文本（匹配 loopName + description）
     */
    private String searchText;
}
