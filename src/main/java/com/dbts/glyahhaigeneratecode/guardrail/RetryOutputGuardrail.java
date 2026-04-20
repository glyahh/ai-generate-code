package com.dbts.glyahhaigeneratecode.guardrail;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;

/**
 * 输出护轨：用于兜底校验模型输出质量，并提供可重试信号。
 */
public class RetryOutputGuardrail implements OutputGuardrail {

    private final int maxRetries;

    public RetryOutputGuardrail() {
        this.maxRetries = 2;
    }

    public RetryOutputGuardrail(Integer maxRetries) {
        this.maxRetries = maxRetries == null ? 2 : maxRetries;
    }

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        String response = responseFromLLM.text();
        // 检查响应是否为空或过短
        if (response == null || response.trim().isEmpty()) {
            return reprompt("响应内容为空", "请重新生成完整的内容");
        }
        if (response.trim().length() < 10) {
            return reprompt("响应内容过短", "请提供更详细的内容");
        }
        // 检查是否包含敏感信息或不当内容
        if (containsSensitiveContent(response)) {
            return reprompt("包含敏感信息", "请重新生成内容，避免包含敏感信息");
        }
        return success();
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * 首轮工具限制：仅允许 writeFile。
     */
    public boolean isFirstRoundToolAllowed(boolean firstRound, String toolName) {
        if (!firstRound) {
            return true;
        }
        return StrUtil.equals("writeFile", toolName);
    }

    /**
     * 编辑模式下应至少发生一次工具调用。
     */
    public boolean shouldWarnEditModeWithoutToolCall(boolean editMode, boolean hasToolCall) {
        return editMode && !hasToolCall;
    }

    /**
     * 检查是否包含敏感内容
     */
    private boolean containsSensitiveContent(String response) {
        String lowerResponse = response.toLowerCase();
        String[] sensitiveWords = {
                "密码", "password", "secret", "token",
                "api key", "私钥", "证书", "credential"
        };
        for (String word : sensitiveWords) {
            if (lowerResponse.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
