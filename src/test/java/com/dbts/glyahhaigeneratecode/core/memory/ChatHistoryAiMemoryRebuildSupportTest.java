package com.dbts.glyahhaigeneratecode.core.memory;

import com.dbts.glyahhaigeneratecode.mapper.ChatHistoryMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.MemoryShrinkService;
import com.dbts.glyahhaigeneratecode.service.UserPersonalizationService;
import com.dbts.glyahhaigeneratecode.service.support.LoopInjectService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatHistoryAiMemoryRebuildSupportTest {

    @Mock
    private MemoryShrinkService memoryShrinkService;
    @Mock
    private ChatHistoryMapper chatHistoryMapper;
    @Mock
    private AppService appService;
    @Mock
    private ChatAiMemoryRedisSupport chatAiMemoryRedisSupport;
    @Mock
    private UserPersonalizationService userPersonalizationService;
    @Mock
    private LoopInjectService loopInjectService;

    private ChatHistoryAiMemoryRebuildSupport rebuildSupport;

    @BeforeEach
    void setUp() {
        rebuildSupport = new ChatHistoryAiMemoryRebuildSupport();
        ReflectionTestUtils.setField(rebuildSupport, "memoryShrinkService", memoryShrinkService);
        ReflectionTestUtils.setField(rebuildSupport, "chatHistoryMapper", chatHistoryMapper);
        ReflectionTestUtils.setField(rebuildSupport, "appService", appService);
        ReflectionTestUtils.setField(rebuildSupport, "chatAiMemoryRedisSupport", chatAiMemoryRedisSupport);
        ReflectionTestUtils.setField(rebuildSupport, "userPersonalizationService", userPersonalizationService);
        ReflectionTestUtils.setField(rebuildSupport, "loopInjectService", loopInjectService);
    }

    @Test
    void rebuildAiChatMemoryFromShrink_shouldCompactLongHtmlAiHistoryBeforeInjectingMemory() {
        Long appId = 1001L;
        String longHtml = "```html\n" + "a".repeat(2600) + "\n```";
        when(memoryShrinkService.collectAllMergedSourceChatHistoryIds(appId)).thenReturn(Set.of());
        when(memoryShrinkService.getConversationSummaryPair(appId)).thenReturn(Optional.empty());
        when(chatHistoryMapper.selectListByQuery(any())).thenReturn(List.of(
                chatHistory(appId, 2L, ChatHistoryMessageTypeEnum.AI.getValue(), longHtml, null),
                chatHistory(appId, 1L, ChatHistoryMessageTypeEnum.USER.getValue(), "build page", null)
        ));

        MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(10);
        int restored = rebuildSupport.rebuildAiChatMemoryFromShrink(appId, memory, 10, CodeGenTypeEnum.HTML);

        assertEquals(2, restored);
        ChatMessage ai = memory.messages().get(1);
        assertInstanceOf(AiMessage.class, ai);
        String compacted = ((AiMessage) ai).text();
        assertTrue(compacted.contains("```html"));
        assertTrue(compacted.length() < longHtml.length());
        verify(chatAiMemoryRedisSupport).refreshAiMemoryTtl(appId);
    }

    @Test
    void rebuildAiChatMemoryFromShrink_shouldReinjectLoopSkillForHistoricalUserMessages() {
        Long appId = 1002L;
        Long userId = 77L;
        Long loopId = 42L;
        String original = "use my loop";
        String injected = MemoryMessageXmlSupport.wrapUserOriginal(original)
                + MemoryMessageXmlSupport.wrapLoopSkill(loopId, "compiled loop prompt", "daily-loop");
        when(memoryShrinkService.collectAllMergedSourceChatHistoryIds(appId)).thenReturn(Set.of());
        when(memoryShrinkService.getConversationSummaryPair(appId)).thenReturn(Optional.empty());
        when(chatHistoryMapper.selectListByQuery(any())).thenReturn(List.of(
                chatHistory(appId, userId, ChatHistoryMessageTypeEnum.USER.getValue(), original, loopId)
        ));
        when(loopInjectService.injectIfPresent(
                eq(MemoryMessageXmlSupport.wrapUserOriginal(original)), eq(userId), eq(appId), eq(loopId)))
                .thenReturn(injected);

        MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(10);
        int restored = rebuildSupport.rebuildAiChatMemoryFromShrink(appId, memory, 10, CodeGenTypeEnum.MULTI_FILE);

        assertEquals(1, restored);
        ChatMessage user = memory.messages().getFirst();
        assertInstanceOf(UserMessage.class, user);
        String text = ((UserMessage) user).singleText();
        assertTrue(text.contains("<user_original>use my loop</user_original>"));
        assertTrue(text.contains("<loop_skill loopId=\"42\" name=\"daily-loop\">"));
    }

    private ChatHistory chatHistory(Long appId, Long userId, String type, String message, Long loopId) {
        return ChatHistory.builder()
                .id(userId)
                .appId(appId)
                .userId(userId)
                .messageType(type)
                .message(message)
                .loopId(loopId)
                .createTime(LocalDateTime.now())
                .build();
    }
}
