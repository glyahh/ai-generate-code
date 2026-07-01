package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.LoopService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LoopController 新增端点（marketImport / importFile）单元测试。
 * <p>只覆盖本次 Loop 分支新增的两个端点，已有端点不重复测。</p>
 */
@ExtendWith(MockitoExtension.class)
class LoopControllerTest {

    @Mock UserService userService;
    @Mock LoopService loopService;
    @Mock HttpServletRequest request;
    @InjectMocks LoopController controller;

    private final User fakeUser = new User() {{ setId(42L); }};

    // ==================== marketImport ====================

    @Test
    void marketImport_shouldReturnNewLoopId() {
        when(userService.getUserInSession(request)).thenReturn(fakeUser);
        when(loopService.marketImport(100L, 42L)).thenReturn(200L);

        BaseResponse<Long> resp = controller.marketImport(100L, request);

        assertEquals(200L, resp.getData());
        verify(loopService).marketImport(100L, 42L);
    }

    // ==================== importFile ====================

    @Test
    void importFile_shouldRejectEmptyFile() {
        MultipartFile empty = new MockMultipartFile("file", new byte[0]);
        Exception ex = assertThrows(RuntimeException.class,
                () -> controller.importFile(empty, request));
        assertTrue(ex.getMessage().contains("不能为空"));
    }

    @Test
    void importFile_shouldRejectInvalidExtension() {
        MultipartFile bad = new MockMultipartFile("file", "script.txt", "text/plain", "content".getBytes());
        Exception ex = assertThrows(RuntimeException.class,
                () -> controller.importFile(bad, request));
        assertTrue(ex.getMessage().contains("仅支持"));
    }

    @Test
    void importFile_shouldRejectNoExtension() {
        MultipartFile noExt = new MockMultipartFile("file", "noext", "text/plain", "content".getBytes());
        Exception ex = assertThrows(RuntimeException.class,
                () -> controller.importFile(noExt, request));
        assertTrue(ex.getMessage().contains("仅支持"));
    }

    @Test
    void importFile_shouldParseJsonLoopFormat() {
        when(userService.getUserInSession(request)).thenReturn(fakeUser);
        String jsonContent = "{\"templateId\":\"standard_v1\",\"name\":\"我的技能\",\"description\":\"示例\"," +
                "\"steps\":[{\"key\":\"role\",\"label\":\"角色设定\",\"content\":\"你是一个专家\"}]}";
        MultipartFile file = new MockMultipartFile("file", "skill.json", "application/json",
                jsonContent.getBytes(StandardCharsets.UTF_8));
        when(loopService.addLoop(any(), eq(42L))).thenReturn(300L);

        BaseResponse<Long> resp = controller.importFile(file, request);

        assertEquals(300L, resp.getData());
        verify(loopService).addLoop(any(), eq(42L));
    }

    @Test
    void importFile_shouldParseJsonAndUseFilenameWhenNoName() {
        when(userService.getUserInSession(request)).thenReturn(fakeUser);
        String jsonContent = "{\"templateId\":\"standard_v1\"," +
                "\"steps\":[{\"key\":\"role\",\"label\":\"角色设定\",\"content\":\"你是一个专家\"}]}";
        MultipartFile file = new MockMultipartFile("file", "my_skill.json", "application/json",
                jsonContent.getBytes(StandardCharsets.UTF_8));
        ArgumentCaptor<com.dbts.glyahhaigeneratecode.model.DTO.LoopAddRequest> captor =
                ArgumentCaptor.forClass(com.dbts.glyahhaigeneratecode.model.DTO.LoopAddRequest.class);
        when(loopService.addLoop(captor.capture(), eq(42L))).thenReturn(300L);

        controller.importFile(file, request);

        assertEquals("my_skill", captor.getValue().getLoopName());
    }

    @Test
    void importFile_shouldFallbackToImportLoopForInvalidJson() {
        when(userService.getUserInSession(request)).thenReturn(fakeUser);
        // 以 `{` 开头但不是合法 Loop JSON（缺少 templateId）
        String content = "{\"foo\":\"bar\"}";
        MultipartFile file = new MockMultipartFile("file", "skill.md", "text/markdown",
                content.getBytes(StandardCharsets.UTF_8));
        when(loopService.importLoop(anyString(), eq(42L))).thenReturn(400L);

        BaseResponse<Long> resp = controller.importFile(file, request);

        assertEquals(400L, resp.getData());
        verify(loopService).importLoop(content, 42L);
    }

    @Test
    void importFile_shouldFallbackToImportLoopForMdContent() {
        when(userService.getUserInSession(request)).thenReturn(fakeUser);
        String mdContent = "---\nname: 我的技能\ndescription: 测试\n---\n## 角色设定\n你是一个专家";
        MultipartFile file = new MockMultipartFile("file", "skill.md", "text/markdown",
                mdContent.getBytes(StandardCharsets.UTF_8));
        when(loopService.importLoop(anyString(), eq(42L))).thenReturn(500L);

        BaseResponse<Long> resp = controller.importFile(file, request);

        assertEquals(500L, resp.getData());
        verify(loopService).importLoop(mdContent, 42L);
    }
}
