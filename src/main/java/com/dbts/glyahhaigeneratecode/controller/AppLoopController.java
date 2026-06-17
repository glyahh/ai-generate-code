package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.AppLoopService;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 应用 Loop 库控制器。
 * 管理应用与 Loop 技能的绑定关系。
 * <p>所有接口均需校验当前用户对 appId 的操作权限，防止越权绑定/解绑他人应用。</p>
 */
@Slf4j
@RestController
@RequestMapping("/app/loop")
@RequiredArgsConstructor
public class AppLoopController {

    private final AppLoopService appLoopService;
    private final AppService appService;
    private final UserService userService;

    /**
     * 校验当前用户是否有权操作指定应用。
     */
    private void checkAppOwner(Long appId, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null || app.getUserId() == null,
                ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()),
                ErrorCode.FORBIDDEN_ERROR, "无权操作该应用");
    }

    /**
     * 批量绑定 Loop 到应用（创建应用时使用）。
     */
    @PostMapping("/bind")
    public BaseResponse<Void> bind(@RequestParam Long appId, @RequestBody List<Long> loopIds,
                                   HttpServletRequest request) {
        checkAppOwner(appId, request);
        appLoopService.bindLoops(appId, loopIds, "creation");
        return ResultUtils.success(null);
    }

    /**
     * 单个添加 Loop 到应用（从市场添加）。
     */
    @PostMapping("/add")
    public BaseResponse<Void> add(@RequestParam Long appId, @RequestParam Long loopId,
                                  HttpServletRequest request) {
        checkAppOwner(appId, request);
        appLoopService.addLoop(appId, loopId, "market");
        return ResultUtils.success(null);
    }

    /**
     * 查询应用绑定的 Loop 摘要列表。
     */
    @PostMapping("/list/vo")
    public BaseResponse<List<Map<String, Object>>> listVO(@RequestParam Long appId,
                                                          HttpServletRequest request) {
        checkAppOwner(appId, request);
        return ResultUtils.success(appLoopService.listLoopVOs(appId));
    }

    /**
     * 从应用移除指定 Loop。
     */
    @PostMapping("/remove")
    public BaseResponse<Void> remove(@RequestParam Long appId, @RequestParam Long loopId,
                                     HttpServletRequest request) {
        checkAppOwner(appId, request);
        appLoopService.removeLoop(appId, loopId);
        return ResultUtils.success(null);
    }
}
