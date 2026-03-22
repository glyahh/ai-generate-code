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

/**
 * 文件读取工具
 * 支持 AI 通过工具调用的方式读取文件内容
 *
 * 整体逻辑:
 * 1. 先解析出文件的绝对路径，并做越界校验
 * 2. 文件错误排查 (不存在/不是文件)
 * 3. 直接将文件内容返回给 AI
 */
@Slf4j
public class FileReadTool {

    @Tool("读取指定路径的文件内容")
    public String readFile(
            @P("文件的相对路径")
            String relativeFilePath,
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

            // 安全检查：相对路径只能在项目根目录下，避免 ../ 越界读取项目外文件
            if (projectRoot != null && !path.startsWith(projectRoot)) {
                return "错误：禁止读取项目目录外的文件 - " + relativeFilePath;
            }

            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return "错误：文件不存在或不是文件 - " + relativeFilePath;
            }

            return Files.readString(path);
        } catch (IOException e) {
            String errorMessage = "读取文件失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }
}

