package com.dbts.glyahhaigeneratecode.LangGraph4j;

import com.dbts.glyahhaigeneratecode.LangGraph4j.state.WorkflowContext;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 完整工作流测试 - 测试从提示词到代码生成的完整流程
 */
@SpringBootTest
class FullWorkflowTest {

    @Test
    void testFullWorkflow_HTML() {
        // 创建工作流
        CodeGenWorkflow workflow = new CodeGenWorkflow();
        assertNotNull(workflow);

        // 执行工作流 - 生成 HTML 网站
        System.out.println("=== 开始测试 HTML 网站生成 ===");
        WorkflowContext result = workflow.executeWorkflow(
                "创建一个简单的个人博客首页，包含标题、导航栏和文章列表",
                CodeGenTypeEnum.HTML
        );

        // 验证关键结果
        assertNotNull(result, "工作流结果不应为空");
        assertNotNull(result.getOriginalPrompt(), "原始提示词不应为空");
        assertNotNull(result.getGenerationType(), "生成类型不应为空");
        assertNotNull(result.getCurrentStep(), "当前步骤不应为空");

        // 打印结果
        System.out.println("原始提示词: " + result.getOriginalPrompt());
        System.out.println("生成类型: " + result.getGenerationType());
        System.out.println("当前步骤: " + result.getCurrentStep());
        System.out.println("生成代码目录: " + result.getGeneratedCodeDir());

        // 验证质量检查结果
        if (result.getQualityResult() != null) {
            System.out.println("质量检查通过: " + result.getQualityResult().getIsValid());
            assertNotNull(result.getQualityResult().getIsValid());
        }

        System.out.println("=== HTML 网站生成测试完成 ===\n");
    }

    @Test
    void testFullWorkflow_VUE() {
        // 创建工作流
        CodeGenWorkflow workflow = new CodeGenWorkflow();
        assertNotNull(workflow);

        // 执行工作流 - 生成 Vue 项目
        System.out.println("=== 开始测试 Vue 项目生成 ===");
        WorkflowContext result = workflow.executeWorkflow(
                "创建一个 Vue3 的待办事项应用，包含添加、删除、完成功能, 尽量简洁一点,代码量少一点",
                CodeGenTypeEnum.VUE
        );

        // 验证关键结果
        assertNotNull(result, "工作流结果不应为空");
        assertNotNull(result.getOriginalPrompt(), "原始提示词不应为空");
        assertNotNull(result.getGenerationType(), "生成类型不应为空");
        assertEquals(CodeGenTypeEnum.VUE, result.getGenerationType(), "生成类型应为 VUE");

        // 打印结果
        System.out.println("原始提示词: " + result.getOriginalPrompt());
        System.out.println("生成类型: " + result.getGenerationType());
        System.out.println("当前步骤: " + result.getCurrentStep());
        System.out.println("生成代码目录: " + result.getGeneratedCodeDir());
        System.out.println("构建结果目录: " + result.getBuildResultDir());

        // 验证质量检查结果
        if (result.getQualityResult() != null) {
            System.out.println("质量检查通过: " + result.getQualityResult().getIsValid());
            assertNotNull(result.getQualityResult().getIsValid());
        }

        System.out.println("=== Vue 项目生成测试完成 ===\n");
    }

    @Test
    void testFullWorkflow_MULTI_FILE() {
        // 创建工作流
        CodeGenWorkflow workflow = new CodeGenWorkflow();
        assertNotNull(workflow);

        // 执行工作流 - 生成多文件项目
        System.out.println("=== 开始测试多文件项目生成 ===");
        WorkflowContext result = workflow.executeWorkflow(
                "创建一个企业官网，包含首页、关于我们、产品展示、联系我们四个页面",
                CodeGenTypeEnum.MULTI_FILE
        );

        // 验证关键结果
        assertNotNull(result, "工作流结果不应为空");
        assertNotNull(result.getOriginalPrompt(), "原始提示词不应为空");
        assertNotNull(result.getGenerationType(), "生成类型不应为空");
        assertEquals(CodeGenTypeEnum.MULTI_FILE, result.getGenerationType(), "生成类型应为 MULTI_FILE");

        // 打印结果
        System.out.println("原始提示词: " + result.getOriginalPrompt());
        System.out.println("生成类型: " + result.getGenerationType());
        System.out.println("当前步骤: " + result.getCurrentStep());
        System.out.println("生成代码目录: " + result.getGeneratedCodeDir());

        // 验证质量检查结果
        if (result.getQualityResult() != null) {
            System.out.println("质量检查通过: " + result.getQualityResult().getIsValid());
            assertNotNull(result.getQualityResult().getIsValid());
        }

        System.out.println("=== 多文件项目生成测试完成 ===\n");
    }
}
