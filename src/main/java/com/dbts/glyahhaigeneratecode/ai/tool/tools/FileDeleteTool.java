package com.dbts.glyahhaigeneratecode.ai.tool.tools;

import cn.hutool.json.JSONObject;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件删除工具
 * 支持 AI 通过工具调用的方式删除文件
 *
 * 整体逻辑:
 * 1. 先获取文件的绝对路径
 * 2. 文件错误排查 (越界/不存在/不是文件)
 * 3. 校验是否为关键文件
 * 4. 删除文件
 */
@Slf4j
@Component
public class FileDeleteTool extends BaseTool {

    @Tool("删除指定路径的文件")
    public String deleteFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @ToolMemoryId Long appId
    ) {
        try {
            Path path = Paths.get(relativeFilePath);
            Path projectRoot = null;
            // 如果相对路径不是绝对路径，则手动将其转换为绝对路径
            if (!path.isAbsolute()) {
                String projectDirName = "vue_project_" + appId;
                // 在/output_code里寻找文件并返回路径
                projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName).normalize().toAbsolutePath();
                // 将项目根目录和传入的相对路径拼接，得到完整的路径
                path = projectRoot.resolve(relativeFilePath);
            }
            path = path.normalize().toAbsolutePath();

            // 安全检查：相对路径只能在项目根目录下删除，避免 ../ 越界
            if (projectRoot != null && !path.startsWith(projectRoot)) {
                return "错误：禁止删除项目目录外的文件 - " + relativeFilePath;
            }

            if (!Files.exists(path)) {
                return "警告：文件不存在，无需删除 - " + relativeFilePath;
            }
            if (!Files.isRegularFile(path)) {
                return "错误：指定路径不是文件，无法删除 - " + relativeFilePath;
            }
            // 安全检查：避免删除重要文件
            String fileName = path.getFileName().toString();
            if (isImportantFile(fileName)) {
                return "错误：不允许删除重要文件 - " + fileName;
            }
            Files.delete(path);
            log.info("成功删除文件: {}", path.toAbsolutePath());
            return "文件删除成功: " + relativeFilePath;
        } catch (IOException e) {
            String errorMessage = "删除文件失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    /**
     * 判断是否是重要文件，不允许删除
     */
    private boolean isImportantFile(String fileName) {
        String[] importantFiles = {
                "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
                "vite.config.js", "vite.config.ts", "vue.config.js",
                "tsconfig.json", "tsconfig.app.json", "tsconfig.node.json",
                "index.html", "main.js", "main.ts", "App.vue", ".gitignore", "README.md"
        };
        for (String important : importantFiles) {
            if (important.equalsIgnoreCase(fileName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getToolName() {
        return "deleteFile";
    }

    @Override
    public String getDisplayName() {
        return "删除文件";
    }


    // 此处JSONObject arguments作为ai调用工具类自动传入的参数的json,使用string取出key的值
    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return  String.format("""
            [工具调用] %s %s
            """, getDisplayName(), arguments.getStr("relativeFilePath"));
    }
}

