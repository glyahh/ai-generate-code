package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.service.AppLoopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 应用 Loop 库控制器。
 * 管理应用与 Loop 技能的绑定关系。
 */
@Slf4j
@RestController
@RequestMapping("/app/loop")
@RequiredArgsConstructor
public class AppLoopController {

    private final AppLoopService appLoopService;

    /**
     * 批量绑定 Loop 到应用（创建应用时使用）。
     */
    @PostMapping("/bind")
    public BaseResponse<Void> bind(@RequestParam Long appId, @RequestBody List<Long> loopIds) {
        appLoopService.bindLoops(appId, loopIds, "creation");
        return ResultUtils.success(null);
    }

    /**
     * 单个添加 Loop 到应用（从市场添加）。
     */
    @PostMapping("/add")
    public BaseResponse<Void> add(@RequestParam Long appId, @RequestParam Long loopId) {
        appLoopService.addLoop(appId, loopId, "market");
        return ResultUtils.success(null);
    }

    /**
     * 查询应用绑定的 Loop 摘要列表。
     */
    @PostMapping("/list/vo")
    public BaseResponse<List<Map<String, Object>>> listVO(@RequestParam Long appId) {
        return ResultUtils.success(appLoopService.listLoopVOs(appId));
    }

    /**
     * 从应用移除指定 Loop。
     */
    @PostMapping("/remove")
    public BaseResponse<Void> remove(@RequestParam Long appId, @RequestParam Long loopId) {
        appLoopService.removeLoop(appId, loopId);
        return ResultUtils.success(null);
    }
}
