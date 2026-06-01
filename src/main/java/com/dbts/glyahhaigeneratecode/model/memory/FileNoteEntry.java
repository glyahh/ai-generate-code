package com.dbts.glyahhaigeneratecode.model.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 单路径 fileNote 持久化条目。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FileNoteEntry(String note, Long roundId, String updatedAt) {
}
