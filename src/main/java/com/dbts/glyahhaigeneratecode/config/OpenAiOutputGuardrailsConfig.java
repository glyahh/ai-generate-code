package com.dbts.glyahhaigeneratecode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAI 风格输出护轨配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.chat-model.output-guardrails-config")
public class OpenAiOutputGuardrailsConfig {

    /**
     * 输出护轨最大重试次数。
     */
    private int maxRetries = 2;
}
