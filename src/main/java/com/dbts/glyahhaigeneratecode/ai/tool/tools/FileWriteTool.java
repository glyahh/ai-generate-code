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
@Component
public class FileWriteTool extends BaseTool {

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
            // .vue 文件：移除 AI 多余输出的尾部闭合标签，避免 vite build 报 Invalid end tag
            if (relativeFilePath.endsWith(".vue")) {
                content = sanitizeVueContent(content);
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

    @Override
    public String getToolName() {
        return "writeFile";
    }

    @Override
    public String getDisplayName() {
        return "写入文件";
    }

    // JSONObject 为 AI 调用工具时序列化后的参数 JSON，按 key 取值
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

    /**
     * 清理 AI 在 .vue 文件末尾多余输出的闭合标签。有的煞笔模型会瞎写
     * 例如 AI 有时会在文件末尾多输出 </template> 或 </script>，
     * 导致 vite build 报 "Invalid end tag"。
     * 通过比较开/闭标签数量，从末尾删除多余的闭合标签。
     */
    private String sanitizeVueContent(String content) {
        if (content == null || content.isEmpty()) return content;

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
