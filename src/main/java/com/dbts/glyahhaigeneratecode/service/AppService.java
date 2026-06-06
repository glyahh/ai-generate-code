package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.DTO.AppAddRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.AppAdminUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.AppQueryRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.AppUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.mybatisflex.core.service.IService;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.VO.AppVO;
import com.dbts.glyahhaigeneratecode.model.VO.ProjectFileVO;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.servlet.http.HttpServletResponse;

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
     * @return 部署标识 deployKey（由控制层拼接最终访问 URL，避免环境端口/上下文路径不一致）
     */
    String deployApp(Long appId, User loginUser);

    /**
     * 取消部署应用（删除部署目录）
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户
     * @return 已清理部署信息返回 true；从未部署（无 deployKey）返回 false
     */
    boolean undeployApp(Long appId, User loginUser);

    /**
     * 异步生成应用截图并更新封面
     *
     * @param appId  应用ID
     * @param appUrl 应用访问URL
     */
    public void generateAppScreenshotAsync(Long appId, String appUrl);

    /**
     * 【用户】根据 id 修改自己的应用（目前只支持修改应用名称）
     */
    Boolean updateMyApp(User loginUser, AppUpdateRequest appUpdateRequest);

    /**
     * 【用户】根据 id 删除自己的应用
     */
    Boolean deleteMyApp(User loginUser, Long appId);

    /**
     * 【用户】根据 id 查看自己的应用详情
     */
    AppVO getMyAppVOById(User loginUser, String id);

    /**
     * 【用户】分页查询自己的应用列表
     */
    Page<AppVO> listMyAppVOByPage(User loginUser, AppQueryRequest appQueryRequest);

    /**
     * 分页获取精选应用列表
     */
    Page<AppVO> listGoodAppVOByPage(AppQueryRequest appQueryRequest);

    /**
     * 【管理员】根据 id 删除任意应用
     */
    Boolean deleteAppByAdmin(Long appId);

    /**
     * 【管理员】根据 id 更新任意应用（支持更新应用名称、应用封面、优先级）
     */
    Boolean updateAppByAdmin(AppAdminUpdateRequest appAdminUpdateRequest);

    /**
     * 【管理员】分页查询应用列表
     */
    Page<AppVO> listAppVOByPageAdmin(AppQueryRequest appQueryRequest);

    /**
     * 【管理员】根据 id 查看应用详情
     */
    AppVO getAppVOByIdAdmin(long id);

    /**
     * 【用户】下载应用对应的生成项目（打包为 zip）
     */
    void downloadProject(User loginUser, Long appId, HttpServletResponse response);

    /**
     * 【用户】获取应用项目文件列表（回显用）
     */
    List<ProjectFileVO> getProjectFiles(User loginUser, Long appId);
}
