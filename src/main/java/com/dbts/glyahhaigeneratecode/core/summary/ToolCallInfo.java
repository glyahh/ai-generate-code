package com.dbts.glyahhaigeneratecode.core.summary;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolCallInfo {
    private String toolName;
    private String action;
    private String filePath;
}
