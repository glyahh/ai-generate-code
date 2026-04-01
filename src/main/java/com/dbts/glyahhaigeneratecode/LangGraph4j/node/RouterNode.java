package com.dbts.glyahhaigeneratecode.LangGraph4j.node;

import com.dbts.glyahhaigeneratecode.LangGraph4j.state.WorkflowContext;
import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorRoutineService;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Slf4j
public class RouterNode {

    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 智能路由");

            // 如果用户在前端声明好业务就就不用让ai思考了

            if (context.getGenerationType()==null){
                CodeGenTypeEnum generationType;
                try {
                    // 获取AI路由服务
                    aiCodeGeneratorRoutineService routingService = SpringContextUtil.getBean(aiCodeGeneratorRoutineService.class);
                    // 根据原始提示词进行智能路由, 说白了就是决定代码生成的类型
                    generationType = routingService.aiCodeGeneratorRoutine(context.getOriginalPrompt());
                    log.info("AI智能路由完成，选择类型: {} ({})", generationType.getValue(), generationType.getText());
                } catch (Exception e) {
                    log.error("AI智能路由失败，使用默认HTML类型: {}", e.getMessage());
                    generationType = CodeGenTypeEnum.HTML;
                }
                context.setGenerationType(generationType);
            }


            // 更新状态
            context.setCurrentStep("智能路由");
            return WorkflowContext.saveContext(context);
        });
    }
}
