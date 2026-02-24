package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.mapper.AppMapper;
import com.dbts.glyahhaigeneratecode.model.DTO.AppQueryRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.AppVO;
import com.dbts.glyahhaigeneratecode.model.VO.UserVO;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

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
        return appVO;
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
            queryWrapper.gt(App::getPriority, priority);
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

}
