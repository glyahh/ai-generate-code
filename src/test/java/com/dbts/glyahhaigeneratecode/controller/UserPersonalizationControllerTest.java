package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.model.DTO.UserPersonalizationUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.UserPersonalizationVO;
import com.dbts.glyahhaigeneratecode.service.UserPersonalizationService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserPersonalizationControllerTest {

    @Mock UserPersonalizationService userPersonalizationService;
    @Mock UserService userService;
    @Mock HttpServletRequest request;
    UserPersonalizationController controller;

    @BeforeEach
    void setUp() {
        controller = new UserPersonalizationController(userPersonalizationService, userService);
    }

    @Test void getPersonalization_returnsVO() {
        User u = new User(); u.setId(1L);
        when(userService.getUserInSession(any())).thenReturn(u);
        UserPersonalizationVO mock = new UserPersonalizationVO();
        mock.setAppStyle("modern");
        when(userPersonalizationService.getByUserId(1L)).thenReturn(mock);
        assertEquals("modern", controller.getPersonalization(request).getData().getAppStyle());
    }

    @Test void updatePersonalization_succeeds() {
        User u = new User(); u.setId(1L);
        when(userService.getUserInSession(any())).thenReturn(u);
        UserPersonalizationUpdateRequest req = new UserPersonalizationUpdateRequest();
        req.setAppStyle("dark");
        assertTrue(controller.updatePersonalization(req, request).getData());
    }
}