package com.dbts.glyahhaigeneratecode.ai.tool.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.ai.tool.tool_assist.FileDirRead_Assist;
import com.dbts.glyahhaigeneratecode.service.AppService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文件目录读取工具
 * 使用 Hutool 简化文件操作
 *
 * 整体逻辑:
 * 1. 组合出要读取的目录绝对路径，并校验是否越界
 * 2. 目录错误排查 (不存在/不是目录)
 * 3. 使用 Hutool 递归遍历目录，按忽略规则过滤文件
 * 4. 按层级与名称排序后，生成带缩进的目录结构文本
 */
@Slf4j
@Component
public class FileDirReadTool extends BaseTool {

    @Resource
    private AppService appService;

    @Resource
    private FileDirRead_Assist fileDirReadAssist;

    @Tool("读取目录结构，获取指定目录下的所有文件和子目录信息")
    public String readDir(
            @P("目录的相对路径，为空则读取整个项目结构")
            String relativeDirPath,
            @ToolMemoryId Long appId
    ) {
        try {
            String safeRelative = relativeDirPath == null ? "" : relativeDirPath;
            Path projectRoot = resolveNormalizedProjectRoot(appId, appService);
            if (projectRoot == null) {
                return "错误：无效的应用 ID，无法解析项目目录 - " + safeRelative;
            }
            Path raw = Paths.get(safeRelative);
            Path path = raw.isAbsolute()
                    ? raw.normalize().toAbsolutePath()
                    : projectRoot.resolve(raw).normalize().toAbsolutePath();

            if (!path.startsWith(projectRoot)) {
                return "错误：禁止读取项目目录外的内容 - " + safeRelative;
            }

            File targetDir = path.toFile();
            if (!targetDir.exists() || !targetDir.isDirectory()) {
                return "错误：目录不存在或不是目录 - " + safeRelative;
            }

            StringBuilder structure = new StringBuilder();
            structure.append("项目目录结构:\n");

            // 从 targetDir 开始递归遍历，遇到 node_modules / dist / build / .git 等目录直接跳过子树
            List<Path> allPaths = new ArrayList<>();
            fileDirReadAssist.collectFilesWithPruning(targetDir.toPath(), projectRoot, allPaths);

            // 按相对路径深度和名称排序显示，靠前展示上层结构
            // 这里 allPaths 已经是告诉Comparator泛型是Path
            allPaths.sort(Comparator
                    // 相对根目录的深度
                    .comparingInt((Path p) -> fileDirReadAssist.getRelativePathDepth(projectRoot, p))
                    // 路径字符串小写字母序
                    .thenComparing(p -> p.toString().toLowerCase()));

            // 根据排序的path添加缩进拼接返回给ai
            for (Path filePath : allPaths) {
                int depth = fileDirReadAssist.getRelativePathDepth(projectRoot, filePath);
                String indent = "  ".repeat(Math.max(depth, 0));
                // 输出项目内相对路径，便于模型定位真实文件
                String relativePath = toRelativePath(projectRoot, filePath);
                structure.append(indent)
                        .append(relativePath != null ? relativePath : filePath.getFileName())
                        .append('\n');
            }

            return structure.toString();
        } catch (Exception e) {
            String errorMessage = "读取目录结构失败: " + relativeDirPath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    @Override
    public String getToolName() {
        return "readDir";
    }

    @Override
    public String getDisplayName() {
        return "读取目录结构";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String dir = StrUtil.blankToDefault(
                arguments.getStr("relativeDirPath"),
                ".");
        return String.format("""
            [工具调用] %s %s
            """, getDisplayName(), dir);
    }
}
