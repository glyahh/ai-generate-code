package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 会话注入结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMemoryInjectResult {

    /**
     * 注入后的总内存消息条数。
     */
    private int injectedMessageCount;

    /**
     * 本轮命中的 changedFiles。
     */
    @Builder.Default
    private List<String> changedFiles = Collections.emptyList();

    /**
     * 回填来源：redis 或 db。
     */
    private String source;
}

