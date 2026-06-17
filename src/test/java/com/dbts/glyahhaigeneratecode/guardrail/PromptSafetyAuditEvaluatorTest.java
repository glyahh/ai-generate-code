package com.dbts.glyahhaigeneratecode.guardrail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptSafetyAuditEvaluatorTest {

    @Test
    void plainTextUnder2200_shouldAllow() {
        String input = "a".repeat(1335);
        PromptSafetyAuditResult result = PromptSafetyAuditEvaluator.evaluate(input);
        assertFalse(result.isBlocked());
    }

    @Test
    void plainTextOver2200_shouldBlock() {
        String input = "a".repeat(2201);
        PromptSafetyAuditResult result = PromptSafetyAuditEvaluator.evaluate(input);
        assertTrue(result.isBlocked());
    }
}
