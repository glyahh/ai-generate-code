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
 * <p>市场 Loop 只能导入到「我的 Loop」个人库，不能直绑应用。
 * 绑应用到应用必须走「我的 Loop」选择，且只能绑自己创建的 Loop。
 * 这是设计约束：避免用户误把他人作品直挂到生产环境后产生连带责任。</p>
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
     * 从「我的 Loop」批量绑定到应用（创建应用时使用）。
     * <p>每个 Loop 必须属于当前用户，由 bindLoopsFromMyLoop 统一校验。
     * 注意：这里不走 bindLoops（无归属校验），因为创建应用时用户选的是「我的 Loop」中的项，
     * 已经过了 LoopPickerTrigger 的前端筛选，但后端仍需二次确认，防止篡改请求。</p>
     */
    @PostMapping("/bind")
    public BaseResponse<Void> bindFromMyLoops(@RequestParam Long appId, @RequestBody List<Long> loopIds,
                                              HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        checkAppOwner(appId, request);
        appLoopService.bindLoopsFromMyLoop(appId, loopIds, loginUser.getId(), "creation");
        return ResultUtils.success(null);
    }

    /**
     * 从「我的 Loop」单个添加到应用（对话界面添加时使用）。
     * <p>与前端的 AppLoopInjectBar「+ 添加」按钮对应：用户从自己的 Loop 列表中选择一个绑定到当前应用。</p>
     */
    @PostMapping("/add")
    public BaseResponse<Void> addFromMyLoop(@RequestParam Long appId, @RequestParam Long loopId,
                                            HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        checkAppOwner(appId, request);
        appLoopService.bindLoopsFromMyLoop(appId, List.of(loopId), loginUser.getId(), "chat");
        return ResultUtils.success(null);
    }

    /**
     * 从「我的 Loop」批量添加到应用（对话界面批量添加时使用）。
     * <p>与前端 AppLoopInjectBar「添加到当前应用」按钮对应：用户从自己的 Loop 列表中
     * 批量选择多个绑定到当前应用。</p>
     */
    @PostMapping("/batch/add")
    public BaseResponse<Void> batchAddFromMyLoop(@RequestParam Long appId, @RequestBody List<Long> loopIds,
                                                  HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        checkAppOwner(appId, request);
        ThrowUtils.throwIf(loopIds == null || loopIds.isEmpty(),
                ErrorCode.PARAMS_ERROR, "Loop ID 列表不能为空");
        for (Long loopId : loopIds) {
            appLoopService.bindLoopsFromMyLoop(appId, List.of(loopId), loginUser.getId(), "chat");
        }
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
