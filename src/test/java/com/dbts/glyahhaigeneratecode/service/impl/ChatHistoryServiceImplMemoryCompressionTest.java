package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.core.memory.ChatAiMemoryRedisSupport;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.MemoryShrinkService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHistoryServiceImplMemoryCompressionTest {

    private void wireShrinkAndTtlMocks(ChatHistoryServiceImpl service) {
        MemoryShrinkService memoryShrinkService = mock(MemoryShrinkService.class);
        when(memoryShrinkService.collectAllMergedSourceChatHistoryIds(any())).thenReturn(Set.of());
        ChatAiMemoryRedisSupport chatAiMemoryRedisSupport = mock(ChatAiMemoryRedisSupport.class);
        ReflectionTestUtils.setField(service, "memoryShrinkService", memoryShrinkService);
        ReflectionTestUtils.setField(service, "chatAiMemoryRedisSupport", chatAiMemoryRedisSupport);
    }

    @Test
    void compactMemoryMessagesIfNeeded_shouldTruncateLongAiMessageInRedisMemory_forMultiFile() {
        ChatMemoryStore chatMemoryStore = mock(ChatMemoryStore.class);

        long appId = 123L;
        String mediumHtml = "```html\n" + "M".repeat(3000) + "\n```";
        String longHtml = "```html\n" + "A".repeat(10_000) + "\n```";
        List<ChatMessage> messages = List.of(
                new UserMessage("hi"),
                new AiMessage(mediumHtml),
                new AiMessage(longHtml)
        );

        when(chatMemoryStore.getMessages(appId)).thenReturn(messages);

        ChatHistoryServiceImpl service = new ChatHistoryServiceImpl();
        ReflectionTestUtils.setField(service, "chatMemoryStore", chatMemoryStore);
        wireShrinkAndTtlMocks(service);

        service.compactMemoryMessagesIfNeeded(appId, CodeGenTypeEnum.MULTI_FILE, "test");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMemoryStore, times(1)).updateMessages(eq(appId), captor.capture());

        List<ChatMessage> updated = captor.getValue();
        String mediumUpdated = ((AiMessage) updated.get(1)).text();
        String longKept = ((AiMessage) updated.get(2)).text();
        assertTrue(mediumUpdated.contains("[历史AI代码已压缩"), "non-exempt AI should be compressed");
        assertTrue(mediumUpdated.length() < mediumHtml.length(), "medium message should shrink");
        assertTrue(longHtml.contentEquals(longKept), "longest AI after last user should stay verbatim");
    }

    @Test
    void compactAiMessageForMemory_nullOrBlank_returnsPlaceholder() {
        ChatHistoryServiceImpl service = new ChatHistoryServiceImpl();
        String placeholder = (String) ReflectionTestUtils.invokeMethod(
                service, "compactAiMessageForMemory", null, CodeGenTypeEnum.MULTI_FILE);
        assertFalse(placeholder == null || placeholder.isBlank(), "null input must yield non-blank safe text");

        String blank = (String) ReflectionTestUtils.invokeMethod(
                service, "compactAiMessageForMemory", "   ", CodeGenTypeEnum.MULTI_FILE);
        assertEquals(placeholder, blank);
    }

    @Test
    void compactMemoryMessagesIfNeeded_nullAiText_rewritesToPlaceholderWithoutThrowing() {
        ChatMemoryStore chatMemoryStore = mock(ChatMemoryStore.class);
        long appId = 456L;
        AiMessage nullTextAi = mock(AiMessage.class);
        when(nullTextAi.text()).thenReturn(null);
        List<ChatMessage> messages = List.of(new UserMessage("hi"), nullTextAi);

        when(chatMemoryStore.getMessages(appId)).thenReturn(messages);

        ChatHistoryServiceImpl service = new ChatHistoryServiceImpl();
        ReflectionTestUtils.setField(service, "chatMemoryStore", chatMemoryStore);
        wireShrinkAndTtlMocks(service);

        service.compactMemoryMessagesIfNeeded(appId, CodeGenTypeEnum.HTML, "test_null_ai");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMemoryStore, times(1)).updateMessages(eq(appId), captor.capture());
        AiMessage fixed = (AiMessage) captor.getValue().get(1);
        assertTrue(fixed.text() != null && !fixed.text().isBlank());
    }
}

