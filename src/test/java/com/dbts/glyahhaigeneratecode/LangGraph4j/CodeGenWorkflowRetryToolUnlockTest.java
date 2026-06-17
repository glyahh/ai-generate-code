package com.dbts.glyahhaigeneratecode.LangGraph4j;

import com.dbts.glyahhaigeneratecode.LangGraph4j.state.QualityResult;
import com.dbts.glyahhaigeneratecode.LangGraph4j.state.WorkflowContext;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CodeGenWorkflowRetryToolUnlockTest {

    @Test
    void routeAfterCodeGenerator_shouldFlipFirstRoundFalseOnRetry() throws Exception {
        WorkflowContext ctx = WorkflowContext.builder()
                .generationType(CodeGenTypeEnum.VUE)
                .firstRound(true)
                .qualityResult(QualityResult.builder().isValid(false).build())
                .build();

        Map<String, Object> data = new HashMap<>();
        data.put(WorkflowContext.WORKFLOW_CONTEXT_KEY, ctx);
        MessagesState<String> state = new MessagesState<>(data);

        CodeGenWorkflow workflow = new CodeGenWorkflow();
        Method m = CodeGenWorkflow.class.getDeclaredMethod("routeAfterCodeGenerator", MessagesState.class);
        m.setAccessible(true);

        Object result = m.invoke(workflow, state);
        assertEquals("retry", result);
        assertFalse(Boolean.TRUE.equals(ctx.getFirstRound()));
    }
}

