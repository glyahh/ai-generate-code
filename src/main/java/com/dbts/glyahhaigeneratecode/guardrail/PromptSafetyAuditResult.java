package com.dbts.glyahhaigeneratecode.guardrail;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Prompt 安全审查结果，用于记录最小审计信息。
 */
@Data
@AllArgsConstructor
public class PromptSafetyAuditResult {

    /**
     * 是否阻断本次请求
     */
    private boolean blocked;

    /**
     * 命中的规则编码，未命中时为 NONE
     */
    private String hitRule;

    /**
     * 审查动作：ALLOW / REJECT
     */
    private String action;

    /**
     * 返回给用户的提示信息
     */
    private String userMessage;

    public static PromptSafetyAuditResult allow() {
        return new PromptSafetyAuditResult(false, "NONE", "ALLOW", "");
    }

    public static PromptSafetyAuditResult reject(String hitRule, String userMessage) {
        return new PromptSafetyAuditResult(true, hitRule, "REJECT", userMessage);
    }
}
