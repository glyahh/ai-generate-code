package com.dbts.glyahhaigeneratecode.LangGraph4j.node;

import com.dbts.glyahhaigeneratecode.LangGraph4j.state.WorkflowContext;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.core.AiCodeGeneratorFacade;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.function.Consumer;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Slf4j
public class CodeGeneratorNode {

    public static AsyncNodeAction<MessagesState<String>> create() {
        return create(null);
    }

    public static AsyncNodeAction<MessagesState<String>> create(Consumer<String> streamChunkConsumer) {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 代码生成");

            // 使用增强提示词作为发给 AI 的用户消息
            String userMessage = context.getEnhancedPrompt();
            CodeGenTypeEnum generationType = context.getGenerationType();
            // 获取 AI 代码生成外观服务
            AiCodeGeneratorFacade codeGeneratorFacade = SpringContextUtil.getBean(AiCodeGeneratorFacade.class);
            log.info("开始生成代码，类型: {} ({})", generationType.getValue(), generationType.getText());

            Long appId = context.getAppId();
            if (appId == null || appId <= 0) {
                appId = System.currentTimeMillis();
                context.setAppId(appId);
            }

            // Keep legacy behavior: only first round without generated dir is writeFile-only.
            boolean firstRound = Boolean.TRUE.equals(context.getFirstRound()) && context.getGeneratedCodeDir() == null;
            // 调用流式代码生成
            Flux<String> codeStream = codeGeneratorFacade.generateAndSaveCodeStream(userMessage, generationType, appId, firstRound);

            // vue项目需要拼接
            if (generationType == CodeGenTypeEnum.VUE && streamChunkConsumer != null) {
                codeStream = codeStream.doOnNext(chunk -> {
                    try {
                        // 执行workflow门面类的方法
                        streamChunkConsumer.accept(chunk);
                    } catch (Exception e) {
                        log.debug("workflow code stream callback failed: {}", e.getMessage());
                    }
                });
            }

            codeStream.blockLast(Duration.ofMinutes(10));

            String generatedCodeDir = generationType == CodeGenTypeEnum.VUE
                    ? String.format("%s/%s_project_%s", AppConstant.CODE_OUTPUT_ROOT_DIR, generationType.getValue(), appId)
                    : String.format("%s/%s_%s", AppConstant.CODE_OUTPUT_ROOT_DIR, generationType.getValue(), appId);
            log.info("AI 代码生成完成，生成目录: {}", generatedCodeDir);

            // 更新状态
            context.setCurrentStep("代码生成");
            context.setGeneratedCodeDir(generatedCodeDir);
            return WorkflowContext.saveContext(context);
        });
    }
}
