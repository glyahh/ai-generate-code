package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.mapper.UserPersonalizationMapper;
import com.dbts.glyahhaigeneratecode.model.DTO.UserPersonalizationUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.UserPersonalization;
import com.dbts.glyahhaigeneratecode.model.VO.UserPersonalizationVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserPersonalizationServiceImplTest {

    @Mock UserPersonalizationMapper mapper;
    @Mock StringRedisTemplate stringRedisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    UserPersonalizationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserPersonalizationServiceImpl(mapper, stringRedisTemplate);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test void getByUserId_null_returnsEmpty() {
        UserPersonalizationVO vo = service.getByUserId(null);
        assertNull(vo.getAppStyle());
        assertNull(vo.getAnswerStyle());
    }

    @Test void buildInjectPrompt_noConfig_returnsEmpty() {
        when(stringRedisTemplate.opsForValue().get(anyString())).thenReturn(null);
        when(mapper.selectOneByQuery(any())).thenReturn(null);
        assertEquals("", service.buildInjectPrompt(1L));
    }

    @Test void buildInjectPrompt_hasConfig_returnsTaggedBlock() {
        when(stringRedisTemplate.opsForValue().get(contains("app:" + 1L))).thenReturn(null);
        when(stringRedisTemplate.opsForValue().get(contains("style:" + 1L))).thenReturn(null);
        when(mapper.selectOneByQuery(any())).thenReturn(
                UserPersonalization.builder().userId(1L).appStyle("毛玻璃").answerStyle("简洁回答").build());
        String p = service.buildInjectPrompt(1L);
        assertTrue(p.contains("[user_app_style]"));
        assertTrue(p.contains("[user_answer_style]"));
    }

    @Test void saveOrUpdate_shouldDeleteCache() {


        UserPersonalizationUpdateRequest req = new UserPersonalizationUpdateRequest();
        req.setAppStyle("dark");
        service.saveOrUpdate(1L, req);
        verify(stringRedisTemplate, times(2)).delete(anyString());
    }
}
