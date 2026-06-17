package com.dbts.glyahhaigeneratecode.LangGraph4j;

import cn.hutool.core.io.FileUtil;
import com.dbts.glyahhaigeneratecode.LangGraph4j.node.CodeQualityCheckNode;
import com.dbts.glyahhaigeneratecode.LangGraph4j.state.QualityResult;
import com.dbts.glyahhaigeneratecode.LangGraph4j.state.WorkflowContext;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CodeGenWorkflow 测试类
 */
class CodeGenWorkflowTest {

    @Test
    void testCreateWorkflow() {
        CodeGenWorkflow workflow = new CodeGenWorkflow();
        CompiledGraph<MessagesState<String>> graph = workflow.createWorkflow();
        assertNotNull(graph);
    }

    @Test
    void testRouteAfterCodeGenerator() throws Exception {
        CodeGenWorkflow workflow = new CodeGenWorkflow();
        
        // 测试质检未通过 -> retry
        WorkflowContext context1 = WorkflowContext.builder()
                .originalPrompt("给我生成一个极客风格网站")
                .generationType(CodeGenTypeEnum.VUE)
                .qualityResult(QualityResult.builder()
                        .isValid(false)
                        .build())
                .build();

        // 验证
        String route1 = invokeRouteMethod(workflow, context1);
        assertNotNull(route1);
        assertEquals("retry", route1);

        // 测试 Vue 项目质检通过 -> vue
        WorkflowContext context2 = WorkflowContext.builder()
                .originalPrompt("创建 Vue 项目")
                .generationType(CodeGenTypeEnum.VUE)
                .qualityResult(QualityResult.builder()
                        .isValid(true)
                        .build())
                .build();
        String route2 = invokeRouteMethod(workflow, context2);
        assertNotNull(route2);
        assertEquals("vue", route2);
        
        // 测试 HTML 项目质检通过 -> skip
        WorkflowContext context3 = WorkflowContext.builder()
                .originalPrompt("创建 HTML 页面")
                .generationType(CodeGenTypeEnum.HTML)
                .qualityResult(QualityResult.builder()
                        .isValid(true)
                        .build())
                .build();
        String route3 = invokeRouteMethod(workflow, context3);
        assertNotNull(route3);
        assertEquals("skip", route3);
        
        // 测试 Context 为空 -> skip
        String route4 = invokeRouteMethod(workflow, null);
        assertNotNull(route4);
        assertEquals("skip", route4);
    }

    /**
     * 反射调用私有路由方法
     */
    private String invokeRouteMethod(CodeGenWorkflow workflow, WorkflowContext context) throws Exception {
        @SuppressWarnings("unchecked")
        MessagesState<String> state = mock(MessagesState.class);
        Map<String, Object> dataMap = new HashMap<>();
        if (context != null) {
            dataMap.put(WorkflowContext.WORKFLOW_CONTEXT_KEY, context);
        }
        when(state.data()).thenReturn(dataMap);
        
        Method method = CodeGenWorkflow.class.getDeclaredMethod("routeAfterCodeGenerator", MessagesState.class);
        method.setAccessible(true);
        return (String) method.invoke(workflow, state);
    }

    @Test
    void testCodeQualityCheckNode_ReadFiles() throws Exception {
        // 创建临时测试目录
        String tempDir = System.getProperty("user.dir") + "/temp/test_code_quality";
        File testDir = FileUtil.file(tempDir);
        FileUtil.del(testDir); // 清理旧数据
        FileUtil.mkdir(testDir);
        
        try {
            // 1. 创建正常的代码文件（应该被读取）
            FileUtil.writeUtf8String("console.log('hello');", new File(testDir, "index.js"));
            FileUtil.writeUtf8String("<html><body>Test</body></html>", new File(testDir, "index.html"));
            FileUtil.writeUtf8String("body { color: red; }", new File(testDir, "style.css"));
            
            // 2. 创建应该被忽略的文件
            // 图片文件
            File imgDir = new File(testDir, "images");
            FileUtil.mkdir(imgDir);
            FileUtil.writeUtf8String("fake image", new File(imgDir, "logo.png"));
            
            // node_modules 目录（应该被忽略）
            File nodeModules = new File(testDir, "node_modules");
            FileUtil.mkdir(nodeModules);
            FileUtil.writeUtf8String("module code", new File(nodeModules, "module.js"));
            
            // .git 目录（应该被忽略）
            File gitDir = new File(testDir, ".git");
            FileUtil.mkdir(gitDir);
            FileUtil.writeUtf8String("git config", new File(gitDir, "config"));
            
            // 隐藏文件（应该被忽略）
            FileUtil.writeUtf8String("env vars", new File(testDir, ".env"));
            
            // lock 文件（应该被忽略）
            FileUtil.writeUtf8String("{}", new File(testDir, "package-lock.json"));
            
            // 3. 调用私有方法读取文件
            String content = invokeReadFilesMethod(tempDir);
            
            // 4. 验证结果 - 关键变量用 assertNotNull 判断
            assertNotNull(content, "读取的内容不应为空");
            
            // 应该包含正常代码文件
            assertTrue(content.contains("index.js"), "应该包含 index.js");
            assertTrue(content.contains("index.html"), "应该包含 index.html");
            assertTrue(content.contains("style.css"), "应该包含 style.css");
            assertTrue(content.contains("console.log"), "应该包含 JS 代码内容");
            assertTrue(content.contains("<html>"), "应该包含 HTML 代码内容");
            
            // 不应该包含被忽略的文件
            assertFalse(content.contains("logo.png"), "不应该包含图片文件");
            assertFalse(content.contains("node_modules"), "不应该包含 node_modules");
            assertFalse(content.contains(".git"), "不应该包含 .git 目录");
            assertFalse(content.contains(".env"), "不应该包含隐藏文件");
            assertFalse(content.contains("package-lock.json"), "不应该包含 lock 文件");
            
            System.out.println("=== 代码质量检查节点测试通过 ===");
            System.out.println("读取到的文件数量: " + (content.split("===== FILE:").length - 1));
            
        } finally {
            // 清理测试目录
            FileUtil.del(testDir);
        }
    }

    @Test
    void testCodeQualityCheckNode_EmptyDir() throws Exception {
        // 测试空目录
        String content = invokeReadFilesMethod("");
        assertNotNull(content);
        assertEquals("", content, "空目录应该返回空字符串");
        
        // 测试不存在的目录
        String content2 = invokeReadFilesMethod("/not/exist/path");
        assertNotNull(content2);
        assertEquals("", content2, "不存在的目录应该返回空字符串");
    }

    @Test
    void testCodeQualityCheckNode_IgnorePatterns() throws Exception {
        // 创建临时测试目录
        String tempDir = System.getProperty("user.dir") + "/temp/test_ignore_patterns";
        File testDir = FileUtil.file(tempDir);
        FileUtil.del(testDir);
        FileUtil.mkdir(testDir);
        
        try {
            // 创建各种应该被忽略的文件类型
            FileUtil.writeUtf8String("code", new File(testDir, "test.js"));
            
            // 测试各种图片格式
            FileUtil.writeUtf8String("img", new File(testDir, "test.jpg"));
            FileUtil.writeUtf8String("img", new File(testDir, "test.png"));
            FileUtil.writeUtf8String("img", new File(testDir, "test.gif"));
            FileUtil.writeUtf8String("img", new File(testDir, "test.svg"));
            
            // 测试字体文件
            FileUtil.writeUtf8String("font", new File(testDir, "font.woff"));
            FileUtil.writeUtf8String("font", new File(testDir, "font.ttf"));
            
            // 测试 map 文件
            FileUtil.writeUtf8String("map", new File(testDir, "app.js.map"));
            
            String content = invokeReadFilesMethod(tempDir);
            
            assertNotNull(content);
            // 只应该包含 test.js
            assertTrue(content.contains("test.js"), "应该包含 JS 文件");
            assertFalse(content.contains("test.jpg"), "不应该包含图片");
            assertFalse(content.contains("font.woff"), "不应该包含字体文件");
            assertFalse(content.contains("app.js.map"), "不应该包含 map 文件");
            
        } finally {
            FileUtil.del(testDir);
        }
    }

    /**
     * 反射调用 CodeQualityCheckNode 的私有文件读取方法
     * 这个方法用来测试代码质量检查节点是否能正确过滤文件
     */
    private String invokeReadFilesMethod(String dir) throws Exception {
        // 获取私有方法
        Method method = CodeQualityCheckNode.class.getDeclaredMethod("readAndConcatenateCodeFiles", String.class);
        method.setAccessible(true); // 允许访问私有方法
        // 调用静态方法，第一个参数传 null
        return (String) method.invoke(null, dir);
    }

    @Test
    void testCodeQualityCheckWithAI() throws Exception {
        // 创建临时测试目录，模拟生成的代码
        String tempDir = System.getProperty("user.dir") + "/temp/test_ai_quality_check";
        File testDir = FileUtil.file(tempDir);
        FileUtil.del(testDir);
        FileUtil.mkdir(testDir);
        
        try {
            // 创建一个简单的 HTML 项目（有一些小问题，让 AI 能检测出来）
            String htmlCode = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>测试页面</title>
                </head>
                <body>
                    <h1>欢迎</h1>
                    <p>这是一个测试页面
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
            
            FileUtil.writeUtf8String(htmlCode, new File(testDir, "index.html"));
            FileUtil.writeUtf8String(jsCode, new File(testDir, "script.js"));
            
            // 创建 WorkflowContext，模拟代码生成完成后的状态
            WorkflowContext context = WorkflowContext.builder()
                    .originalPrompt("创建企业官网，展示公司形象和业务介绍")
                    .generationType(CodeGenTypeEnum.MULTI_FILE)
                    .generatedCodeDir(tempDir) // 设置生成的代码目录
                    .currentStep("代码生成")
                    .build();
            
            // 调用 CodeQualityCheckNode 的 create 方法创建节点
            var node = CodeQualityCheckNode.create();
            assertNotNull(node, "节点不应为空");
            
            // 创建 MessagesState 并执行节点
            @SuppressWarnings("unchecked")
            MessagesState<String> state = mock(MessagesState.class);
            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put(WorkflowContext.WORKFLOW_CONTEXT_KEY, context);
            when(state.data()).thenReturn(dataMap);
            
            // 执行节点（会调用 AI 进行质量检查）
            System.out.println("=== 开始执行 AI 代码质量检查 ===");
            Map<String, Object> result = node.apply(state).get(); // 异步执行并等待结果
            
            // 从结果中获取更新后的 context
            WorkflowContext updatedContext = (WorkflowContext) result.get(WorkflowContext.WORKFLOW_CONTEXT_KEY);
            
            // 验证关键变量
            assertNotNull(updatedContext, "更新后的 context 不应为空");
            assertNotNull(updatedContext.getQualityResult(), "质量检查结果不应为空");
            
            QualityResult qualityResult = updatedContext.getQualityResult();
            
            // 验证质量检查结果的字段
            assertNotNull(qualityResult.getIsValid(), "isValid 不应为空");
            
            // 打印 AI 返回的结果
            System.out.println("=== AI 质量检查结果 ===");
            System.out.println("是否通过: " + qualityResult.getIsValid());
            
            if (qualityResult.getErrors() != null && !qualityResult.getErrors().isEmpty()) {
                System.out.println("错误列表:");
                for (int i = 0; i < qualityResult.getErrors().size(); i++) {
                    System.out.println("  " + i + " = " + qualityResult.getErrors().get(i));
                }
                assertNotNull(qualityResult.getErrors(), "错误列表不应为空");
            }
            
            if (qualityResult.getSuggestions() != null && !qualityResult.getSuggestions().isEmpty()) {
                System.out.println("改进建议:");
                for (int i = 0; i < qualityResult.getSuggestions().size(); i++) {
                    System.out.println("  " + i + " = " + qualityResult.getSuggestions().get(i));
                }
                assertNotNull(qualityResult.getSuggestions(), "建议列表不应为空");
            }
            
            System.out.println("=== 测试完成 ===");
            
        } finally {
            // 清理测试目录
            FileUtil.del(testDir);
        }
    }
}
