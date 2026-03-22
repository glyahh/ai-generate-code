package com.dbts.glyahhaigeneratecode.ai.tool;

import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 文件修改工具
 * 支持 AI 通过工具调用的方式修改文件内容
 *
 * 整体逻辑:
 * 1. 先获取文件的绝对路径，并做越界校验
 * 2. 文件错误排查 (不存在/不是文件)
 * 3. 校验是否为关键文件，避免直接修改核心配置/入口
 * 4. 读取原始内容并按旧内容 → 新内容进行替换后回写
 */
@Slf4j
public class FileModifyTool {

    @Tool("修改文件内容，用新内容替换指定的旧内容")
    public String modifyFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要替换的旧内容")
            String oldContent,
            @P("替换后的新内容")
            String newContent,
            @ToolMemoryId Long appId
    ) {
        try {
            Path path = Paths.get(relativeFilePath);
            Path projectRoot = null;
            if (!path.isAbsolute()) {
                String projectDirName = "vue_project_" + appId;
                projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName)
                        .normalize()
                        .toAbsolutePath();
                path = projectRoot.resolve(relativeFilePath);
            }
            path = path.normalize().toAbsolutePath();

            // 安全检查：相对路径只能在项目根目录下，避免 ../ 越界修改到项目外
            if (projectRoot != null && !path.startsWith(projectRoot)) {
                return "错误：禁止修改项目目录外的文件 - " + relativeFilePath;
            }

            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return "错误：文件不存在或不是文件 - " + relativeFilePath;
            }

            // 避免直接修改关键配置/入口文件
            String fileName = path.getFileName().toString();
            if (isImportantFile(fileName)) {
                return "错误：不允许直接修改重要文件 - " + fileName;
            }

            String originalContent = Files.readString(path);
            if (oldContent == null || oldContent.isEmpty()) {
                return "错误：旧内容不能为空";
            }
            if (!originalContent.contains(oldContent)) {
                return "警告：文件中未找到要替换的内容，文件未修改 - " + relativeFilePath;
            }

            String modifiedContent = originalContent.replace(oldContent, newContent == null ? "" : newContent);
            if (originalContent.equals(modifiedContent)) {
                return "信息：替换后文件内容未发生变化 - " + relativeFilePath;
            }

            Files.writeString(path, modifiedContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("成功修改文件: {}", path.toAbsolutePath());
            return "文件修改成功: " + relativeFilePath;
        } catch (IOException e) {
            String errorMessage = "修改文件失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    /**
     * 判断是否是重要文件，不允许修改
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
}

