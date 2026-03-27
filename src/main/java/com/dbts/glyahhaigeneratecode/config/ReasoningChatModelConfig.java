package com.dbts.glyahhaigeneratecode.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.chat-model")
@Data
public class ReasoningChatModelConfig {

    private String apiKey;

    private String baseUrl;

    // 推理编码模型
    private final String reasoningModelName = "qwen3-coder-next";
    private final int reasoningMaxTokens = 32768;
    // 普通模型 - 使用稳定的支持工具调用的模型
    private final String modelName = "MiniMax-M2.1";
    private final int maxTokens = 8192;


    @Bean
    // 返回类型 Bean名称 reasoningChatModel
    public StreamingChatModel reasoningChatModel() {
        // 工具调用 + 长 SSE 时，两次数据间隔可能超过默认 60s 读超时，需单独放宽（仅影响 VUE 流式模型）
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .timeout(Duration.ofMinutes(5))
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
//      # qwen3.5-plus 99%
//      # qwen3.5-plus-2026-02-15 83%
//      # qwen3.5-122b-a10b
//      # qwen3.5-flash
//      # qwen3-vl-plus-2025-12-19
//      # qwen3.5-35b-a3b