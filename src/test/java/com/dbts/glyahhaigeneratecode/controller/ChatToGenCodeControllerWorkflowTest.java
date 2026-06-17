package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.ChatToGenCode;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatToGenCodeControllerWorkflowTest {

    @Mock
    private ChatToGenCode chatToGenCodeService;
    @Mock
    private UserService userService;
    @Mock
    private HttpServletRequest request;

    private ChatToGenCodeController controller;
    private User loginUser;

    @BeforeEach
    void setUp() {
        controller = new ChatToGenCodeController(chatToGenCodeService, userService);
        loginUser = new User();
        loginUser.setId(123L);
    }

    @Test
    void chatToGenCodeByWorkflow_shouldReturnDoneEventAndReuseSseWrapper() {
        when(userService.getUserInSession(request)).thenReturn(loginUser);
        when(chatToGenCodeService.chatToGenCodeByWorkflow(1L, "hello", loginUser))
                .thenReturn(Flux.just("chunk-1", "chunk-2"));

        List<ServerSentEvent<String>> events = controller
                .chatToGenCodeByWorkflow(1L, "hello", request)
                .collectList()
                .block();

        assertNotNull(events);
        assertEquals(3, events.size());
        assertEquals("chunk-1", events.get(0).data());
        assertEquals("chunk-2", events.get(1).data());
        assertEquals("done", events.get(2).event());
        verify(chatToGenCodeService).chatToGenCodeByWorkflow(1L, "hello", loginUser);
    }

    @Test
    void chatToGenCode_shouldKeepOldEndpointBehavior() {
        when(userService.getUserInSession(request)).thenReturn(loginUser);
        when(chatToGenCodeService.chatToGenCode(2L, "legacy", null, loginUser))
                .thenReturn(Flux.just("legacy-chunk"));

        List<ServerSentEvent<String>> events = controller
                .chatToGenCode(2L, "legacy", null, request)
                .collectList()
                .block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("legacy-chunk", events.get(0).data());
        assertEquals("done", events.get(1).event());
        verify(chatToGenCodeService).chatToGenCode(2L, "legacy", null, loginUser);
    }
}
