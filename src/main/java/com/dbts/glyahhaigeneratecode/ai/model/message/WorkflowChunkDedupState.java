package com.dbts.glyahhaigeneratecode.ai.model.message;

import java.util.HashSet;
import java.util.Set;

/**
 * Workflow 流内工具事件幂等状态：用于按 toolCallId 去重 TOOL_REQUEST / TOOL_EXECUTED。
 */
public class WorkflowChunkDedupState {

    private final Set<String> seenToolRequestIds = new HashSet<>();

    private final Set<String> seenToolExecutedIds = new HashSet<>();

    public Set<String> getSeenToolRequestIds() {
        return seenToolRequestIds;
    }

    public Set<String> getSeenToolExecutedIds() {
        return seenToolExecutedIds;
    }
}

