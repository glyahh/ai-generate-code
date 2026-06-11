package com.dbts.glyahhaigeneratecode.core.summary;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SummaryResult {
    private int totalToolCalls;
    private int writeFileCount;
    private int modifyFileCount;
    private int deleteFileCount;
    private int dirReadCount;
    private List<String> naturalLanguageChunks;
}
