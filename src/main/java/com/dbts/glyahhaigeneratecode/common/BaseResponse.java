package com.dbts.glyahhaigeneratecode.common;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;


/**
 * 通用响应类
 * @param <T>
 */
@Data
public class BaseResponse<T> implements Serializable {

    private int code;

    private T data;

    private String message;

    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public BaseResponse(int code, T data) {
        this(code, data, "滚木");
    }

    /**
     * 将枚举类错误类返回
     * @param errorCode
     */
    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }
}
