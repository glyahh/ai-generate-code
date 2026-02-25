package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.RandomUtil;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.mybatisflex.spring.service.impl.ServiceImpl;
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
import org.springframework.stereotype.Service;

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
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    @Resource
    private UserService userService;

    @Override
    public long createApp(App app) {
        if (app == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "在Service层: 创建 App 时传入的参数为空");
        }
        // 必须用 insert 而非 insertSelective：id 为 null 时 insertSelective 会跳过 id 列，
        // 不会触发 @Id(snowFlakeId) 的生成逻辑，数据库会用自增返回 1,2,3...
        int insert = this.mapper.insert(app);
        if (insert < 1) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "创建应用失败");
        }
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
        // 更稳妥的获取枚举类
        if (codeGenTypeEnum == null && StrUtil.isNotBlank(codeGenType)) {
            try {
                codeGenTypeEnum = CodeGenTypeEnum.valueOf(codeGenType);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (codeGenTypeEnum == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "应用配置的 codeGenType 无效");
        }
        // 自动处理分隔符
        Path sourceDir = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, codeGenTypeEnum.getValue() + "_" + appId);

        // 6. 校验路径下的文件是否存在
        if (!Files.isDirectory(sourceDir)) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用代码产物目录不存在，请先生成代码再部署");
        }
        Path indexPath = sourceDir.resolve("index.html");
        if (!Files.isRegularFile(indexPath)) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用代码产物缺少 index.html, 请删除再试~");
        }
        if (CodeGenTypeEnum.MULTI_FILE.equals(codeGenTypeEnum)) {
            if (!Files.isRegularFile(sourceDir.resolve("style.css"))) {
                throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用代码产物缺少 style.css, 请删除再试~");
            }
            if (!Files.isRegularFile(sourceDir.resolve("script.js"))) {
                throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用代码产物缺少 script.js, 请删除再试~");
            }
        }

        // 7. 复制文件到deploy文件夹下
        Path deployDir = Paths.get(AppConstant.CODE_DEPLOY_ROOT_DIR, deployKey);
        try {
            FileUtil.copyContent(sourceDir.toFile(), deployDir.toFile(), true);
        } catch (Exception e) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "部署文件复制失败: " + e.getMessage());
        }

        // 8. 更新数据库
        app.setDeployedTime(LocalDateTime.now());
        app.setUpdateTime(LocalDateTime.now());
        app.setDeployKey(deployKey);
        boolean updateResult = this.updateById(app);
        if (!updateResult) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "部署信息更新失败");
        }

        // 9. 返回结果
        String host = AppConstant.CODE_DEPLOY_HOST;
        if (StrUtil.isBlank(host)) {
            host = "http://localhost";
        }
        // 如果末尾有/,移除,否则不变
        host = StrUtil.removeSuffix(host, "/");
        return host + "/" + deployKey + "/";
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
}
