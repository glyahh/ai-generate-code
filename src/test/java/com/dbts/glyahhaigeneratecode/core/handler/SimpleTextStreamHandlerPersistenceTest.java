package com.dbts.glyahhaigeneratecode.core.handler;

import com.dbts.glyahhaigeneratecode.constant.ChatHistoryConstant;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimpleTextStreamHandlerPersistenceTest {

    @Test
    void handle_shouldPersistUserFacingMessage_onStreamError_withoutExceptionDetails() {
        SimpleTextStreamHandler handler = new SimpleTextStreamHandler();
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
        assertFalse(persisted.startsWith("AI回复失败"));
    }

    @Test
    void handle_shouldNotPersistAiMessage_onStreamCancel() {
        SimpleTextStreamHandler handler = new SimpleTextStreamHandler();
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        when(chatHistoryService.addChatMessage(anyLong(), anyString(), anyString(), anyLong())).thenReturn(true);

        User user = new User();
        user.setId(1001L);

        // Flux that emits some chunks then gets cancelled
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
