package com.dbts.glyahhaigeneratecode.core.summary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AiMessageSummaryServiceTest {

    private AiMessageSummaryService service;

    @BeforeEach
    void setUp() { service = new AiMessageSummaryService(); }

    @Test
    void parseAiMessage_mixed() {
        SummaryResult r = service.parseAiMessage("""
                [选择工具] FileWriteTool
                [工具调用] 写入文件 index.html
                这是评论。""");
        assertEquals(1, r.getTotalToolCalls());
        assertEquals(1, r.getWriteFileCount());
        assertEquals(1, r.getNaturalLanguageChunks().size());
    }

    @Test
    void parseAiMessage_onlyTools() {
        SummaryResult r = service.parseAiMessage("[选择工具] FileWriteTool\n[工具调用] 写入文件 index.html");
        assertEquals(1, r.getTotalToolCalls());
        assertTrue(r.getNaturalLanguageChunks().isEmpty());
    }

    @Test
    void parseAiMessage_onlyNL() {
        SummaryResult r = service.parseAiMessage("纯文字。");
        assertEquals(0, r.getTotalToolCalls());
        assertEquals(1, r.getNaturalLanguageChunks().size());
    }

    @Test
    void parseAiMessage_empty() {
        SummaryResult r = service.parseAiMessage("");
        assertEquals(0, r.getTotalToolCalls());
        assertTrue(r.getNaturalLanguageChunks().isEmpty());
    }

    @Test
    void buildUserSummary_withTools() {
        String s = service.buildUserSummary(SummaryResult.builder().totalToolCalls(3).writeFileCount(2).modifyFileCount(1).build());
        assertEquals("[AI回复已完成] 共调用 3 次工具（写入 2，修改 1）", s);
    }

    @Test
    void buildUserSummary_noTools() {
        assertEquals("[AI回复已完成] 共调用 0 次工具", service.buildUserSummary(SummaryResult.builder().totalToolCalls(0).build()));
    }

    @Test
    void extractNaturalLanguage_removesToolLines() {
        String nl = service.extractNaturalLanguage("[工具调用] 写入文件 a.html\n这是评论。");
        assertFalse(nl.contains("[工具调用]"));
        assertTrue(nl.contains("这是评论。"));
    }
}
