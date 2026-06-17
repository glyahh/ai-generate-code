package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.core.support.ChatHistorySchemaMigrationSupport;
import com.dbts.glyahhaigeneratecode.mapper.ChatHistoryMapper;
import com.dbts.glyahhaigeneratecode.mapper.MemoryShrinkMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.Entity.MemoryShrink;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.model.enums.MemoryShrinkTypeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryShrinkEffectiveRoundsTest {

    @Mock
    private MemoryShrinkMapper memoryShrinkMapper;

    @Mock
    private ChatHistoryMapper chatHistoryMapper;

    @Test
    void countEffectiveUserRounds_includesSummaryAndUnmergedDbUsers() {
        MemoryShrinkServiceImpl memoryShrinkService = new MemoryShrinkServiceImpl();
        ObjectMapper objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(memoryShrinkService, "mapper", memoryShrinkMapper);
        ReflectionTestUtils.setField(memoryShrinkService, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(memoryShrinkService, "chatHistoryMapper", chatHistoryMapper);
        when(memoryShrinkMapper.countMemoryShrinkTableExists()).thenReturn(1);
        when(memoryShrinkMapper.countUserRowsByShrinkType(10L, MemoryShrinkTypeEnum.CONVERSATION_SUMMARY.getValue()))
                .thenReturn(1);
        when(chatHistoryMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(3L);

        int rounds = memoryShrinkService.countEffectiveUserRounds(10L, Set.of(100L, 101L));
        assertEquals(4, rounds);
    }

    @Test
    void countUnmergedDbUserRounds_excludesMergedIds() {
        MemoryShrinkServiceImpl memoryShrinkService = new MemoryShrinkServiceImpl();
        ReflectionTestUtils.setField(memoryShrinkService, "mapper", memoryShrinkMapper);
        ReflectionTestUtils.setField(memoryShrinkService, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(memoryShrinkService, "chatHistoryMapper", chatHistoryMapper);
        when(chatHistoryMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(2L);

        int dbUsers = memoryShrinkService.countUnmergedDbUserRounds(10L, Set.of(1L, 2L, 3L, 4L));
        assertEquals(2, dbUsers);
    }

    @Test
    void getConversationSummaryPair_foldsMultipleLegacyRowsToOneView() {
        MemoryShrinkServiceImpl memoryShrinkService = new MemoryShrinkServiceImpl();
        ObjectMapper objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(memoryShrinkService, "mapper", memoryShrinkMapper);
        ReflectionTestUtils.setField(memoryShrinkService, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(memoryShrinkService, "chatHistoryMapper", chatHistoryMapper);
        when(memoryShrinkMapper.countMemoryShrinkTableExists()).thenReturn(1);
        when(memoryShrinkMapper.countUserRowsByShrinkType(10L, MemoryShrinkTypeEnum.CONVERSATION_SUMMARY.getValue()))
                .thenReturn(2);
        LocalDateTime anchor = LocalDateTime.of(2026, 1, 1, 0, 0);
        when(memoryShrinkMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(
                summaryRow(1L, ChatHistoryMessageTypeEnum.USER.getValue(), "用户摘要1", anchor, "[1,2]"),
                summaryRow(2L, ChatHistoryMessageTypeEnum.AI.getValue(), "AI摘要1", anchor, "[1,2]"),
                summaryRow(3L, ChatHistoryMessageTypeEnum.USER.getValue(), "用户摘要2", anchor.plusHours(1), "[3,4]"),
                summaryRow(4L, ChatHistoryMessageTypeEnum.AI.getValue(), "AI摘要2", anchor.plusHours(1), "[3,4]")
        ));

        Optional<com.dbts.glyahhaigeneratecode.model.DTO.ConversationSummaryPair> pair =
                memoryShrinkService.getConversationSummaryPair(10L);
        assertTrue(pair.isPresent());
        assertEquals("用户摘要1", pair.get().userSummary());
        assertEquals("AI摘要1", pair.get().aiSummary());
        assertEquals(4, pair.get().sourceChatHistoryIds().size());
    }

    @Test
    void listOldestMessagesForMerge_excludesMergedSourceIds() {
        ChatHistoryMapper mapper = org.mockito.Mockito.mock(ChatHistoryMapper.class);
        when(mapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(
                ChatHistory.builder().id(5L).messageType(ChatHistoryMessageTypeEnum.USER.getValue()).build()
        ));

        var result = ChatHistorySchemaMigrationSupport
                .listOldestMessagesForMerge(1L, 4, mapper, Set.of(1L, 2L, 3L, 4L));
        assertEquals(1, result.size());
        assertEquals(5L, result.getFirst().getId());
    }

    private static MemoryShrink summaryRow(Long id, String messageType, String message,
                                           LocalDateTime anchor, String sourceJson) {
        return MemoryShrink.builder()
                .id(id)
                .appId(10L)
                .message(message)
                .messageType(messageType)
                .shrinkType(MemoryShrinkTypeEnum.CONVERSATION_SUMMARY.getValue())
                .anchorCreateTime(anchor)
                .sourceChatHistoryIds(sourceJson)
                .build();
    }
}
