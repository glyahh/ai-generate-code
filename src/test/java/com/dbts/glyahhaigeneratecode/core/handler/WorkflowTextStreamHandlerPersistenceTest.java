package com.dbts.glyahhaigeneratecode.core.handler;

import com.dbts.glyahhaigeneratecode.constant.ChatHistoryConstant;
import com.dbts.glyahhaigeneratecode.guardrail.UserFacingOutputSanitizer;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowTextStreamHandlerPersistenceTest {

    private static WorkflowTextStreamHandler newHandler() {
        WorkflowTextStreamHandler handler = new WorkflowTextStreamHandler();
        try {
            var field = WorkflowTextStreamHandler.class.getDeclaredField("userFacingOutputSanitizer");
            field.setAccessible(true);
            field.set(handler, new UserFacingOutputSanitizer());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return handler;
    }

    @Test
    void handle_shouldKeepToolRequestAndToolExecutedLinesForPersistence() {
        WorkflowTextStreamHandler handler = newHandler();
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        when(chatHistoryService.addChatMessage(anyLong(), anyString(), anyString(), anyLong())).thenReturn(true);

        User user = new User();
        user.setId(1001L);

        Flux<String> origin = Flux.just(
                "[workflow] stage: generation\n",
                "[选择工具] writeFile(src/App.vue)\n",
                "[工具调用] writeFile success\n"
        );

        handler.handle(origin, chatHistoryService, 2002L, user).collectList().block();

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatHistoryService, times(1)).addChatMessage(anyLong(), messageCaptor.capture(), anyString(), anyLong());

        String persisted = messageCaptor.getValue();
        assertTrue(persisted.contains("[选择工具] writeFile(src/App.vue)"));
        assertTrue(persisted.contains("[工具调用] writeFile success"));
    }

    @Test
    void handle_shouldNotTruncateVeryLongWorkflowMessage_beforePersisting() {
        WorkflowTextStreamHandler handler = newHandler();
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        when(chatHistoryService.addChatMessage(anyLong(), anyString(), anyString(), anyLong())).thenReturn(true);

        User user = new User();
        user.setId(1001L);

        // Large message should be persisted as-is (no hard truncation)
        String huge = "X".repeat(90_000);
        Flux<String> origin = Flux.just(huge);

        handler.handle(origin, chatHistoryService, 2002L, user).collectList().block();

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatHistoryService, times(1)).addChatMessage(anyLong(), messageCaptor.capture(), anyString(), anyLong());

        String persisted = messageCaptor.getValue();
        assertTrue(!persisted.contains("...[workflow message truncated]"));
        assertTrue(persisted.length() == huge.length());
    }

    @Test
    void handle_shouldPersistUserFacingMessage_onStreamError_withoutExceptionDetails() {
        WorkflowTextStreamHandler handler = newHandler();
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        when(chatHistoryService.addChatMessage(anyLong(), anyString(), anyString(), anyLong())).thenReturn(true);

        User user = new User();
        user.setId(1001L);

        Flux<String> origin = Flux.error(new RuntimeException(
                "dev.langchain4j.exception.AuthenticationException: AllocationQuota.FreeTierOnly"));

        try {
            handler.handle(origin, chatHistoryService, 2002L, user).collectList().block();
        } catch (RuntimeException ignored) {
            // upstream error propagates after doOnError side effect
        }

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatHistoryService, times(1)).addChatMessage(anyLong(), messageCaptor.capture(), anyString(), anyLong());

        String persisted = messageCaptor.getValue();
        assertEquals(ChatHistoryConstant.GENERATION_FAILED_USER_MESSAGE, persisted);
        assertFalse(persisted.contains("AuthenticationException"));
        assertFalse(persisted.contains("AllocationQuota"));
        assertFalse(persisted.startsWith("AI回复失败"));
    }

    @Test
    void handle_shouldNotPersistAiMessage_onStreamCancel() {
        WorkflowTextStreamHandler handler = newHandler();
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        when(chatHistoryService.addChatMessage(anyLong(), anyString(), anyString(), anyLong())).thenReturn(true);

        User user = new User();
        user.setId(1001L);

        Flux<String> origin = Flux.just("chunk1", "chunk2")
                .concatWith(Flux.never());

        // Subscribe with immediate cancel
        handler.handle(origin, chatHistoryService, 2002L, user)
                .take(Duration.ofMillis(50))
                .collectList()
                .block();

        // Verify no AI message was persisted on cancel
        verify(chatHistoryService, never()).addChatMessage(anyLong(), anyString(), anyString(), anyLong());
    }
}