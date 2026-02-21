package com.dbts.glyahhaigeneratecode.controller;

import cn.hutool.core.bean.BeanUtil;
import com.dbts.glyahhaigeneratecode.annotation.MyRole;
import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.DeleteRequest;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.constant.UserConstant;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.DTO.*;
import com.dbts.glyahhaigeneratecode.model.VO.LoginUserVO;
import com.dbts.glyahhaigeneratecode.model.VO.UserVO;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.mybatisflex.core.paginate.Page;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * 用户 控制层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserService userService;

    /**
     * 用户注册
     * @param user
     * @return 注册用户数据库ID
     */
    @PostMapping("register")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest user) {
        ThrowUtils.throwIf(user==null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        long UserDataBaseID = userService.userRegister(user.getUserAccount(), user.getUserPassword(), user.getCheckPassword());
        return ResultUtils.success(UserDataBaseID);
    }

    /**
     * 用户登录
     * @param user
     * @param request
     * @return 脱敏后的用户信息
     */
    @PostMapping("login")
    public BaseResponse<LoginUserVO> userLogin(@RequestBody UserLoginRequest user, HttpServletRequest request) {
        ThrowUtils.throwIf(user==null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        LoginUserVO loginUserVO = userService.userLogin(user, request);
        return ResultUtils.success(loginUserVO);
    }

    /**
     * 获取当前登录用户
     * @param request
     * @return
     */
    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        return ResultUtils.success(userService.UserSwitch(loginUser));
    }

    /**
     * 用户注销
     * @param request
     * @return
     */
    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "用户取消登录controller层没有request");
        boolean result = userService.userLogout(request);
        return ResultUtils.success(result);
    }

    /**
     * 管理员创建用户
     */
    @PostMapping("/add")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> addUser(@RequestBody UserAddRequest userAddRequest) {
        ThrowUtils.throwIf(userAddRequest == null, ErrorCode.PARAMS_ERROR, "管理员创建用户的时候传入的参数时空的");

        User user = new User();
        BeanUtil.copyProperties(userAddRequest, user);

        // 默认密码 12345678
        final String DEFAULT_PASSWORD = "12345678";
        String encryptPassword = userService.getEncryptPassword(DEFAULT_PASSWORD);
        user.setUserPassword(encryptPassword);

        boolean result = userService.save(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "管理员创建用户失败");

        return ResultUtils.success(user.getId());
    }

    /**
     * 根据 id 获取用户（仅管理员）
     */
    @GetMapping("/get")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<User> getUserById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "管理员根据获取用户id时的id传入有误");

        User user = userService.getById(id);
        log.info("查找的id: {}, user: {}", id, user);

        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "管理员没有成功根据id找到用户");
        return ResultUtils.success(user);
    }

    /**
     * 根据 id 获取包装类
     * 查询回显
     */
    @GetMapping("/get/vo")
    public BaseResponse<UserVO> getUserVOById(long id) {
        BaseResponse<User> response = getUserById(id);
        User user = response.getData();
        return ResultUtils.success(userService.getUserVO(user));
    }

    /**
     * 管理员删除用户
     */
    @PostMapping("/delete")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteUser(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "管理员删除用户时传入的id异常");
        }
        boolean b = userService.removeById(deleteRequest.getId());
        ThrowUtils.throwIf(!b, ErrorCode.OPERATION_ERROR, "管理员删除用户失败");
        return ResultUtils.success(b);
    }

    /**
     * 管理员更新用户
     */
    @PostMapping("/update")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest userUpdateRequest) {
        if (userUpdateRequest == null || userUpdateRequest.getId() == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "管理员更新用户的传递参数异常");
        }
        User user = new User();
        BeanUtil.copyProperties(userUpdateRequest, user);
        boolean result = userService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "管理员更新用户信息出错");

        return ResultUtils.success(true);
    }

    /**
     * 分页获取用户封装列表（仅管理员）
     *
     * @param userQueryRequest 查询请求参数
     */
    @PostMapping("/list/page/vo")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<UserVO>> listUserVOByPage(@RequestBody UserQueryRequest userQueryRequest) {
        ThrowUtils.throwIf(userQueryRequest == null, ErrorCode.PARAMS_ERROR, "管理员试图给用户分页时传入的参数有误");

        long pageNum = userQueryRequest.getPageNum();
        long pageSize = userQueryRequest.getPageSize();
        //这里page从Service层获取到了类型User,所以一个实体类对应了一整套CSM
        Page<User> userPage = userService.page(Page.of(pageNum, pageSize),
                userService.buildUserQueryWrapper(userQueryRequest));

        // 数据脱敏
        //构造List<UserVO>
        Page<UserVO> userVOPage = new Page<>(pageNum, pageSize, userPage.getTotalRow());
        // 脱敏里面的List
        List<UserVO> userVOList = userService.getUserVOList(userPage.getRecords());
        userVOPage.setRecords(userVOList);

        return ResultUtils.success(userVOPage);
    }








    /**
     * 保存用户。
     * 只有管理员可以用
     * @param user 用户
     * @return {@code true} 保存成功，{@code false} 保存失败
     */
    @PostMapping("save")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public boolean save(@RequestBody User user) {
        return userService.save(user);
    }

    /**
     * 根据主键删除用户。
     *
     * @param id 主键
     * @return {@code true} 删除成功，{@code false} 删除失败
     */
    @DeleteMapping("remove/{id}")
    public boolean remove(@PathVariable Long id) {
        return userService.removeById(id);
    }

    /**
     * 根据主键更新用户。
     *
     * @param user 用户
     * @return {@code true} 更新成功，{@code false} 更新失败
     */
    @PutMapping("update")
    public boolean update(@RequestBody User user) {
        return userService.updateById(user);
    }

    /**
     * 查询所有用户。
     *
     * @return 所有数据
     */
    @GetMapping("list")
    public List<User> list() {
        return userService.list();
    }

    /**
     * 根据主键获取用户。
     *
     * @param id 用户主键
     * @return 用户详情
     */
    @GetMapping("getInfo/{id}")
    public User getInfo(@PathVariable Long id) {
        return userService.getById(id);
    }

    /**
     * 分页查询用户。
     *
     * @param page 分页对象
     * @return 分页对象
     */
    @GetMapping("page")
    public Page<User> page(Page<User> page) {
        return userService.page(page);
    }

}
