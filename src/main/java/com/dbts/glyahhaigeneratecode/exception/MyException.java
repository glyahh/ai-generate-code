package com.dbts.glyahhaigeneratecode.exception;

import lombok.Getter;

@Getter

public class MyException extends RuntimeException {
    // 可选：添加获取错误码的方法
    /**
     * 错误码
     */
    private final int code;

    public MyException(int code, String message) {
        super(message);
        this.code = code;
    }

    public MyException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public MyException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

}
