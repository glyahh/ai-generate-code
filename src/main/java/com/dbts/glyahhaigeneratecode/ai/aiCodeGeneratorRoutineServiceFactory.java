package com.dbts.glyahhaigeneratecode.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ai服务创建工厂
 */
@Configuration
@Slf4j
public class aiCodeGeneratorRoutineServiceFactory {

    @Resource
    private ChatModel chatModel;

    @Bean
    public aiCodeGeneratorRoutineService aiCodeGeneratorRoutineService() {
        return AiServices.builder(aiCodeGeneratorRoutineService.class)
                .chatModel(chatModel)
                .build();
    }

}
