package com.dbts.glyahhaigeneratecode.core;

import com.dbts.glyahhaigeneratecode.ai.model.message.WorkflowChunkDedupState;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.ai.tool.ToolManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowCodeGeneratorFacadeDedupTest {

    @Test
    void shouldObserveToolRequestAndExecutedCountMismatchInSameRound() throws Exception {
        WorkflowCodeGeneratorFacade facade = new WorkflowCodeGeneratorFacade();

        ToolManager toolManager = new ToolManager() {
            @Override
            public BaseTool getTool(String toolName) {
                return null;
            }
        };
        Field toolManagerField = WorkflowCodeGeneratorFacade.class.getDeclaredField("toolManager");
        toolManagerField.setAccessible(true);
        toolManagerField.set(facade, toolManager);

        Method adaptMethod = WorkflowCodeGeneratorFacade.class.getDeclaredMethod(
                "adaptWorkflowCodeChunk",
                String.class,
                WorkflowChunkDedupState.class);
        adaptMethod.setAccessible(true);

        WorkflowChunkDedupState dedupState = new WorkflowChunkDedupState();

        List<String> chunks = new ArrayList<>();
        chunks.add("{\"type\":\"tool_request\",\"id\":\"call-1\",\"name\":\"writeFile\",\"arguments\":\"{\\\"relativeFilePath\\\":\\\"src/App.vue\\\"}\"}");
        chunks.add("{\"type\":\"tool_request\",\"id\":\"call-1\",\"name\":\"writeFile\",\"arguments\":\"{\\\"relativeFilePath\\\":\\\"src/App.vue\\\"}\"}");
        chunks.add("{\"type\":\"tool_executed\",\"id\":\"call-1\",\"name\":\"writeFile\",\"arguments\":\"{\\\"relativeFilePath\\\":\\\"src/App.vue\\\"}\",\"result\":\"ok\"}");
        chunks.add("{\"type\":\"tool_executed\",\"id\":\"call-2\",\"name\":\"writeFile\",\"arguments\":\"{\\\"relativeFilePath\\\":\\\"src/main.js\\\"}\",\"result\":\"ok\"}");

        int requestVisibleCount = 0;
        int executedVisibleCount = 0;

        for (String chunk : chunks) {
            String adapted = (String) adaptMethod.invoke(facade, chunk, dedupState);
            if (adapted == null || adapted.isBlank()) {
                continue;
            }
            if (chunk.contains("\"type\":\"tool_request\"")) {
                requestVisibleCount++;
            }
            if (chunk.contains("\"type\":\"tool_executed\"")) {
                executedVisibleCount++;
            }
        }

        System.out.println("[repro] tool_request visible count = " + requestVisibleCount);
        System.out.println("[repro] tool_executed visible count = " + executedVisibleCount);

        assertTrue(executedVisibleCount > requestVisibleCount,
                "预期复现 tool_executed 数量大于 tool_request 数量");
    }
}