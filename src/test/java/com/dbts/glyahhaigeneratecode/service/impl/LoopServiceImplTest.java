package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.mapper.LoopMapper;
import com.dbts.glyahhaigeneratecode.mapper.AppLoopMapper;
import com.dbts.glyahhaigeneratecode.mapper.UserLoopApplyMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.Loop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LoopServiceImpl 新增功能（marketImport）单元测试。
 * <p>只覆盖本次 Loop 分支新增的 marketImport 方法，原有 CRUD 不重复测。</p>
 */
@ExtendWith(MockitoExtension.class)
class LoopServiceImplTest {

    @Mock LoopMapper loopMapper;
    @Mock AppLoopMapper appLoopMapper;
    @Mock UserLoopApplyMapper userLoopApplyMapper;
    @Mock StringRedisTemplate stringRedisTemplate;
    LoopServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LoopServiceImpl(loopMapper, appLoopMapper, userLoopApplyMapper, stringRedisTemplate);
    }

    // ==================== marketImport ====================

    @Test
    void marketImport_shouldClonePublicLoop() {
        Loop source = new Loop();
        source.setId(100L);
        source.setLoopName("测试技能");
        source.setDescription("一个测试用技能");
        source.setUserId(1L);
        source.setPriority(99);
        source.setWorkflowJson("{\"steps\":[{\"key\":\"role\",\"content\":\"test\"}]}");
        source.setCompiledPrompt("compiled");
        source.setSourceType("created");
        source.setVisibility("public");
        source.setIsDelete(0);
        when(loopMapper.selectOneById(100L)).thenReturn(source);
        // 模拟 insert 后设置克隆 ID
        doAnswer(invocation -> {
            Loop arg = invocation.getArgument(0);
            arg.setId(200L);
            return 1;
        }).when(loopMapper).insert(any(Loop.class));

        Long newId = service.marketImport(100L, 2L);

        assertEquals(200L, newId);
        ArgumentCaptor<Loop> captor = ArgumentCaptor.forClass(Loop.class);
        verify(loopMapper).insert(captor.capture());
        Loop clone = captor.getValue();
        assertEquals("测试技能（导入）", clone.getLoopName());
        assertEquals(2L, clone.getUserId());
        assertEquals(0, clone.getPriority().intValue());
        assertEquals("market_imported", clone.getSourceType());
        assertEquals("private", clone.getVisibility());
        assertEquals(0, clone.getIsDelete().intValue());
        assertNotNull(clone.getCreateTime());
        assertNotNull(clone.getUpdateTime());
    }

    @Test
    void marketImport_shouldThrowWhenLoopNotFound() {
        when(loopMapper.selectOneById(999L)).thenReturn(null);
        Exception ex = assertThrows(RuntimeException.class,
                () -> service.marketImport(999L, 2L));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void marketImport_shouldThrowWhenLoopDeleted() {
        Loop deleted = new Loop();
        deleted.setId(100L);
        deleted.setIsDelete(1);
        when(loopMapper.selectOneById(100L)).thenReturn(deleted);
        Exception ex = assertThrows(RuntimeException.class,
                () -> service.marketImport(100L, 2L));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void marketImport_shouldThrowWhenLoopNotPublic() {
        Loop privateLoop = new Loop();
        privateLoop.setId(100L);
        privateLoop.setIsDelete(0);
        privateLoop.setVisibility("private");
        when(loopMapper.selectOneById(100L)).thenReturn(privateLoop);
        Exception ex = assertThrows(RuntimeException.class,
                () -> service.marketImport(100L, 2L));
        assertTrue(ex.getMessage().contains("只能导入公开 Loop"));
    }
}
