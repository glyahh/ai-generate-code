package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.DTO.UserLoginRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.UserQueryRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.LoginUserVO;
import com.dbts.glyahhaigeneratecode.model.VO.UserVO;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 用户 服务层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
public interface UserService extends IService<User> {
    /**
     * 用户注册
     *
     * @param userAccount   用户账户
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @return 新用户 id
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 用户脱敏转化
     * @param user
     * @return
     */
     LoginUserVO UserSwitch (User user);

    /**
     * 用户登录
     *
      * @param user 用户登录请求
     *  @param request 登录请求
     * @return 脱敏后的用户信息
     */
     LoginUserVO userLogin(UserLoginRequest user, HttpServletRequest request);

     /**
     * 从请求头中获取用户信息
     */
     User getUserInSession (HttpServletRequest request);

     /**
      * 将用户实体转换为用户视图对象（脱敏）
      *
      * @param user 用户实体
      * @return 脱敏后的用户视图
      */
     UserVO getUserVO(User user);

     /**
      * 将用户实体列表转换为用户视图对象列表（批量脱敏）
      *
      * @param userList 用户实体列表
      * @return 脱敏后的用户视图列表
      */
     List<UserVO> getUserVOList(List<User> userList);

     /**
     * 用户注销
     *
     * @param request 登出请求
     * @return 登出结果
     */
      boolean userLogout(HttpServletRequest request);

     /**
      * 根据用户查询请求对象构建查询条件
      *
      * @param userQueryRequest 用户查询请求参数
      * @return 封装好条件的 QueryWrapper
      */
     QueryWrapper buildUserQueryWrapper(UserQueryRequest userQueryRequest);

    /**
     * 获取加密后的密码
     * @param Password
     * @return
     */
    String getEncryptPassword(String Password);
}
