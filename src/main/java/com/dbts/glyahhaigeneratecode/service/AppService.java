package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.DTO.AppAddRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.AppQueryRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.mybatisflex.core.service.IService;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.VO.AppVO;
import com.mybatisflex.core.query.QueryWrapper;

import java.util.List;

/**
 * 应用 服务层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
public interface AppService extends IService<App> {

    /**
     * 创建应用（使用 insert 以触发雪花 id 生成；createTime/updateTime 为 null 时由数据库默认值填充）
     *
     * @return 新建应用 id
     */
    long createApp(User loginUser, AppAddRequest appAddRequest);

    /**
     * 将应用实体转换为应用视图对象
     *
     * @param app 应用实体
     * @return 应用视图
     */
    AppVO getAppVO(App app);

    /**
     * 将应用实体列表转换为应用视图对象列表
     *
     * @param appList 应用实体列表
     * @return 应用视图列表
     */
    List<AppVO> getAppVOList(List<App> appList);

    /**
     * 根据应用查询请求对象构建查询条件（不包含时间字段）
     *
     * @param appQueryRequest 应用查询请求参数
     * @return 封装好条件的 QueryWrapper
     */
    QueryWrapper buildAppQueryWrapper(AppQueryRequest appQueryRequest);

    /**
     * 构建当前登录用户自己的应用分页查询条件
     *
     * @param appQueryRequest 应用查询请求参数（仅使用名称与排序字段）
     * @param userId          当前用户 id
     * @return 封装好条件的 QueryWrapper
     */
    QueryWrapper buildMyAppQueryWrapper(AppQueryRequest appQueryRequest, Long userId);


    /**
     * 部署应用
     *
     * @param appId 应用 id
     * @return 访问部署应用的url
     */
    String deployApp(Long appId, User loginUser);

    /**
     * 取消部署应用（删除部署目录）
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户
     * @return 删除成功返回 true，未部署或无目录返回 false
     */
    boolean undeployApp(Long appId, User loginUser);
}
