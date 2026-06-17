package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.DTO.UserPersonalizationUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.UserPersonalizationVO;
import com.dbts.glyahhaigeneratecode.service.UserPersonalizationService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户个性化配置 Controller。
 * <p>
 * 登录用户仅能读写自己的配置，通过 session 中 userService.getUserInSession 鉴权。
 *
 * @author glyahh
 */
@RestController
@RequestMapping("/user/personalization")
@RequiredArgsConstructor
@Slf4j
public class UserPersonalizationController {

    private final UserPersonalizationService userPersonalizationService;
    private final UserService userService;

    /**
     * 获取当前登录用户的个性化配置。
     *
     * @return 配置 VO（含 appStyle / answerStyle），未配置时字段为 null
     */
    @GetMapping
    public BaseResponse<UserPersonalizationVO> getPersonalization(HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null,
                ErrorCode.NOT_LOGIN_ERROR, "用户未登录或会话失效");
        UserPersonalizationVO vo = userPersonalizationService.getByUserId(loginUser.getId());
        return ResultUtils.success(vo);
    }

    /**
     * 保存或更新当前登录用户的个性化配置。
     *
     * @param request 请求体（appStyle / answerStyle 可选）
     * @return true 表示保存成功
     */
    @PutMapping
    public BaseResponse<Boolean> updatePersonalization(
            @RequestBody UserPersonalizationUpdateRequest request,
            HttpServletRequest httpRequest) {
        User loginUser = userService.getUserInSession(httpRequest);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null,
                ErrorCode.NOT_LOGIN_ERROR, "用户未登录或会话失效");
        ThrowUtils.throwIf(request == null,
                ErrorCode.PARAMS_ERROR, "请求参数不能为空");
        userPersonalizationService.saveOrUpdate(loginUser.getId(), request);
        return ResultUtils.success(true);
    }
}