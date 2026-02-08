package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
public class Test {

    @GetMapping("/")
    public BaseResponse<?> test() {
        //return ResultUtils.success("glyahh !");
        return ResultUtils.error(ErrorCode.NO_AUTH_ERROR);
    }
}
