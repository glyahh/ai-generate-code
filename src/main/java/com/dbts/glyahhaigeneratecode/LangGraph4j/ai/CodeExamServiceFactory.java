package com.dbts.glyahhaigeneratecode.LangGraph4j.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 代码质量检查 AI 服务工厂（无工具调用，仅对话模型）。
 */
@Slf4j
@Configuration
public class CodeExamServiceFactory {

    @Resource(name = "openAiChatModel")
    private ChatModel chatModel;

    /**
     * 创建代码质量检查 AI 服务（依据 Prompt/code_exam.txt，无工具调用）
     */
    @Bean
    public CodeExamService createCodeExamService() {
        return AiServices.builder(CodeExamService.class)
                .chatModel(chatModel)
                .build();
    }
}
