package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.mapper.AppLoopMapper;
import com.dbts.glyahhaigeneratecode.mapper.LoopMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.AppLoop;
import com.dbts.glyahhaigeneratecode.model.Entity.Loop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AppLoopServiceImpl 新增功能（bindLoopsFromMyLoop）单元测试。
 * <p>只覆盖本次 Loop 分支新增的方法，原有 bindLoops/addLoop 不重复测。</p>
 */
@ExtendWith(MockitoExtension.class)
class AppLoopServiceImplTest {

    @Mock AppLoopMapper appLoopMapper;
    @Mock LoopMapper loopMapper;
    @Mock StringRedisTemplate redisTemplate;
    @Mock SetOperations<String, String> setOps;
    AppLoopServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AppLoopServiceImpl(appLoopMapper, loopMapper, redisTemplate);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    // ==================== bindLoopsFromMyLoop ====================

    @Test
    void bindLoopsFromMyLoop_shouldBindOwnedLoop() {
        Loop myLoop = new Loop();
        myLoop.setId(10L);
        myLoop.setUserId(42L);
        myLoop.setIsDelete(0);
        when(loopMapper.selectOneById(10L)).thenReturn(myLoop);

        service.bindLoopsFromMyLoop(100L, java.util.List.of(10L), 42L, "creation");

        ArgumentCaptor<AppLoop> captor = ArgumentCaptor.forClass(AppLoop.class);
        verify(appLoopMapper).insert(captor.capture());
        assertEquals(100L, captor.getValue().getAppId());
        assertEquals(10L, captor.getValue().getLoopId());
        assertEquals("creation", captor.getValue().getAddedFrom());
        verify(redisTemplate).delete("app:loop:ids:" + 100L);
    }

    @Test
    void bindLoopsFromMyLoop_shouldSkipEmptyList() {
        service.bindLoopsFromMyLoop(100L, java.util.Collections.emptyList(), 42L, "creation");
        verifyNoInteractions(appLoopMapper);
    }

    @Test
    void bindLoopsFromMyLoop_shouldSkipNullList() {
        service.bindLoopsFromMyLoop(100L, null, 42L, "creation");
        verifyNoInteractions(appLoopMapper);
    }

    @Test
    void bindLoopsFromMyLoop_shouldThrowWhenLoopNotFound() {
        when(loopMapper.selectOneById(10L)).thenReturn(null);
        Exception ex = assertThrows(RuntimeException.class,
                () -> service.bindLoopsFromMyLoop(100L, java.util.List.of(10L), 42L, "creation"));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void bindLoopsFromMyLoop_shouldThrowWhenLoopDeleted() {
        Loop deleted = new Loop();
        deleted.setId(10L);
        deleted.setIsDelete(1);
        when(loopMapper.selectOneById(10L)).thenReturn(deleted);
        Exception ex = assertThrows(RuntimeException.class,
                () -> service.bindLoopsFromMyLoop(100L, java.util.List.of(10L), 42L, "creation"));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void bindLoopsFromMyLoop_shouldThrowWhenLoopNotOwnedByUser() {
        Loop othersLoop = new Loop();
        othersLoop.setId(10L);
        othersLoop.setUserId(99L);
        othersLoop.setIsDelete(0);
        when(loopMapper.selectOneById(10L)).thenReturn(othersLoop);
        Exception ex = assertThrows(RuntimeException.class,
                () -> service.bindLoopsFromMyLoop(100L, java.util.List.of(10L), 42L, "creation"));
        assertTrue(ex.getMessage().contains("只能从「我的 Loop」选择"));
    }
}
