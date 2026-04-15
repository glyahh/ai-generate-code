package com.dbts.glyahhaigeneratecode.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.streaming-chat-model")
@Data
public class StreamChatModelConfig {

    private String apiKey;

    private String baseUrl;

    private String modelName;

    private Integer maxTokens;

    private Double temperature;

    private String logRequests;

    private String logResponses;

    @Bean
    // 返回多例的Bean
    @Scope("prototype")
    public StreamingChatModel prototypeStreamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .timeout(Duration.ofMinutes(5))
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
