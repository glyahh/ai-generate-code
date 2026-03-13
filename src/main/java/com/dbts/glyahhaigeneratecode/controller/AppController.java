package com.dbts.glyahhaigeneratecode.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.annotation.MyRole;
import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.DeleteRequest;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.constant.UserConstant;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.DTO.*;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.ApplyHistoryVO;
import com.dbts.glyahhaigeneratecode.model.VO.ApplyVO;
import com.dbts.glyahhaigeneratecode.model.VO.AppVO;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.dbts.glyahhaigeneratecode.service.UserAppApplyService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.dbts.glyahhaigeneratecode.service.ProjectDownloadService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 应用 控制层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@RestController
@RequestMapping("/app")
@RequiredArgsConstructor
@Slf4j
public class AppController {

    private final AppService appService;
    private final UserService userService;
    private final UserAppApplyService userAppApplyService;
    private final ChatHistoryService chatHistoryService;
    private final ProjectDownloadService projectDownloadService;

    /**
     * 【用户】创建应用（须填写 initPrompt）
     *
     * @param appAddRequest 创建请求
     * @param request       请求
     * @return 应用 id
     */
    @PostMapping("/add")
    public BaseResponse<Long> addMyApp(@RequestBody AppAddRequest appAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR, "创建应用请求参数为空");
        ThrowUtils.throwIf(StrUtil.isBlank(appAddRequest.getInitPrompt()), ErrorCode.PARAMS_ERROR, "initPrompt 必填");

        User loginUser = userService.getUserInSession(request);
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        app.setCodeGenType(CodeGenTypeEnum.VUE.getValue());

        //这里最好使用这个save,不要mapper中的insert,否则id会因为没有声明而被覆盖雪花算法的值,而且其他未声明的字段会报数据库不能非空
        boolean save = appService.save(app);
        ThrowUtils.throwIf(!save, ErrorCode.OPERATION_ERROR, "创建应用失败");
        return ResultUtils.success(app.getId());
    }

    /**
     * 【用户】根据 id 修改自己的应用（目前只支持修改应用名称）
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateMyApp(@RequestBody AppUpdateRequest appUpdateRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appUpdateRequest == null || appUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "更新应用请求参数异常");
        ThrowUtils.throwIf(StrUtil.isBlank(appUpdateRequest.getAppName()), ErrorCode.PARAMS_ERROR, "应用名称不能为空");

        User loginUser = userService.getUserInSession(request);
        App app = appService.getById(appUpdateRequest.getId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId()) && !loginUser.getUserRole().equals(UserConstant.ADMIN_ROLE), ErrorCode.NO_AUTH_ERROR, "只能修改自己的应用");

        app.setAppName(appUpdateRequest.getAppName());
        app.setUpdateTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新应用失败");
        return ResultUtils.success(true);
    }

    /**
     * 【用户】根据 id 删除自己的应用
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteMyApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null || deleteRequest.getId() <= 0,
                ErrorCode.PARAMS_ERROR, "删除应用请求参数异常");

        User loginUser = userService.getUserInSession(request);
        App app = appService.getById(deleteRequest.getId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId()) && !loginUser.getUserRole().equals(UserConstant.ADMIN_ROLE), ErrorCode.NO_AUTH_ERROR, "只能删除自己的应用");

        boolean result = appService.removeById(deleteRequest.getId());
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除应用失败");
        // 关联删除该应用的所有对话历史，失败不影响应用删除
        try {
            chatHistoryService.removeByAppId(deleteRequest.getId());
        } catch (Exception e) {
            log.warn("删除应用对话历史失败, appId={}", deleteRequest.getId(), e);
        }
        return ResultUtils.success(true);
    }

    /**
     * 【用户】根据 id 查看自己的应用详情
     */
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getMyAppVOById(@RequestParam String id, HttpServletRequest request) {
        // 其实这里后端可以不用转化的,但是写都写了(
        // 接收字符串类型的 id，避免前端 number 精度丢失问题
        Long appId;
        try {
            appId = Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "应用 id 格式错误");
        }
        ThrowUtils.throwIf(appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 异常");

        User loginUser = userService.getUserInSession(request);
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId()) && !loginUser.getUserRole().equals(UserConstant.ADMIN_ROLE) && app.getPriority()==0, ErrorCode.NO_AUTH_ERROR, "只能查看自己的应用");

        return ResultUtils.success(appService.getAppVO(app));
    }

    /**
     * 【用户】分页查询自己的应用列表（支持根据名称查询，每页最多 20 个）
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(@RequestBody AppQueryRequest appQueryRequest,
                                                       HttpServletRequest request) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR, "分页查询请求参数为空");

        int pageNum = appQueryRequest.getPageNum();
        int pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多 20 条");

        User loginUser = userService.getUserInSession(request);

        // 构造当前用户自己的应用查询条件
        QueryWrapper queryWrapper = appService.buildMyAppQueryWrapper(appQueryRequest, loginUser.getId());

        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        appVOPage.setRecords(appService.getAppVOList(appPage.getRecords()));
        return ResultUtils.success(appVOPage);
    }

    /**
     * 分页获取精选应用列表
     *
     * @param appQueryRequest 查询请求
     * @return 精选应用列表
     */
    @PostMapping("/good/list/page/vo")
    public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        long pageNum = appQueryRequest.getPageNum();
        // 只查询精选的应用,管理员没有声明按99,否则按照管理员设置的
        if (appQueryRequest.getPriority() != null){
            appQueryRequest.setPriority(appQueryRequest.getPriority());
        }
        else{
            appQueryRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);
        }
        log.info("查询精选应用列表, appQueryRequest: {}", appQueryRequest);

        QueryWrapper queryWrapper = appService.buildAppQueryWrapper(appQueryRequest);
        // 分页查询
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());

        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }


    /**
     * 【管理员】根据 id 删除任意应用
     */
    @PostMapping("/admin/delete")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteAppByAdmin(@RequestBody DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null || deleteRequest.getId() <= 0,
                ErrorCode.PARAMS_ERROR, "删除应用请求参数异常");
        boolean result = appService.removeById(deleteRequest.getId());
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除应用失败");
        // 关联删除该应用的所有对话历史，失败不影响应用删除
        try {
            chatHistoryService.removeByAppId(deleteRequest.getId());
        } catch (Exception e) {
            log.warn("管理员删除应用对话历史失败, appId={}", deleteRequest.getId(), e);
        }
        return ResultUtils.success(true);
    }

    /**
     * 【管理员】根据 id 更新任意应用（支持更新应用名称、应用封面、优先级）
     */
    @PostMapping("/admin/update")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateAppByAdmin(@RequestBody AppAdminUpdateRequest appAdminUpdateRequest) {
        ThrowUtils.throwIf(appAdminUpdateRequest == null || appAdminUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "更新应用请求参数异常");

        App app = appService.getById(appAdminUpdateRequest.getId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");

        if (StrUtil.isNotBlank(appAdminUpdateRequest.getAppName())) {
            app.setAppName(appAdminUpdateRequest.getAppName());
        }
        if (StrUtil.isNotBlank(appAdminUpdateRequest.getCover())) {
            app.setCover(appAdminUpdateRequest.getCover());
        }
        if (appAdminUpdateRequest.getPriority() != null) {
            app.setPriority(appAdminUpdateRequest.getPriority());
        }
        app.setUpdateTime(LocalDateTime.now());

        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "管理员更新应用失败");
        return ResultUtils.success(true);
    }

    /**
     * 【管理员】分页查询应用列表（支持根据除时间外的任何字段查询，每页数量不限）
     */
    @PostMapping("/admin/list/page/vo")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AppVO>> listAppVOByPageAdmin(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR, "分页查询请求参数为空");

        int pageNum = appQueryRequest.getPageNum();
        int pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize <= 0, ErrorCode.PARAMS_ERROR, "pageSize 必须大于 0");

        QueryWrapper queryWrapper = appService.buildAppQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);

        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        appVOPage.setRecords(appService.getAppVOList(appPage.getRecords()));
        return ResultUtils.success(appVOPage);
    }

    /**
     * 【管理员】根据 id 查看应用详情
     */
    @GetMapping("/admin/get/vo")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<AppVO> getAppVOByIdAdmin(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "应用 id 异常");
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        return ResultUtils.success(appService.getAppVO(app));
    }

    /**
     * 应用部署
     *
     * @param appDeployRequest 部署请求
     * @param request          请求
     * @return 部署 URL
     */
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@RequestBody AppDeployRequest appDeployRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appDeployRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = appDeployRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        // 获取当前登录用户
        User loginUser = userService.getUserInSession(request);
        // 调用服务部署应用
        String deployUrl = appService.deployApp(appId, loginUser);
        return ResultUtils.success(deployUrl);
    }

    /**
     * 取消应用部署（删除部署目录）
     *
     * @param appDeployRequest 取消部署请求，仅使用 appId
     * @param request          请求
     * @return 删除成功返回 true，未部署或无目录返回 false
     */
    @PostMapping("/undeploy")
    public BaseResponse<Boolean> undeployApp(@RequestBody AppDeployRequest appDeployRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appDeployRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = appDeployRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        // 获取当前登录用户
        User loginUser = userService.getUserInSession(request);
        // 调用服务取消部署应用
        boolean result = appService.undeployApp(appId, loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 【用户】下载应用对应的生成项目（打包为 zip）
     *
     * @param appId    应用 id
     * @param request  请求
     * @param response 响应（写入 zip）
     */
    @GetMapping("/download/{appId}")
    public void downloadProject(@PathVariable Long appId,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");

        // 校验登录用户
        User loginUser = userService.getUserInSession(request);

        // 获取应用并校验归属
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId())
                        && !UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()),
                ErrorCode.NO_AUTH_ERROR, "只能下载自己的应用");

        // 组装 service 所需参数：appName + 项目路径
        String appName = app.getAppName();

        String codeGenType = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == null && StrUtil.isNotBlank(codeGenType)) {
            try {
                codeGenTypeEnum = CodeGenTypeEnum.valueOf(codeGenType);
            } catch (IllegalArgumentException ignored) {
            }
        }
        ThrowUtils.throwIf(codeGenTypeEnum == null, ErrorCode.PARAMS_ERROR, "应用配置的 codeGenType 无效");

        String projectPath;
        switch (codeGenTypeEnum) {
            case VUE -> projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/" + codeGenTypeEnum.getValue() + "_project_" + appId;
            case HTML, MULTI_FILE -> projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/" + codeGenTypeEnum.getValue() + appId;
            default -> throw new MyException(ErrorCode.PARAMS_ERROR, "暂不支持的 codeGenType");
        }

        projectDownloadService.downloadProject(response, appName, projectPath);
    }


    /**
     * 【用户】提交应用 / 权限申请
     *
     * @param applyRequest 申请参数（appId、appPropriety、operate、applyReason）
     * @param request      请求
     * @return 申请是否创建成功；若用户已是管理员，则返回 false，并通过 message 提示
     */
    @PostMapping("/apply")
    public BaseResponse<Boolean> applyForAppOrAdmin(@RequestBody UserAppApplyRequest applyRequest,
                                                    HttpServletRequest request) {
        ThrowUtils.throwIf(applyRequest == null, ErrorCode.PARAMS_ERROR, "申请参数不能为空");

        User loginUser = userService.getUserInSession(request);

        // 如果用户已经是管理员，直接返回 false 和提示信息
        if (UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            return new BaseResponse<>(20000, false, "您已经是管理员了");
        }

        boolean result = userAppApplyService.createUserAppApply(
                applyRequest.getAppId(),
                applyRequest.getAppPropriety(),
                applyRequest.getOperate(),
                applyRequest.getApplyReason(),
                loginUser
        );
        return ResultUtils.success(result);
    }

    /**
     * 【管理员】回显待处理的用户申请列表（status=0）
     *
     * @param request 请求
     * @return ApplyVO 列表：用户id、用户头像、operate、appId、Reason
     */
    @PostMapping("/apply/list/pending")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<ApplyVO>> listPendingApply(HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        List<ApplyVO> result = userAppApplyService.listPendingApplyVO(loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 【用户】回显自己的申请历史记录
     *
     * @param request 请求
     * @return ApplyHistoryVO 列表：operate、appId、appName、applyReason、status、reviewRemark、createTime、reviewTime
     */
    @PostMapping("/apply/list/my/history")
    public BaseResponse<List<ApplyHistoryVO>> listMyApplyHistory(HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()), ErrorCode.NO_AUTH_ERROR, "仅普通用户可查看申请历史");
        List<ApplyHistoryVO> result = userAppApplyService.listMyApplyHistoryVO(loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 【管理员】同意某条用户申请
     *
     * @param handleRequest 申请处理请求（applyId，来源于待处理列表 ApplyVO.applyId）
     * @param request       请求
     * @return 是否处理成功
     */
    @PostMapping("/apply/agree")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> agreeApply(@RequestBody UserAppApplyHandleRequest handleRequest,
                                            HttpServletRequest request) {
        ThrowUtils.throwIf(handleRequest == null || handleRequest.getApplyId() == null || handleRequest.getApplyId() <= 0,
                ErrorCode.PARAMS_ERROR, "applyId 异常");
        User loginUser = userService.getUserInSession(request);
        boolean result = userAppApplyService.agreeApply(handleRequest.getApplyId(), loginUser);
        return ResultUtils.success(result);
    }

    @PostMapping("/apply/reject")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> rejectApply(@RequestBody UserAppApplyHandleRequest handleRequest,
                                             HttpServletRequest request) {
        ThrowUtils.throwIf(handleRequest == null
                        || handleRequest.getApplyId() == null
                        || handleRequest.getApplyId() <= 0,
                ErrorCode.PARAMS_ERROR, "applyId 异常");
        ThrowUtils.throwIf(StrUtil.isBlank(handleRequest.getReviewRemark()),
                ErrorCode.PARAMS_ERROR, "审核备注不能为空");
        User loginUser = userService.getUserInSession(request);
        boolean result = userAppApplyService.rejectApply(handleRequest.getApplyId(),
                handleRequest.getReviewRemark(), handleRequest.getApplyReason(), loginUser);
        return ResultUtils.success(result);
    }
}
