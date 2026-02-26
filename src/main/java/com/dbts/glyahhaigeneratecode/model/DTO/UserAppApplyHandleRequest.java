package com.dbts.glyahhaigeneratecode.model.DTO;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户申请处理请求
 */
@Data
public class UserAppApplyHandleRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 申请记录 id
     */
    private Long applyId;
}

