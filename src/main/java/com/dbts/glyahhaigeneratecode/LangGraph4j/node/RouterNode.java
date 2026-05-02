package com.dbts.glyahhaigeneratecode.LangGraph4j.node;

import com.dbts.glyahhaigeneratecode.LangGraph4j.state.WorkflowContext;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
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

            // 强规则：前端已手动选择 generationType 时，禁止再次自动路由覆盖用户意图。
            if (context.getGenerationType() == null) {
                CodeGenTypeEnum generationType;
                try {
                    // 获取AI路由服务
                    aiCodeGeneratorRoutineService routingService = SpringContextUtil.getBean(aiCodeGeneratorRoutineService.class);
                    // 根据原始提示词进行智能路由, 说白了就是决定代码生成的类型
                    generationType = routingService.aiCodeGeneratorRoutine(context.getOriginalPrompt());
                    if (generationType == null) {
                        throw new MyException(ErrorCode.OPERATION_ERROR, "自动路由未返回有效的代码生成类型");
                    }
                    log.info("AI智能路由完成，选择类型: {} ({})", generationType.getValue(), generationType.getText());
                } catch (Exception e) {
                    log.error("AI智能路由失败，终止流程: {}", e.getMessage(), e);
                    throw new MyException(ErrorCode.OPERATION_ERROR, "自动路由失败，请稍后重试");
                }
                context.setGenerationType(generationType);
            } else {
                log.info("检测到已指定 generationType={}，跳过自动路由", context.getGenerationType().getValue());
            }


            // 更新状态
            context.setCurrentStep("智能路由");
            return WorkflowContext.saveContext(context);
        });
    }
}
