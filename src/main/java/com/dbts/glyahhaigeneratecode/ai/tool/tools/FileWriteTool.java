package com.dbts.glyahhaigeneratecode.ai.tool.tools;

import cn.hutool.core.io.FileUtil;
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
import java.nio.file.StandardOpenOption;

/**
 * 文件写入工具。
 * 支持 AI 通过工具调用的方式将生成内容落盘到项目目录。
 */
@Slf4j
@Component
public class FileWriteTool extends BaseTool {

    @Tool("写入文件到指定路径")
    public String writeFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要写入文件的原始文件内容。请直接传源码文本，不要额外包一层引号，不要手工做二次转义；工具调用本身会处理 JSON 层转义。")
            String content,
            @ToolMemoryId Long appId
    ) {
        try {
            Path path = Paths.get(relativeFilePath);
            if (!path.isAbsolute()) {
                String projectDirName = "vue_project_" + appId;
                Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName);
                path = projectRoot.resolve(relativeFilePath);
            }

            Path parentDir = path.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }

            if (relativeFilePath.endsWith(".vue")) {
                content = repairVueSfcContent(content);
            }

            Files.write(path, content.getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
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
        return String.format("""
            [工具调用] %s %s
            文件内容:
            ```%s
            %s
            ```
            """, getDisplayName(), arguments.getStr("relativeFilePath"),
                FileUtil.getSuffix(arguments.getStr("relativeFilePath")),
                arguments.getStr("content"));
    }

    private String sanitizeVueContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        String result = content;

        int excessTemplate = countMatches(result, "</template>") - countMatches(result, "<template");
        for (int i = 0; i < excessTemplate; i++) {
            int lastIdx = result.lastIndexOf("</template>");
            if (lastIdx >= 0) {
                result = result.substring(0, lastIdx) + result.substring(lastIdx + "</template>".length());
            }
        }

        int excessScript = countMatches(result, "</script>") - countMatches(result, "<script");
        for (int i = 0; i < excessScript; i++) {
            int lastIdx = result.lastIndexOf("</script>");
            if (lastIdx >= 0) {
                result = result.substring(0, lastIdx) + result.substring(lastIdx + "</script>".length());
            }
        }

        return result.stripTrailing();
    }

    /**
     * 针对 .vue 文件做最小兜底，避免顶层 SFC 标签不平衡导致构建失败。
     */
    private String repairVueSfcContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String result = sanitizeVueContent(content);
        result = appendMissingClosingTag(result, "<template", "</template>");
        result = appendMissingClosingTag(result, "<script", "</script>");
        result = appendMissingClosingTag(result, "<style", "</style>");
        return result.stripTrailing() + System.lineSeparator();
    }

    private String appendMissingClosingTag(String text, String openingTagPrefix, String closingTag) {
        int openingCount = countMatches(text, openingTagPrefix);
        int closingCount = countMatches(text, closingTag);
        int missingCount = openingCount - closingCount;
        if (missingCount <= 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (int i = 0; i < missingCount; i++) {
            sb.append(System.lineSeparator()).append(closingTag);
        }
        return sb.toString();
    }

    private int countMatches(String text, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }
}
