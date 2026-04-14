package com.dbts.glyahhaigeneratecode.LangGraph4j;

import com.dbts.glyahhaigeneratecode.LangGraph4j.node.*;
import com.dbts.glyahhaigeneratecode.LangGraph4j.state.WorkflowContext;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;

import java.util.Map;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;


/**
 * 真实执行工作流的业务逻辑
 */
@Slf4j
public class CodeGenWorkflow {

    /**
     * 创建完整的工作流
     */
    public CompiledGraph<MessagesState<String>> createWorkflow() {
        try {
            return new MessagesStateGraph<String>()
                    // 添加节点 - 使用完整实现的节点
                    .addNode("image_collector", ImageCollectorNode.create())
                    .addNode("prompt_enhancer", PromptEnhancerNode.create())
                    .addNode("router", RouterNode.create())
                    .addNode("code_generator", CodeGeneratorNode.create())
                    .addNode("project_builder", ProjectBuilderNode.create())
                    .addNode("code_quality_check", CodeQualityCheckNode.create())

                    // 添加边
                    .addEdge(START, "image_collector")
                    .addEdge("image_collector", "prompt_enhancer")
                    .addEdge("prompt_enhancer", "router")
                    .addEdge("router", "code_generator")
                    .addEdge("code_generator", "code_quality_check")
                    .addConditionalEdges("code_quality_check",
                            edge_async(this::routeAfterCodeGenerator),
                            Map.of(
                                    "retry", "code_generator",
                                    "vue", "project_builder",
                                    "skip", END
                            ))
                    .addEdge("project_builder", END)

                    // 编译工作流
                    .compile();
        } catch (GraphStateException e) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "工作流创建失败");
        }
    }

    /**
     * {@code code_quality_check} 之后的条件边，返回值需与映射键一致：{@code retry} / {@code vue} / {@code skip}。
     * <ul>
     *   <li>{@code retry}：质检未通过（{@code QualityResult#isValid} 为 {@code false}）→ 回到 {@code code_generator}</li>
     *   <li>{@code vue}：质检通过且为 Vue 项目 → {@code project_builder}</li>
     *   <li>{@code skip}：质检通过且非 Vue → 直接结束</li>
     * </ul>
     */
    private String routeAfterCodeGenerator(MessagesState<String> state) throws Exception {
        WorkflowContext ctx = WorkflowContext.getContext(state);
        if (ctx == null) {
            log.warn("routeAfterCodeGenerator: WorkflowContext 为空，走 skip 结束");
            return "skip";
        }

        if (ctx.getQualityResult() != null && Boolean.FALSE.equals(ctx.getQualityResult().getIsValid())) {
            log.info("代码质检未通过，重新执行 code_generator");
            return "retry";
        }

        if (ctx.getGenerationType() == CodeGenTypeEnum.VUE) {
            log.info("质检通过且为 Vue 项目，执行 project_builder");
            return "vue";
        }

        log.info("质检通过且非 Vue 项目，结束工作流");
        return "skip";
    }

    /**
     * 执行工作流
     */
    public WorkflowContext executeWorkflow (String originalPrompt, CodeGenTypeEnum codeGenTypeEnum) {
        CompiledGraph<MessagesState<String>> workflow = createWorkflow();

        // 初始化 WorkflowContext
        WorkflowContext initialContext = WorkflowContext.builder()
                .originalPrompt(originalPrompt)
                .generationType(codeGenTypeEnum)
                .appId(System.currentTimeMillis())
                .currentStep("初始化")
                .build();

        GraphRepresentation graph = workflow.getGraph(GraphRepresentation.Type.MERMAID);
        log.info("工作流图:\n{}", graph.content());
        log.info("开始执行代码生成工作流");

        WorkflowContext finalContext = null;
        int stepCounter = 1;

        // 传入一个初始的 map，然后遍历取出每一次经过的节点产出的数据
        for (NodeOutput<MessagesState<String>> step : workflow.stream(
                // 初始参数
                Map.of(WorkflowContext.WORKFLOW_CONTEXT_KEY, initialContext))) {

            log.info("--- 第 {} 步完成 ---", stepCounter);
            // 根据WorkflowContext.WORKFLOW_CONTEXT_KEY, 显示当前状态
            WorkflowContext currentContext = WorkflowContext.getContext(step.state());
            if (currentContext != null) {
                finalContext = currentContext;
                log.info("当前步骤上下文: {}", currentContext);
            }
            stepCounter++;
        }
        log.info("代码生成工作流执行完成！");
        return finalContext;
    }
}
