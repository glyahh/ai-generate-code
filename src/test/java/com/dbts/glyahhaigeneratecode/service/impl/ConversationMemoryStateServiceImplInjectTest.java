package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.config.ConversationMemoryProperties;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryInjectTexts;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryStateInjectSupport;
import com.dbts.glyahhaigeneratecode.mapper.ChatHistoryMapper;
import com.dbts.glyahhaigeneratecode.mapper.ConversationMemoryRefMapper;
import com.dbts.glyahhaigeneratecode.mapper.ConversationMemoryStateMapper;
import com.dbts.glyahhaigeneratecode.mapper.SnapshotHistoryMapper;
import com.dbts.glyahhaigeneratecode.model.memory.FileNoteEntry;
import com.dbts.glyahhaigeneratecode.service.ConversationMemoryFileNoteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;

class ConversationMemoryStateServiceImplInjectTest {

    @Test
    void addInjectedFileToMemory_shouldAppendSystemMessage() throws Exception {
        ConversationMemoryStateMapper stateMapper = mock(ConversationMemoryStateMapper.class);
        ConversationMemoryRefMapper refMapper = mock(ConversationMemoryRefMapper.class);
        SnapshotHistoryMapper snapshotMapper = mock(SnapshotHistoryMapper.class);
        ChatHistoryMapper chatHistoryMapper = mock(ChatHistoryMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ConversationMemoryProperties props = new ConversationMemoryProperties();
        ObjectMapper mapper = new ObjectMapper();
        ConversationMemoryFileNoteService fileNoteService = mock(ConversationMemoryFileNoteService.class);
        ConversationMemoryStateInjectSupport injectSupport =
                new ConversationMemoryStateInjectSupport(stateMapper, fileNoteService, mapper);
        ConversationMemoryStateServiceImpl service =
                new ConversationMemoryStateServiceImpl(stateMapper, refMapper, snapshotMapper, chatHistoryMapper, redis, mapper, props, injectSupport);

        ChatMemoryStore chatMemoryStore = mock(ChatMemoryStore.class);
        when(chatMemoryStore.getMessages(3001L)).thenReturn(List.of());
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .id(3001L)
                .chatMemoryStore(chatMemoryStore)
                .maxMessages(20)
                .build();

        Method m = ConversationMemoryStateServiceImpl.class.getDeclaredMethod(
                "addInjectedFileToMemory", MessageWindowChatMemory.class, Path.class, Path.class, String.class
        );
        m.setAccessible(true);
        Path root = Path.of("D:/tmp/project");
        Path file = root.resolve("src/main.java");
        m.invoke(service, memory, root, file, "class A {}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMemoryStore, times(1)).updateMessages(eq(3001L), captor.capture());
        List<ChatMessage> updated = captor.getValue();
        assertEquals(1, updated.size());
        assertTrue(updated.getFirst() instanceof SystemMessage);
    }

    @Test
    void buildMemoryTaggedText_shouldFormatIndexAndFileNote() {
        Map<String, FileNoteEntry> notes = new LinkedHashMap<>();
        notes.put("src/App.vue", new FileNoteEntry("Vue 根组件，本轮改了布局。", 99L, "2026-06-01T10:00:00"));

        String index = ConversationMemoryInjectTexts.buildMemoryIndexMessage(List.of("src/App.vue"));
        String fileNote = ConversationMemoryInjectTexts.buildMemoryFileNoteMessage(notes);

        assertTrue(index.startsWith("[memory_index]"));
        assertTrue(index.contains("src/App.vue"));
        assertTrue(index.contains("仅供参考"));
        assertTrue(fileNote.startsWith("[memory_file_note]"));
        assertTrue(fileNote.contains("Vue 根组件"));

        String policy = ConversationMemoryInjectTexts.buildMemoryStatePriorityMessage();
        assertTrue(policy.startsWith("[memory_policy]"));
        assertTrue(policy.contains("[memory_index]"));
        assertTrue(policy.contains("[memory_file_note]"));
    }

    @Test
    void buildMemoryIndexMessage_emptyChangedFiles_returnsNull() {
        assertNull(ConversationMemoryInjectTexts.buildMemoryIndexMessage(List.of()));
        assertNull(ConversationMemoryInjectTexts.buildMemoryFileNoteMessage(Map.of()));
    }
}
