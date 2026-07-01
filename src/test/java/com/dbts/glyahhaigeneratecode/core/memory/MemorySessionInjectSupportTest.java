package com.dbts.glyahhaigeneratecode.core.memory;

import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorServiceFactory;
import com.dbts.glyahhaigeneratecode.service.UserPersonalizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemorySessionInjectSupportTest {

    @Mock UserPersonalizationService userPersonalizationService;
    @Mock aiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    MemorySessionInjectSupport support;

    @BeforeEach
    void setUp() {
        support = new MemorySessionInjectSupport(userPersonalizationService, aiCodeGeneratorServiceFactory);
    }

    @Test
    void injectOrUpdateSessionStyle_nullAppId_shouldReturnEarly() {
        support.injectOrUpdateSessionStyle(null, 1L);
        verifyNoInteractions(userPersonalizationService, aiCodeGeneratorServiceFactory);
    }

    @Test
    void injectOrUpdateSessionStyle_nullUserId_shouldReturnEarly() {
        support.injectOrUpdateSessionStyle(1L, null);
        verifyNoInteractions(userPersonalizationService, aiCodeGeneratorServiceFactory);
    }

    @Test
    void injectOrUpdateSessionStyle_bothStylesEmpty_shouldRemove() {
        when(userPersonalizationService.getCachedAppStyle(1L)).thenReturn(null);
        when(userPersonalizationService.getCachedAnswerStyle(1L)).thenReturn(null);

        support.injectOrUpdateSessionStyle(100L, 1L);

        verify(aiCodeGeneratorServiceFactory).applySessionStyle(eq(100L), isNull());
    }

    @Test
    void injectOrUpdateSessionStyle_hasStyles_shouldCallFactoryWithBody() {
        when(userPersonalizationService.getCachedAppStyle(1L)).thenReturn("专业");
        when(userPersonalizationService.getCachedAnswerStyle(1L)).thenReturn("简洁");

        support.injectOrUpdateSessionStyle(100L, 1L);

        verify(aiCodeGeneratorServiceFactory).applySessionStyle(eq(100L), argThat(body ->
                body != null
                        && body.startsWith("<inject_prompt>")
                        && body.contains("<user_style>")
                        && body.contains("<app_style>")
                        && body.contains("专业")
                        && body.contains("<answer_style>")
                        && body.contains("简洁")
        ));
    }

    @Test
    void injectOrUpdateSessionStyle_onlyAppStyle_shouldWork() {
        when(userPersonalizationService.getCachedAppStyle(1L)).thenReturn("极简");
        when(userPersonalizationService.getCachedAnswerStyle(1L)).thenReturn(null);

        support.injectOrUpdateSessionStyle(100L, 1L);

        verify(aiCodeGeneratorServiceFactory).applySessionStyle(eq(100L), argThat(body ->
                body != null
                        && body.contains("<app_style>")
                        && body.contains("极简")
                        && !body.contains("<answer_style>")
        ));
    }

    @Test
    void injectOrUpdateSessionStyle_getCachedThrows_shouldLogWarnAndReturn() {
        when(userPersonalizationService.getCachedAppStyle(1L)).thenThrow(new RuntimeException("Redis down"));

        support.injectOrUpdateSessionStyle(100L, 1L);

        verify(aiCodeGeneratorServiceFactory, never()).applySessionStyle(anyLong(), any());
    }

    @Test
    void injectOrUpdateSessionStyle_styleBodyContainsPriorityMeta() {
        when(userPersonalizationService.getCachedAppStyle(1L)).thenReturn("专业");
        when(userPersonalizationService.getCachedAnswerStyle(1L)).thenReturn("简洁");

        support.injectOrUpdateSessionStyle(100L, 1L);

        verify(aiCodeGeneratorServiceFactory).applySessionStyle(eq(100L), argThat(body ->
                body.contains("user_style") && body.contains("loop_skill") && body.contains("优先级")
        ));
    }
}
