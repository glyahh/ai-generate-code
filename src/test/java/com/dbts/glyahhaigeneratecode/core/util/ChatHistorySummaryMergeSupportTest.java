package com.dbts.glyahhaigeneratecode.core.util;

import com.dbts.glyahhaigeneratecode.core.support.ChatHistorySchemaMigrationSupport;
import com.dbts.glyahhaigeneratecode.mapper.ChatHistoryMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatHistorySummaryMergeSupportTest {

    @Test
    void listOldestMessagesForMergeValidated_acceptsTwoUserRoundsInFourMessages() {
        ChatHistoryMapper mapper = mock(ChatHistoryMapper.class);
        when(mapper.selectListByQuery(any())).thenReturn(List.of(
                user(1L, "u1"),
                ai(2L, "a1"),
                user(3L, "u2"),
                ai(4L, "a2")
        ));

        List<ChatHistory> result = ChatHistorySchemaMigrationSupport.listOldestMessagesForMergeValidated(
                1L, 4, 2, mapper, Set.of());
        assertNotNull(result);
        assertEquals(4, result.size());
    }

    @Test
    void listOldestMessagesForMergeValidated_rejectsWhenUserRoundCountMismatch() {
        ChatHistoryMapper mapper = mock(ChatHistoryMapper.class);
        when(mapper.selectListByQuery(any())).thenReturn(List.of(
                user(1L, "u1"),
                ai(2L, "a1"),
                user(3L, "u2"),
                ai(4L, "a2")
        ));

        List<ChatHistory> result = ChatHistorySchemaMigrationSupport.listOldestMessagesForMergeValidated(
                1L, 4, 1, mapper, Set.of());
        assertNull(result);
    }

    @Test
    void summarizeWithExistingSummary_callsModel() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(anyString())).thenReturn("【用户总结】合并后用户\n【AI总结】合并后AI");

        String raw = ChatHistorySchemaMigrationSupport.summarizeWithExistingSummary(
                "旧用户", "旧AI", List.of(user(9L, "新用户"), ai(10L, "新AI")), chatModel);

        String[] parsed = ChatHistorySchemaMigrationSupport.parseSummaryResponse(raw);
        assertEquals("合并后用户", parsed[0]);
        assertEquals("合并后AI", parsed[1]);
    }

    private static ChatHistory user(Long id, String message) {
        return ChatHistory.builder()
                .id(id)
                .messageType(ChatHistoryMessageTypeEnum.USER.getValue())
                .message(message)
                .build();
    }

    private static ChatHistory ai(Long id, String message) {
        return ChatHistory.builder()
                .id(id)
                .messageType(ChatHistoryMessageTypeEnum.AI.getValue())
                .message(message)
                .build();
    }
}
