package com.dbts.glyahhaigeneratecode.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.chat-model")
@Data
public class ReasoningChatModelConfig {

    private String apiKey;

    private String baseUrl;

    // 推理编码模型
    private final String reasoningModelName = "qwen3-coder-next";
    private final int reasoningMaxTokens = 32768;
    // 普通模型
    private final String modelName = "qwen3.5-plus";
    private final int maxTokens = 8192;

    @Bean
    // 返回类型 Bean名称 reasoningChatModel
    public StreamingChatModel reasoningChatModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
