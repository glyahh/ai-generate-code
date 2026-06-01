package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.config.ConversationMemoryProperties;
import com.dbts.glyahhaigeneratecode.mapper.ConversationMemoryStateMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.ConversationMemoryState;
import com.dbts.glyahhaigeneratecode.model.memory.FileNoteEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationMemoryFileNoteServiceImplTest {

    @Mock
    private ConversationMemoryStateMapper conversationMemoryStateMapper;
    @Mock
    private ChatModel fileNoteChatModel;

    private ConversationMemoryProperties properties;
    private ObjectMapper objectMapper;
    private ConversationMemoryFileNoteServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new ConversationMemoryProperties();
        properties.setFileNoteEnabled(true);
        objectMapper = new ObjectMapper();
        service = new ConversationMemoryFileNoteServiceImpl(conversationMemoryStateMapper, properties, objectMapper);
        ReflectionTestUtils.setField(service, "fileNoteChatModel", fileNoteChatModel);
    }

    @Test
    void flushPending_emptyPending_returnsNullWithoutLlm() {
        assertNull(service.flushPendingFileNotes(1L, 100L));
        verify(fileNoteChatModel, never()).chat(anyString());
    }

    @Test
    void flushPending_mergesLlmResult() throws Exception {
        ConversationMemoryState row = ConversationMemoryState.builder()
                .appId(1L)
                .fileNotesJson(objectMapper.writeValueAsString(
                        Map.of("keep.js", new FileNoteEntry("保留", 1L, "2026-01-01T00:00:00"))))
                .build();
        when(conversationMemoryStateMapper.selectOneByQuery(org.mockito.ArgumentMatchers.any())).thenReturn(row);
        when(fileNoteChatModel.chat(anyString())).thenReturn("{\"new.js\":\"新文件说明\"}");

        service.registerPendingFileChange(1L, "new.js", "const x = 1;");
        String json = service.flushPendingFileNotes(1L, 200L);

        assertNotNull(json);
        assertTrue(json.contains("new.js"));
        assertTrue(json.contains("保留"));
        verify(fileNoteChatModel).chat(anyString());

        assertNull(service.flushPendingFileNotes(1L, 201L));
    }
}
