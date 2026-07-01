package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.AppLoopService;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AppLoopController 新增逻辑（bindFromMyLoops / addFromMyLoop 的归属校验）单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AppLoopControllerTest {

    @Mock UserService userService;
    @Mock AppService appService;
    @Mock AppLoopService appLoopService;
    @Mock HttpServletRequest request;
    @InjectMocks AppLoopController controller;

    private final User fakeUser = new User() {{ setId(42L); }};
    private final App fakeApp = new App() {{ setId(100L); setUserId(42L); }};

    @Test
    void bindFromMyLoops_shouldPassOwnershipAndDelegate() {
        when(userService.getUserInSession(request)).thenReturn(fakeUser);
        when(appService.getById(100L)).thenReturn(fakeApp);

        BaseResponse<Void> resp = controller.bindFromMyLoops(100L, List.of(1L, 2L), request);

        assertNotNull(resp);
        verify(appLoopService).bindLoopsFromMyLoop(100L, List.of(1L, 2L), 42L, "creation");
    }

    @Test
    void bindFromMyLoops_shouldThrowWhenAppNotFound() {
        when(userService.getUserInSession(request)).thenReturn(fakeUser);
        when(appService.getById(999L)).thenReturn(null);

        Exception ex = assertThrows(RuntimeException.class,
                () -> controller.bindFromMyLoops(999L, List.of(1L), request));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void bindFromMyLoops_shouldThrowWhenNotAppOwner() {
        User otherUser = new User() {{ setId(99L); }};
        when(userService.getUserInSession(request)).thenReturn(otherUser);
        when(appService.getById(100L)).thenReturn(fakeApp);

        Exception ex = assertThrows(RuntimeException.class,
                () -> controller.bindFromMyLoops(100L, List.of(1L), request));
        assertTrue(ex.getMessage().contains("无权操作"));
    }

    @Test
    void addFromMyLoop_shouldPassOwnershipAndDelegate() {
        when(userService.getUserInSession(request)).thenReturn(fakeUser);
        when(appService.getById(100L)).thenReturn(fakeApp);

        BaseResponse<Void> resp = controller.addFromMyLoop(100L, 1L, request);

        assertNotNull(resp);
        verify(appLoopService).bindLoopsFromMyLoop(100L, List.of(1L), 42L, "chat");
    }

    @Test
    void addFromMyLoop_shouldThrowWhenNotAppOwner() {
        User otherUser = new User() {{ setId(99L); }};
        when(userService.getUserInSession(request)).thenReturn(otherUser);
        when(appService.getById(100L)).thenReturn(fakeApp);

        Exception ex = assertThrows(RuntimeException.class,
                () -> controller.addFromMyLoop(100L, 1L, request));
        assertTrue(ex.getMessage().contains("无权操作"));
    }
}
