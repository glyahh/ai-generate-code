package com.dbts.glyahhaigeneratecode.controller;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.annotation.MyRole;
import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.constant.UserConstant;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopAddRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopQueryRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.LoopVO;
import com.dbts.glyahhaigeneratecode.service.LoopService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Loop 控制层。
 */
@Slf4j
@RestController
@RequestMapping("/loop")
@RequiredArgsConstructor
public class LoopController {

    private final LoopService loopService;
    private final UserService userService;

    /**
     * 创建 Loop。
     */
    @PostMapping("/add")
    public BaseResponse<Long> add(@RequestBody LoopAddRequest req, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        ThrowUtils.throwIf(req.getLoopName() == null || req.getLoopName().isBlank(),
                ErrorCode.PARAMS_ERROR, "Loop 名称不能为空");
        ThrowUtils.throwIf(req.getLoopName().length() > 128,
                ErrorCode.PARAMS_ERROR, "Loop 名称最长 128 字符");
        ThrowUtils.throwIf(StrUtil.isBlank(req.getWorkflowJson()),
                ErrorCode.PARAMS_ERROR, "workflowJson 不能为空");
        Long id = loopService.addLoop(req, loginUser.getId());
        return ResultUtils.success(id);
    }

    /**
     * 更新 Loop。
     */
    @PostMapping("/update")
    public BaseResponse<Void> update(@RequestBody LoopUpdateRequest req, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        ThrowUtils.throwIf(req.getId() == null, ErrorCode.PARAMS_ERROR, "ID 不能为空");
        loopService.updateLoop(req, loginUser.getId());
        return ResultUtils.success(null);
    }

    /**
     * 删除 Loop。
     */
    @PostMapping("/delete")
    public BaseResponse<Void> delete(@RequestParam Long id, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "ID 不能为空");
        loopService.deleteLoop(id, loginUser.getId());
        return ResultUtils.success(null);
    }

    /**
     * 获取 Loop 视图对象。
     */
    @GetMapping("/get/vo")
    public BaseResponse<LoopVO> getVO(@RequestParam Long id) {
        return ResultUtils.success(loopService.getLoopVO(id));
    }

    /**
     * 分页查询当前用户自己的 Loop。
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<List<LoopVO>> myListPage(@RequestBody LoopQueryRequest req, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        return ResultUtils.success(loopService.myListPage(req, loginUser.getId()));
    }

    /**
     * 分页查询精选 Loop（priority >= 99，公开可见）。
     */
    @PostMapping("/good/list/page/vo")
    public BaseResponse<List<LoopVO>> goodListPage(@RequestBody LoopQueryRequest req) {
        return ResultUtils.success(loopService.goodListPage(req));
    }

    /**
     * 分页查询公开 Loop（所有 visibility=public 的 Loop，不带 priority 过滤）。用于市场「探索」Tab。
     */
    @PostMapping("/public/list/page/vo")
    public BaseResponse<List<LoopVO>> publicListPage(@RequestBody LoopQueryRequest req) {
        return ResultUtils.success(loopService.publicListPage(req));
    }

    /**
     * 导入 Loop（解析 frontmatter + body）。
     */
    @PostMapping("/import")
    public BaseResponse<Long> importLoop(@RequestBody String rawContent, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        ThrowUtils.throwIf(StrUtil.isBlank(rawContent),
                ErrorCode.PARAMS_ERROR, "导入内容不能为空");
        Long id = loopService.importLoop(rawContent, loginUser.getId());
        return ResultUtils.success(id);
    }

    /**
     * 从市场克隆一个公开 Loop 到当前用户的个人库。
     * 市场只进个人库，不会直接绑定到应用，避免用户误把他人作品直挂到生产环境。
     */
    @PostMapping("/market/import")
    public BaseResponse<Long> marketImport(@RequestParam Long loopId, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        Long id = loopService.marketImport(loopId, loginUser.getId());
        return ResultUtils.success(id);
    }

    /**
     * 通过上传文件导入 Loop。
     * <p>支持 .md（含 YAML frontmatter 的 Skill 格式）和 .json（标准 Loop workflowJson 格式）。
     * 自动识别：合法 JSON 且含 templateId+steps 视为 Loop 原生格式直接入库；
     * 含 YAML frontmatter 的视为 Skill，走 importLoop 转换。</p>
     */
    @PostMapping("/import/file")
    public BaseResponse<Long> importFile(@RequestParam MultipartFile file, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        ThrowUtils.throwIf(file == null || file.isEmpty(),
                ErrorCode.PARAMS_ERROR, "上传文件不能为空");

        String fileName = file.getOriginalFilename();
        if (fileName == null || (!fileName.endsWith(".md") && !fileName.endsWith(".json"))) {
            ThrowUtils.throwIf(true, ErrorCode.PARAMS_ERROR, "仅支持 .md 或 .json 格式文件");
        }

        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            ThrowUtils.throwIf(StrUtil.isBlank(content),
                    ErrorCode.PARAMS_ERROR, "文件内容为空");

            String trimmed = content.trim();

            // 尝试识别为 JSON Loop 原生格式
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(trimmed);
                    if (root.has("templateId") && root.has("steps") && root.get("steps").isArray()) {
                        // 是标准 Loop JSON → 直接作为 workflowJson 创建
                        String loopName = root.has("name") ? root.get("name").asText() : fileName.replaceAll("\\.\\w+$", "");
                        LoopAddRequest req = new LoopAddRequest();
                        req.setLoopName(loopName);
                        req.setDescription(root.has("description") ? root.get("description").asText() : "");
                        req.setWorkflowJson(trimmed);
                        req.setSourceType("imported");
                        req.setVisibility("private");
                        return ResultUtils.success(loopService.addLoop(req, loginUser.getId()));
                    }
                } catch (Exception ignored) {
                    // 不是合法 JSON Loop，继续按 Skill 方式解析
                }
            }

            // 否则走现有 importLoop（解析 frontmatter + body → workflowJson）
            Long id = loopService.importLoop(trimmed, loginUser.getId());
            return ResultUtils.success(id);

        } catch (IOException e) {
            log.error("importFile 读取异常", e);
            throw new RuntimeException("文件读取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 申请精选。
     */
    @PostMapping("/apply")
    public BaseResponse<Void> apply(@RequestParam Long loopId,
                                    @RequestParam(required = false) String reason,
                                    HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        loopService.applyGood(loopId, reason, loginUser.getId());
        return ResultUtils.success(null);
    }

    /**
     * 管理员分页查询所有 Loop。
     */
    @PostMapping("/admin/list/page/vo")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<LoopVO>> adminListPage(@RequestBody LoopQueryRequest req) {
        return ResultUtils.success(loopService.adminListPage(req));
    }

    /**
     * 管理员更新 Loop（可调整 priority）。
     */
    @PostMapping("/admin/update")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Void> adminUpdate(@RequestBody LoopUpdateRequest req) {
        loopService.adminUpdate(req);
        return ResultUtils.success(null);
    }

    /**
     * 管理员删除 Loop（绕过用户归属校验）。
     */
    @PostMapping("/admin/delete")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Void> adminDelete(@RequestParam Long id) {
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "ID 不能为空");
        loopService.adminDeleteLoop(id);
        return ResultUtils.success(null);
    }

    // ==================== 管理员 Loop 申请审批 ====================

    /**
     * 管理员分页查询 Loop 精选申请列表。
     */
    @PostMapping("/admin/apply/list")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<Map<String, Object>>> adminListApply(@RequestBody LoopQueryRequest req) {
        return ResultUtils.success(loopService.adminListApply(req));
    }

    /**
     * 管理员通过 Loop 精选申请。
     */
    @PostMapping("/admin/apply/approve")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Void> adminApproveApply(@RequestParam Long applyId, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        loopService.adminApproveApply(applyId, loginUser.getId());
        return ResultUtils.success(null);
    }

    /**
     * 管理员拒绝 Loop 精选申请。
     */
    @PostMapping("/admin/apply/reject")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Void> adminRejectApply(@RequestParam Long applyId,
                                                @RequestParam(required = false) String reviewRemark,
                                                HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        loopService.adminRejectApply(applyId, reviewRemark, loginUser.getId());
        return ResultUtils.success(null);
    }
}
