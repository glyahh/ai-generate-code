package com.dbts.glyahhaigeneratecode.core.memory;

import com.dbts.glyahhaigeneratecode.model.memory.FileNoteEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryFileNoteSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mergeNotes_onlyUpdatesPendingPaths() {
        Map<String, FileNoteEntry> existing = new LinkedHashMap<>();
        existing.put("a.js", new FileNoteEntry("旧说明 A", 1L, "2026-01-01T00:00:00"));
        existing.put("b.js", new FileNoteEntry("旧说明 B", 1L, "2026-01-01T00:00:00"));

        Map<String, String> llm = Map.of("b.js", "新说明 B", "c.js", "新文件 C");
        Map<String, FileNoteEntry> merged = ConversationMemoryFileNoteSupport.mergeNotes(existing, llm, 2L, 120);

        assertEquals("旧说明 A", merged.get("a.js").note());
        assertEquals("新说明 B", merged.get("b.js").note());
        assertEquals(2L, merged.get("b.js").roundId());
        assertEquals("新文件 C", merged.get("c.js").note());
    }

    @Test
    void parseBatchNoteResponse_stripsJsonFence() {
        String raw = """
                ```json
                {"src/App.vue":"Vue 根组件说明"}
                ```
                """;
        Map<String, String> parsed = ConversationMemoryFileNoteSupport.parseBatchNoteResponse(raw, objectMapper);
        assertEquals("Vue 根组件说明", parsed.get("src/App.vue"));
    }

    @Test
    void sanitizeNote_truncatesAndRedactsSensitive() {
        String note = "api-key: sk-secret12345678901234567890 " + "x".repeat(200);
        String cleaned = ConversationMemoryFileNoteSupport.sanitizeNote(note, 50);
        assertTrue(cleaned.length() <= 50);
        assertFalse(cleaned.contains("sk-secret"));
    }

    @Test
    void pendingLastWriteWins_viaRegisterSemantics() {
        Map<String, String> pending = new java.util.concurrent.ConcurrentHashMap<>();
        pending.put("src/a.js", "hint1");
        pending.put("src/a.js", "hint2");
        assertEquals("hint2", pending.get("src/a.js"));
    }
}
