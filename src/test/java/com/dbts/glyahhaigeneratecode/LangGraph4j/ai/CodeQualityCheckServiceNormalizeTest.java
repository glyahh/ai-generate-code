package com.dbts.glyahhaigeneratecode.LangGraph4j.ai;

import com.dbts.glyahhaigeneratecode.LangGraph4j.state.QualityResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeQualityCheckServiceNormalizeTest {

    @Test
    void checkCodeQuality_whenValidTrue_mergesStrayErrorsIntoSuggestions() {
        CodeExamService exam = Mockito.mock(CodeExamService.class);
        Mockito.when(exam.examineCode(Mockito.anyString())).thenReturn(
                QualityResult.builder()
                        .isValid(true)
                        .errors(List.of("SEO 建议误放进 errors"))
                        .suggestions(List.of("已有建议"))
                        .build());

        CodeQualityCheckService svc = new CodeQualityCheckService(exam);
        QualityResult r = svc.checkCodeQuality("dummy");

        assertTrue(r.getErrors().isEmpty());
        assertEquals(2, r.getSuggestions().size());
        assertTrue(r.getSuggestions().contains("已有建议"));
        assertTrue(r.getSuggestions().contains("SEO 建议误放进 errors"));
    }
}
