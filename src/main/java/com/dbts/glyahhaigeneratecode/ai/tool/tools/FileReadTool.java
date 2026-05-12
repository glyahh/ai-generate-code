package com.dbts.glyahhaigeneratecode.ai.tool.tools;

import cn.hutool.json.JSONObject;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.service.AppService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import jakarta.annotation.Resource;

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
@Component
public class FileReadTool extends BaseTool {

    @Resource
    private AppService appService;

    @Tool("读取指定路径的文件内容")
    public String readFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @ToolMemoryId Long appId
    ) {
        try {
            Path projectRoot = resolveNormalizedProjectRoot(appId, appService);
            if (projectRoot == null) {
                return "错误：无效的应用 ID，无法解析项目目录 - " + relativeFilePath;
            }
            Path raw = Paths.get(relativeFilePath);
            Path path = raw.isAbsolute()
                    ? raw.normalize().toAbsolutePath()
                    : projectRoot.resolve(raw).normalize().toAbsolutePath();

            if (!path.startsWith(projectRoot)) {
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

    @Override
    public String getToolName() {
        return "readFile";
    }

    @Override
    public String getDisplayName() {
        return "读取文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("""
            [工具调用] %s %s
            """, getDisplayName(), arguments.getStr("relativeFilePath"));
    }
}

