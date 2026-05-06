package com.dbts.glyahhaigeneratecode.LangGraph4j.state;

import com.dbts.glyahhaigeneratecode.LangGraph4j.model.ImageResource;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 工作流上下文 - 存储所有状态信息
 * 相当于 map<WORKFLOW_CONTEXT_KEY, workcontext>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowContext implements Serializable {

    /**
     * WorkflowContext 在 MessagesState 中的存储key
     */
    public static final String WORKFLOW_CONTEXT_KEY = "workflowContext";

    /**
     * 当前执行步骤
     */
    private String currentStep;

    /**
     * 用户原始输入的提示词
     */
    private String originalPrompt;

    /**
     * 图片资源字符串
     * 直接对应 图片-aiservice 的接口返回值
     */
    private String imageListStr;

    /**
     * 图片资源列表
     * imageListStr 的格式化存储
     */
    private List<ImageResource> imageList;

    /**
     * 增强后的提示词
     */
    private String enhancedPrompt;

    /**
     * 代码生成类型
     */
    private CodeGenTypeEnum generationType;

    /**
     * 本次工作流对应的 appId（用于隔离 AI 会话记忆与产物目录）
     */
    private Long appId;

    /**
     * 是否首轮对话（用于复用既有工具权限约束）
     */
    private Boolean firstRound;

    /**
     * 工作流代码质检失败后的重试次数（仅 code_generator 重入计数）
     */
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * 生成的代码目录
     */
    private String generatedCodeDir;

    /**
     * 构建成功的目录
     */
    private String buildResultDir;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 代码质量检查结果
     */
    private QualityResult qualityResult;

    /**
     * 是否出现 Mermaid 构造错误（仅用于前端提示，不影响流程）
     */
    private Boolean mermaidError;

    @Serial
    private static final long serialVersionUID = 1L;

    // ========== 上下文操作方法 ==========

    /**
     * 从 MessagesState 中获取 WorkflowContext
     */
    public static WorkflowContext getContext(MessagesState<String> state) {
        return (WorkflowContext) state.data().get(WORKFLOW_CONTEXT_KEY);
    }

    /**
     * 将 WorkflowContext 保存到 MessagesState 中
     */
    public static Map<String, Object> saveContext(WorkflowContext context) {
        return Map.of(WORKFLOW_CONTEXT_KEY, context);
    }
}

