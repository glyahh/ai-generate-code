package com.dbts.glyahhaigeneratecode.exception;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@Hidden
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // 错误码异常
    @ExceptionHandler(MyException.class)
    public BaseResponse<?> businessExceptionHandler(MyException e) {
        log.error("出现了,我的异常! MyException: ", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }


    // 运行时异常
    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }
}
