package com.dbts.glyahhaigeneratecode.guardrail;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;

public class PromptSafetyInputGuardrail implements InputGuardrail {

    @Override
    public InputGuardrailResult validate (UserMessage userMessage) {
        String input = userMessage.singleText();
        PromptSafetyAuditResult auditResult = PromptSafetyAuditEvaluator.evaluate(input);
        if (auditResult.isBlocked()) {
            return fatal(auditResult.getUserMessage());
        }
        return success();
    }
}
