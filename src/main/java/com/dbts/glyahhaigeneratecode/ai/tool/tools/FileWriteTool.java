package com.dbts.glyahhaigeneratecode.ai.tool.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.ai.tool.tool_assist.VueSfcRepairHelper;
import com.dbts.glyahhaigeneratecode.config.ConversationMemoryProperties;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryFileNoteSupport;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.ConversationMemoryFileNoteService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 文件写入工具。
 * 支持 AI 通过工具调用的方式将生成内容落盘到项目目录。
 */
@Slf4j
@Component
public class FileWriteTool extends BaseTool {

    @Resource
    private AppService appService;

    @Resource
    private ConversationMemoryFileNoteService conversationMemoryFileNoteService;

    @Resource
    private ConversationMemoryProperties conversationMemoryProperties;

    @Tool("写入文件到指定路径。若目标文件已存在则拒绝写入——请改用 modifyFile 进行增量修改，或先 deleteFile 再 writeFile。仅用于创建新文件。")
    public String writeFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要写入文件的原始文件内容。请直接传源码文本，不要额外包一层引号，不要手工做二次转义；工具调用本身会处理 JSON 层转义。")
            String content,
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
                return "错误：禁止写入项目目录外的文件 - " + relativeFilePath;
            }

            // 文件已存在时拒绝覆盖，引导使用 modifyFile 或 deleteFile + writeFile
            if (Files.exists(path)) {
                return "错误：文件已存在 - " + relativeFilePath
                        + "。请优先使用 modifyFile 工具对该文件进行增量修改（oldContent → newContent），"
                        + "或先使用 deleteFile 删除该文件后再 writeFile 重新创建。";
            }

            Path parentDir = path.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }

            if (content != null) {
                content = content.stripTrailing();
            }
            if (relativeFilePath.endsWith(".vue")) {
                content = VueSfcRepairHelper.repairVueSfcContent(content);
            }

            if (content == null) {
                return "错误：写入内容为空 - " + relativeFilePath;
            }

            Files.write(path, content.getBytes(),
                    StandardOpenOption.CREATE_NEW);
            registerFileNoteAfterWrite(appId, projectRoot, path, content);
            return "文件写入成功: " + relativeFilePath;
        } catch (IOException e) {
            String errorMessage = "文件写入失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    @Override
    public String getToolName() {
        return "writeFile";
    }

    @Override
    public String getDisplayName() {
        return "写入文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String rawContent = arguments.getStr("content");
        String displayContent = rawContent == null ? "" : rawContent.stripTrailing();
        return String.format("""
            [工具调用] %s %s
            文件内容:
            ```%s
            %s
            ```
            """, getDisplayName(), arguments.getStr("relativeFilePath"),
                FileUtil.getSuffix(arguments.getStr("relativeFilePath")),
                displayContent);
    }

    private void registerFileNoteAfterWrite(Long appId, Path projectRoot, Path absoluteFile, String writtenContent) {
        try {
            String relative = toRelativePath(projectRoot, absoluteFile);
            if (relative == null) {
                return;
            }
            int maxChars = conversationMemoryProperties == null ? 2000 : conversationMemoryProperties.getFileNoteInputChars();
            String hint = ConversationMemoryFileNoteSupport.truncateHint(
                    StrUtil.blankToDefault(writtenContent, ""), maxChars);
            conversationMemoryFileNoteService.registerPendingFileChange(appId, relative, hint);
        } catch (Exception ignore) {
            // fileNote 失败不阻塞写盘
        }
    }
}
