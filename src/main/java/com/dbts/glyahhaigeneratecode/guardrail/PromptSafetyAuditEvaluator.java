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

    /**
     * 普通文本输入长度上限（用于拦截明显异常的超长提示词）。
     */
    private static final int MAX_INPUT_LENGTH = 1000;

    /**
     * 代码类输入长度上限：当输入内容包含明显的代码/文件片段时，允许更长内容，
     * 以支持“二轮修改/续写”场景（上一轮代码会被带入上下文）。
     */
    private static final int MAX_CODE_INPUT_LENGTH = 20000;

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
        if (input == null) {
            return PromptSafetyAuditResult.reject("EMPTY_INPUT", "输入内容不能为空");
        }
        if (input.length() > MAX_INPUT_LENGTH) {
            // 允许代码类内容更长（例如：上一轮生成的 HTML/多文件内容进入上下文，二轮要求增量修改）
            if (!looksLikeCodeOrProjectText(input) || input.length() > MAX_CODE_INPUT_LENGTH) {
                return PromptSafetyAuditResult.reject("LENGTH_LIMIT", "输入内容过长，不要超过 1000 字");
            }
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

    /**
     * 粗粒度判断输入是否更像“代码/工程文本”而非纯自然语言。
     * 目的：放开二轮增量修改时，输入中携带的代码片段。
     */
    private static boolean looksLikeCodeOrProjectText(String input) {
        String lower = input.toLowerCase();
        return lower.contains("<!doctype") ||
                lower.contains("<html") ||
                lower.contains("```") ||
                lower.contains("import ") ||
                lower.contains("export ") ||
                lower.contains("package ") ||
                lower.contains("class ") ||
                lower.contains("function ") ||
                lower.contains("index.html") ||
                lower.contains("src/") ||
                lower.contains("\\src\\");
    }
}
