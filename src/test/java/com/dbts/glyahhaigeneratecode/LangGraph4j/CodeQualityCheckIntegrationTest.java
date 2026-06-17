package com.dbts.glyahhaigeneratecode.LangGraph4j;

import cn.hutool.core.io.FileUtil;
import com.dbts.glyahhaigeneratecode.LangGraph4j.state.QualityResult;
import com.dbts.glyahhaigeneratecode.LangGraph4j.state.WorkflowContext;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeGenWorkflow 集成测试 - 需要 Spring 上下文和 AI 服务
 * 这个测试会真实调用 AI 进行代码质量检查
 */
@SpringBootTest
class CodeQualityCheckIntegrationTest {

    @Test
    void testExecuteWorkflowWithAI() {
        // 创建临时测试目录，模拟生成的代码
        String tempDir = System.getProperty("user.dir") + "/temp/test_workflow_ai";
        File testDir = FileUtil.file(tempDir);
        FileUtil.del(testDir);
        FileUtil.mkdir(testDir);

        try {
            // 创建一个简单的 HTML 项目（有一些小问题，让 AI 能检测出来）
            String htmlCode = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>企业官网</title>
                </head>
                <body>
                    <h1>欢迎访问我们的企业</h1>
                    <p>这是一个测试页面
                    <div>公司介绍</div>
                </body>
                </html>
                """; // 注意：<p> 标签没有闭合

            String jsCode = """
                console.log('hello world');
                function test() {
                    var x = 1;
                    console.log(x)
                }
                """; // 使用了 var 而不是 let/const

            String cssCode = """
                body {
                    color: red;
                }
                h1 {
                    font-size: 24px
                }
                """; // 缺少分号

            FileUtil.writeUtf8String(htmlCode, new File(testDir, "index.html"));
            FileUtil.writeUtf8String(jsCode, new File(testDir, "script.js"));
            FileUtil.writeUtf8String(cssCode, new File(testDir, "style.css"));

            // 手动创建 CodeGenWorkflow 实例
            CodeGenWorkflow codeGenWorkflow = new CodeGenWorkflow();

            // 执行完整的工作流（会调用 AI）
            System.out.println("=== 开始执行完整工作流（包含 AI 质量检查）===");
            WorkflowContext result = codeGenWorkflow.executeWorkflow(
                    "创建企业官网，展示公司形象和业务介绍",
                    CodeGenTypeEnum.MULTI_FILE
            );

            // 验证结果 - 所有关键变量用 assertNotNull
            assertNotNull(result, "工作流执行结果不应为空");
            assertNotNull(result.getCurrentStep(), "当前步骤不应为空");
            assertNotNull(result.getOriginalPrompt(), "原始提示词不应为空");
            assertNotNull(result.getGenerationType(), "生成类型不应为空");

            // 打印工作流执行结果
            System.out.println("=== 工作流执行完成 ===");
            System.out.println("当前步骤: " + result.getCurrentStep());
            System.out.println("原始提示词: " + result.getOriginalPrompt());
            System.out.println("生成类型: " + result.getGenerationType());

            // 如果有质量检查结果，打印出来
            if (result.getQualityResult() != null) {
                QualityResult qualityResult = result.getQualityResult();
                System.out.println("\n=== AI 质量检查结果 ===");
                System.out.println("是否通过: " + qualityResult.getIsValid());

                // 验证质量检查结果
                assertNotNull(qualityResult.getIsValid(), "isValid 不应为空");

                if (qualityResult.getErrors() != null && !qualityResult.getErrors().isEmpty()) {
                    System.out.println("错误列表 (size = " + qualityResult.getErrors().size() + "):");
                    for (int i = 0; i < qualityResult.getErrors().size(); i++) {
                        System.out.println("  " + i + " = " + qualityResult.getErrors().get(i));
                    }
                }

                if (qualityResult.getSuggestions() != null && !qualityResult.getSuggestions().isEmpty()) {
                    System.out.println("改进建议 (size = " + qualityResult.getSuggestions().size() + "):");
                    for (int i = 0; i < qualityResult.getSuggestions().size(); i++) {
                        System.out.println("  " + i + " = " + qualityResult.getSuggestions().get(i));
                    }
                }
            }

            System.out.println("\n=== 测试完成 ===");

        } finally {
            // 清理测试目录
            FileUtil.del(testDir);
        }
    }
}
