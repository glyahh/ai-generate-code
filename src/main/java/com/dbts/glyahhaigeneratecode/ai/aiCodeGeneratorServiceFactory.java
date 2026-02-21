package com.dbts.glyahhaigeneratecode.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ai服务创建工厂
 */
@Configuration
public class aiCodeGeneratorServiceFactory {

    @Resource
    private ChatModel chatModel;

    /**
     * 创建ai代码生成器服务
     * @return
     */
    @Bean
    public aiCodeGeneratorService createAiCodeGeneratorService() {
        return AiServices.create(aiCodeGeneratorService.class, chatModel);
    }

}
