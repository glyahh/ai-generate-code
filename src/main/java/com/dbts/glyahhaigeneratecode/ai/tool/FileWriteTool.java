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
 * 文件写入工具
 * 支持 AI 通过工具调用的方式写入文件
 *
 * 整体逻辑:
 * 1. 先解析并拼接出文件的绝对路径
 * 2. 确保父级目录已创建（不存在则自动创建）
 * 3. 以覆盖写入的方式写入新内容
 * 4. 返回相对路径给 AI，避免泄露本地绝对路径
 */
@Slf4j
public class FileWriteTool {

    //告诉ai这个方法的作用
    @Tool("写入文件到指定路径")
    public String writeFile(
            @P("文件的相对路径") // 告诉ai参数的作用
            String relativeFilePath,
            @P("要写入文件的内容")
            String content,
            @ToolMemoryId Long appId // ai自动保存记忆的参数
    ) {
        try {
            // 生成绝对路径,包含Vue文件
            Path path = Paths.get(relativeFilePath);
            if (!path.isAbsolute()) {
                // 相对路径处理，创建基于 appId 的项目目录
                String projectDirName = "vue_project_" + appId;
                Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName);
                path = projectRoot.resolve(relativeFilePath);
            }
            // 创建父目录（如果不存在）
            Path parentDir = path.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            // 写入文件内容
            Files.write(path, content.getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            //log.info("成功写入文件: {}", path.toAbsolutePath());
            // 注意要返回相对路径，不能让 AI 把文件绝对路径返回给用户
            return "文件写入成功: " + relativeFilePath;
        } catch (IOException e) {
            String errorMessage = "文件写入失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }
}
