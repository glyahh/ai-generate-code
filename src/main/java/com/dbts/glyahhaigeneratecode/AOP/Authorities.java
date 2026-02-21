package com.dbts.glyahhaigeneratecode.AOP;

import com.dbts.glyahhaigeneratecode.annotation.MyRole;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.enums.UserRoleEnum;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;


@Aspect
@Component
@RequiredArgsConstructor
public class Authorities {

    private  final UserService userService;

    @Around("@annotation(MyRole)")
    public Object checkAuthority(ProceedingJoinPoint proceedingJoinPoint, MyRole MyRole) throws Throwable {
        // 获取当前注解中的权限
        String role = MyRole.role();

        //获取request
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = null;
        if (requestAttributes != null) {
            request = ((ServletRequestAttributes) requestAttributes).getRequest();
        }

        // 获取用户角色
        User user = userService.getUserInSession(request);
        if (user==null){
            return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR, "无法从request中根据session获取user");
        }
        String userRole = user.getUserRole();

        UserRoleEnum userNowRole = UserRoleEnum.getEnumByValue(userRole);
        UserRoleEnum userNeedRole = UserRoleEnum.getEnumByValue(role);

        // 没有权限,直接拒绝
        if (userNowRole == null){
            return ResultUtils.error(ErrorCode.NO_AUTH_ERROR, "用户没有权限");
        }

        // 当不需要权限时
        if (userNeedRole == null){
            return proceedingJoinPoint.proceed();
        }

        // 当需要管理员权限时,用户有没有权限时
        if (UserRoleEnum.ADMIN.equals(userNeedRole) && !UserRoleEnum.ADMIN.equals(userNowRole)){
            return ResultUtils.error(ErrorCode.NO_AUTH_ERROR, "用户没有管理员权限");
        }

        return proceedingJoinPoint.proceed();
    }
}
