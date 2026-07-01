package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryInjectTexts;
import com.dbts.glyahhaigeneratecode.model.memory.FileNoteEntry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryStateServiceImplInjectTest {

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
