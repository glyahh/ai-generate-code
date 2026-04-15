package com.dbts.glyahhaigeneratecode.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ai服务创建工厂
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class aiCodeGeneratorRoutineServiceFactory {

    private final ApplicationContext applicationContext;

//    @Resource(name = "routingChatModelPrototype")
//    private ChatModel chatModel;

    // 因为这里的service不会随着用户没创一个应用而创一个(aiCodeGeneratorService会每次创建一个)
    // 所以这里没法并发service,所以没必要使用prototype
    public aiCodeGeneratorRoutineService createAiCodeGeneratorRoutineService() {
        ChatModel routingChatModelPrototype = applicationContext.getBean("routingChatModelPrototype", ChatModel.class);
        return AiServices.builder(aiCodeGeneratorRoutineService.class)
                    .chatModel(routingChatModelPrototype)
                .build();
    }

    @Bean
    public aiCodeGeneratorRoutineService aiCodeGeneratorRoutineService() {
        return createAiCodeGeneratorRoutineService();
    }
}
