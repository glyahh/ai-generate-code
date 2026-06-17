package com.dbts.glyahhaigeneratecode.LangGraph4j;

import com.dbts.glyahhaigeneratecode.LangGraph4j.node.CodeGeneratorNode;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CodeGenWorkflowStreamBridgeTest {

    @Test
    void createWorkflow_shouldSupportOptionalCodeChunkConsumer() {
        CodeGenWorkflow workflow = new CodeGenWorkflow();
        CompiledGraph<MessagesState<String>> graphWithoutConsumer = workflow.createWorkflow();
        CompiledGraph<MessagesState<String>> graphWithConsumer = workflow.createWorkflow(chunk -> {
        });

        assertNotNull(graphWithoutConsumer);
        assertNotNull(graphWithConsumer);
    }

    @Test
    void codeGeneratorNodeFactory_shouldSupportOptionalCodeChunkConsumer() {
        assertNotNull(CodeGeneratorNode.create());
        assertNotNull(CodeGeneratorNode.create(chunk -> {
        }));
    }
}
