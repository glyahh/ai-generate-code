package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.RandomUtil;
import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorRoutineService;
import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorRoutineServiceFactory;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.core.Builder.vueProjectBuilder;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.manage.OssManager;
import com.dbts.glyahhaigeneratecode.model.DTO.AppAddRequest;
import com.dbts.glyahhaigeneratecode.service.ScreenshotService;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.mybatisflex.core.update.UpdateChain;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.mapper.AppMapper;
import com.dbts.glyahhaigeneratecode.model.DTO.AppQueryRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.AppVO;
import com.dbts.glyahhaigeneratecode.model.VO.UserVO;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Service
@Slf4j
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    @Resource
    private UserService userService;

    @Resource
    private vueProjectBuilder vueProjectBuilder;

//    @Resource
//    private aiCodeGeneratorRoutineService aiCodeGeneratorRoutine;

    @Resource
    private aiCodeGeneratorRoutineServiceFactory aiCodeGeneratorRoutineServiceFactory;

//    @Resource
//    private AppService appService;

    @Resource
    private ScreenshotService screenshotService;

    @Resource
    private OssManager ossManager;

    @Override
    public long createApp (User loginUser, AppAddRequest appAddRequest) {
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        if (app.getIsBeta() == null) {
            app.setIsBeta(0);
        }

        // 优先使用前端显式选择的 codeGenType，避免 LLM 路由器覆盖用户意图。
        // 如前端传入 vue_project（但后端 value 约定为 vue），CodeGenTypeEnum#getEnumByValue 会做兼容映射。
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(appAddRequest.getCodeGenType());
        if (codeGenTypeEnum == null) {
            aiCodeGeneratorRoutineService aiCodeGeneratorRoutineService = aiCodeGeneratorRoutineServiceFactory.createAiCodeGeneratorRoutineService();
            codeGenTypeEnum = aiCodeGeneratorRoutineService.aiCodeGeneratorRoutine(app.getInitPrompt());
        }
        if (codeGenTypeEnum == null) {
            // 显式 if：便于静态分析准确收窄空值分支
            throw new MyException(ErrorCode.PARAMS_ERROR, "生成类型无法确定");
        }
        app.setCodeGenType(codeGenTypeEnum.getValue());

        //这里最好使用这个save,不要mapper中的insert,否则id会因为没有声明而被覆盖雪花算法的值,而且其他未声明的字段会报数据库不能非空
        boolean save = this.save(app);
        ThrowUtils.throwIf(!save, ErrorCode.OPERATION_ERROR, "创建应用失败");
        return app.getId();
    }

    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "传入的 App 为空，无法转换为 AppVO");
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        appVO.setHasGeneratedCode(hasGeneratedCode(app));
        return appVO;
    }

    /**
     * 判断应用是否已有生成代码（code_output 下存在对应目录及 index.html）
     */
    private boolean hasGeneratedCode(App app) {
        if (app == null || app.getId() == null) {
            return false;
        }
        String codeGenType = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == null && StrUtil.isNotBlank(codeGenType)) {
            try {
                codeGenTypeEnum = CodeGenTypeEnum.valueOf(codeGenType);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (codeGenTypeEnum == null) {
            return false;
        }
        Path sourceDir = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, codeGenTypeEnum.getValue() + "_" + app.getId());
        if (!Files.isDirectory(sourceDir)) {
            return false;
        }
        return Files.isRegularFile(sourceDir.resolve("index.html"));
    }

    /**
     * 这里使用map分转好id,userVo一起返回list,避免循环查询
     * @param appList 应用实体列表
     * @return
     */
    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (appList == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "传入的 AppList 为空，无法转换为 AppVOList");
        }
        if (appList.isEmpty()) {
            return Collections.emptyList();
        }

        // 因为1000个app可能只对应200个用户,这样只要查200次,再通过map存储映射到UserVo里去
        // 收集所有 userId，批量查用户并封装为 UserVO，再按 userId 组成 Map
        List<Long> userIds = appList.stream()
                .map(App::getUserId)
                .filter(Objects::nonNull)
                .distinct() // 去除List流中的重复元素
                .collect(Collectors.toList());

        Map<Long, UserVO> userIdToUserVO = Collections.emptyMap();

        if (!userIds.isEmpty()) {
            List<User> users = userService.listByIds(userIds);
            List<UserVO> userVOList = userService.getUserVOList(users);
            userIdToUserVO = userVOList.stream()
                    .collect(Collectors.toMap(UserVO::getId, userVO -> userVO));
        }
        Map<Long, UserVO> finalUserIdToUserVO = userIdToUserVO;

        return appList.stream()
                .map(app -> {
                    AppVO appVO = new AppVO();
                    BeanUtil.copyProperties(app, appVO);
                    appVO.setUserVO(finalUserIdToUserVO.get(app.getUserId()));
                    appVO.setHasGeneratedCode(hasGeneratedCode(app));
                    return appVO;
                })
                .collect(Collectors.toList());
    }

    @Override
    public QueryWrapper buildAppQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "构建 App QueryWrapper 时，appQueryRequest 为空");
        }

        QueryWrapper queryWrapper = new QueryWrapper();

        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        Integer isDelete = appQueryRequest.getIsDelete();

        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();

        if (id != null) {
            queryWrapper.eq(App::getId, id);
        }
        if (StrUtil.isNotBlank(appName)) {
            queryWrapper.like(App::getAppName, appName);
        }
        if (StrUtil.isNotBlank(cover)) {
            queryWrapper.eq(App::getCover, cover);
        }
        if (StrUtil.isNotBlank(initPrompt)) {
            queryWrapper.like(App::getInitPrompt, initPrompt);
        }
        if (StrUtil.isNotBlank(codeGenType)) {
            queryWrapper.eq(App::getCodeGenType, codeGenType);
        }
        if (StrUtil.isNotBlank(deployKey)) {
            queryWrapper.eq(App::getDeployKey, deployKey);
        }
        if (priority != null) {
            // 优先级大于等于给定值（例如精选应用：priority >= GOOD_APP_PRIORITY）
            queryWrapper.ge(App::getPriority, priority);
        }
        if (userId != null) {
            queryWrapper.eq(App::getUserId, userId);
        }
        if (isDelete != null) {
            queryWrapper.eq(App::getIsDelete, isDelete);
        }

        if (StrUtil.isNotBlank(sortField) && StrUtil.isNotBlank(sortOrder)) {
            queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
        }

        return queryWrapper;
    }

    @Override
    public QueryWrapper buildMyAppQueryWrapper(AppQueryRequest appQueryRequest, Long userId) {
        if (appQueryRequest == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "构建当前用户应用查询条件时，请求参数为空");
        }
        if (userId == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "构建当前用户应用查询条件时，用户 id 为空");
        }

        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(App::getUserId, userId);

        if (StrUtil.isNotBlank(appQueryRequest.getAppName())) {
            queryWrapper.like(App::getAppName, appQueryRequest.getAppName());
        }
        if (StrUtil.isNotBlank(appQueryRequest.getSortField()) && StrUtil.isNotBlank(appQueryRequest.getSortOrder())) {
            queryWrapper.orderBy(appQueryRequest.getSortField(), "ascend".equals(appQueryRequest.getSortOrder()));
        } else {
            queryWrapper.orderBy("createTime", false);
        }

        return queryWrapper;
    }


    @Override
    public String deployApp(Long appId, User loginUser) {
        // 1. 权限校验
        if (appId == null || appId <= 0) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "应用 id 异常");
        }

        // 2. 获取应用信息
        App app = this.getById(appId);
        if (app == null) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }

        // 3. 校验权限/是否本人部署
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new MyException(ErrorCode.NO_AUTH_ERROR, "只能部署自己的应用");
        }

        // 4. 校验deployKey是否存在,不存在则生成deployKey(8为 字母+数字)
        String deployKey = app.getDeployKey();
        if (StrUtil.isBlank(deployKey)) {
            deployKey = generateUniqueDeployKey();
            app.setDeployKey(deployKey);
        }

        // 5. 根据应用文件类型超找到本应该的路径 (参考路径生成代码)
        String codeGenType = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == null && StrUtil.isNotBlank(codeGenType)) {
            try {
                codeGenTypeEnum = CodeGenTypeEnum.valueOf(codeGenType);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (codeGenTypeEnum == null) {
            log.info("codeGenType = {}", codeGenType);
            throw new MyException(ErrorCode.PARAMS_ERROR, "应用配置的 codeGenType 无效");
        }

        // 生成对应的data-output中的文件夹名
        Path sourceDirPath = null;
        switch (codeGenTypeEnum){
            case VUE -> sourceDirPath = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, codeGenTypeEnum.getValue() + "_project_" + appId);
            case HTML, MULTI_FILE -> sourceDirPath = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, codeGenTypeEnum.getValue() + "_" + appId);
        }
        if (sourceDirPath == null) {
            // 防御式：避免枚举扩展后 switch 未覆盖导致 NPE
            throw new MyException(ErrorCode.PARAMS_ERROR, "应用 codeGenType 无法确定部署路径");
        }

        // 6. 校验路径下的文件是否存在
        if (!Files.isDirectory(sourceDirPath)) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用代码产物目录不存在，请先生成代码再部署");
        }
        Path indexPath = sourceDirPath.resolve("index.html");
        if (!Files.isRegularFile(indexPath)) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用代码产物缺少 index.html, 请删除再试~");
        }
        if (CodeGenTypeEnum.MULTI_FILE.equals(codeGenTypeEnum)) {
            if (!Files.isRegularFile(sourceDirPath.resolve("style.css"))) {
                throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用代码产物缺少 style.css, 请删除再试~");
            }
            if (!Files.isRegularFile(sourceDirPath.resolve("script.js"))) {
                throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用代码产物缺少 script.js, 请删除再试~");
            }
        }

        // 6. 检查源目录是否存在
        File sourceDir = new File(String.valueOf(sourceDirPath));
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "应用代码不存在，请先生成代码");
        }


        // 7. Vue 项目特殊处理：执行npm构建
        if (codeGenTypeEnum == CodeGenTypeEnum.VUE) {
            // Vue 项目需要构建
            boolean buildSuccess = vueProjectBuilder.buildProject(String.valueOf(sourceDirPath));
            ThrowUtils.throwIf(!buildSuccess, ErrorCode.SYSTEM_ERROR, "Vue 项目构建失败，请检查代码和依赖");
            // 检查 目标目录下 vue文件是否存在
            File distDir = new File(String.valueOf(sourceDirPath), "dist");
            ThrowUtils.throwIf(!distDir.exists(), ErrorCode.SYSTEM_ERROR, "Vue 项目构建完成但未生成 dist 目录");
            // 将 dist 目录作为部署源
            sourceDir = distDir;
            log.info("Vue 项目构建成功，将部署 dist 目录: {}", distDir.getAbsolutePath());
        }

        // 8. 复制文件到部署目录
        String deployDirPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;

        // 9. 复制文件到deploy文件夹下
        Path deployDir = Paths.get(AppConstant.CODE_DEPLOY_ROOT_DIR, deployKey);
        try {
            FileUtil.copyContent(sourceDir, deployDir.toFile(), true);
        } catch (Exception e) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "部署文件复制失败: " + e.getMessage());
        }

        //10. 异步生成应用截图保存到阿里云
        generateAppScreenshotAsync(appId, deployDirPath);

        // 11. 更新数据库
        app.setDeployedTime(LocalDateTime.now());
        app.setUpdateTime(LocalDateTime.now());
        app.setDeployKey(deployKey);
        boolean updateResult = this.updateById(app);
        if (!updateResult) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "部署信息更新失败");
        }

        // 12. 返回 deployKey；最终 URL 由控制层按“当前请求域名+端口+context-path”拼接，避免返回错误主机。
        return deployKey;
    }

    /**
     * 取消部署应用：删除部署目录并清空应用的部署信息
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户
     * @return 已清理部署信息（含删除目录或仅校正库内字段）返回 true；从未部署过（无 deployKey）返回 false
     */
    @Override
    public boolean undeployApp(Long appId, User loginUser) {
        // 1. 权限校验
        if (appId == null || appId <= 0) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "应用 id 异常");
        }

        // 2. 获取应用信息
        App app = this.getById(appId);
        if (app == null) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }

        // 3. 校验权限 / 是否本人操作
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new MyException(ErrorCode.NO_AUTH_ERROR, "只能操作自己的应用");
        }

        // 4. 验证是否已部署（deployKey 存在且部署目录存在）
        String deployKey = app.getDeployKey();
        if (StrUtil.isBlank(deployKey)) {
            // 没有部署标识，视为未部署
            return false;
        }

        Path deployDir = Paths.get(AppConstant.CODE_DEPLOY_ROOT_DIR, deployKey);
        // 5. 下线同时删除 OSS 中该应用截图（cover）
        // 说明：cover 由部署时异步截图写入；下线后该截图通常已无意义，避免对象存储堆积。
        // 备注：即使部署目录不存在（被手动删/异常丢失），也应清理 cover 与 deployKey，保证状态可回到“未部署”。
        String cover = app.getCover();
        if (StrUtil.isNotBlank(cover)) {
            boolean deleted = ossManager.deleteFile(cover);
            ThrowUtils.throwIf(!deleted, ErrorCode.OPERATION_ERROR, "删除 OSS 截图失败，请检查对象存储权限配置");
        }

        // 6. 删除部署目录（目录不存在则忽略，仅做字段修正）
        if (Files.isDirectory(deployDir)) {
            try {
                FileUtil.del(deployDir.toFile());
            } catch (Exception e) {
                throw new MyException(ErrorCode.SYSTEM_ERROR, "取消部署失败: " + e.getMessage());
            }
        }

        // 7. 清理应用上的部署信息（强制将字段置空落库：updateById 默认可能忽略 null）
        boolean cleared = UpdateChain.create(this.getMapper())
                .set("deployKey", null)
                .set("deployedTime", null)
                .set("cover", null)
                .set("updateTime", LocalDateTime.now())
                .eq("id", appId)
                .update();
        ThrowUtils.throwIf(!cleared, ErrorCode.OPERATION_ERROR, "取消部署信息更新失败");
        return true;
    }

    private String generateUniqueDeployKey() {
        // 尝试多次避免极小概率碰撞
        for (int i = 0; i < 10; i++) {
            String candidate = RandomUtil.randomString("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 8);
            QueryWrapper queryWrapper = new QueryWrapper();
            queryWrapper.eq(App::getDeployKey, candidate);
            long count = this.count(queryWrapper);
            if (count == 0) {
                return candidate;
            }
        }
        throw new MyException(ErrorCode.SYSTEM_ERROR, "生成 deployKey 失败，请重试");
    }



    /**
     * 异步生成应用截图并更新封面
     *
     * @param appId  应用ID
     * @param appUrl 应用访问URL
     */
    @Override
    public void generateAppScreenshotAsync(Long appId, String appUrl) {
        // 使用虚拟线程异步执行
        Thread.startVirtualThread(() -> {
            // 调用截图服务生成截图并上传
            String screenshotUrl = screenshotService.generateAndUploadScreenshot(appUrl);
            // 更新应用封面字段
            App updateApp = new App();
            updateApp.setId(appId);
            updateApp.setCover(screenshotUrl);
            boolean updated = this.updateById(updateApp);
            ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "更新应用封面字段失败");
        });
    }

}
