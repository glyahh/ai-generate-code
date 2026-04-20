package com.dbts.glyahhaigeneratecode.guardrail;

import cn.hutool.core.util.StrUtil;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 安全审查评估器（最小链路）。
 */
public final class PromptSafetyAuditEvaluator {

    private PromptSafetyAuditEvaluator() {
    }

    private static final int MAX_INPUT_LENGTH = 1000;

    private static final List<String> SENSITIVE_WORDS = Arrays.asList(
            "忽略之前的指令", "ignore previous instructions", "ignore above",
            "破解", "hack", "绕过", "bypass", "越狱", "jailbreak"
    );

    private static final List<Pattern> INJECTION_PATTERNS = Arrays.asList(
            Pattern.compile("(?i)ignore\\s+(?:previous|above|all)\\s+(?:instructions?|commands?|prompts?)"),
            Pattern.compile("(?i)(?:forget|disregard)\\s+(?:everything|all)\\s+(?:above|before)"),
            Pattern.compile("(?i)(?:pretend|act|behave)\\s+(?:as|like)\\s+(?:if|you\\s+are)"),
            Pattern.compile("(?i)system\\s*:\\s*you\\s+are"),
            Pattern.compile("(?i)new\\s+(?:instructions?|commands?|prompts?)\\s*:")
    );

    public static PromptSafetyAuditResult evaluate(String input) {
        if (input == null || input.length() > MAX_INPUT_LENGTH) {
            return PromptSafetyAuditResult.reject("LENGTH_LIMIT", "输入内容过长，不要超过 1000 字");
        }
        if (StrUtil.isBlank(input)) {
            return PromptSafetyAuditResult.reject("EMPTY_INPUT", "输入内容不能为空");
        }
        String lowerInput = input.toLowerCase();
        for (String sensitiveWord : SENSITIVE_WORDS) {
            if (lowerInput.contains(sensitiveWord.toLowerCase())) {
                return PromptSafetyAuditResult.reject("SENSITIVE_WORD", "输入包含不当内容，请修改后重试");
            }
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(input).find()) {
                return PromptSafetyAuditResult.reject("INJECTION_PATTERN", "检测到恶意输入，请求被拒绝");
            }
        }
        return PromptSafetyAuditResult.allow();
    }
}
