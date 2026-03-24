package com.dbts.glyahhaigeneratecode.ai.tool.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

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

    /**
     * 需要忽略的文件和目录
     */
    private static final Set<String> IGNORED_NAMES = Set.of(
            "node_modules", ".git", "dist", "build", ".DS_Store",
            ".env", "target", ".mvn", ".idea", ".vscode", "coverage"
    );

    /**
     * 需要忽略的文件扩展名
     */
    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".log", ".tmp", ".cache", ".lock"
    );

    @Tool("读取目录结构，获取指定目录下的所有文件和子目录信息")
    public String readDir(
            @P("目录的相对路径，为空则读取整个项目结构")
            String relativeDirPath,
            @ToolMemoryId Long appId
    ) {
        try {
            String safeRelative = relativeDirPath == null ? "" : relativeDirPath;
            Path path = Paths.get(safeRelative);
            Path projectRoot = null;
            if (!path.isAbsolute()) {
                String projectDirName = "vue_project_" + appId;
                projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName)
                        .normalize()
                        .toAbsolutePath();
                path = projectRoot.resolve(safeRelative);
            }
            path = path.normalize().toAbsolutePath();

            // 相对路径时，限制在项目根目录下，避免越界访问
            if (projectRoot != null && !path.startsWith(projectRoot)) {
                return "错误：禁止读取项目目录外的内容 - " + safeRelative;
            }

            File targetDir = path.toFile();
            if (!targetDir.exists() || !targetDir.isDirectory()) {
                return "错误：目录不存在或不是目录 - " + safeRelative;
            }

            StringBuilder structure = new StringBuilder();
            structure.append("项目目录结构:\n");

            // 使用 Hutool 递归获取所有文件（已按忽略规则过滤）
            List<File> allFiles = FileUtil.loopFiles(targetDir, file -> !shouldIgnore(file.getName()));

            // 按路径深度和名称排序显示，靠前展示上层结构
            allFiles.stream()
                    .sorted(Comparator
                            .comparingInt((File f) -> getRelativeDepth(targetDir, f))
                            .thenComparing(f -> f.getPath().toLowerCase()))
                    .forEach(file -> {
                        int depth = getRelativeDepth(targetDir, file);
                        String indent = "  ".repeat(Math.max(depth, 0));
                        structure.append(indent)
                                .append(file.getName())
                                .append('\n');
                    });

            return structure.toString();
        } catch (Exception e) {
            String errorMessage = "读取目录结构失败: " + relativeDirPath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    /**
     * 计算文件相对于根目录的深度
     */
    private int getRelativeDepth(File root, File file) {
        Path rootPath = root.toPath();
        Path filePath = file.toPath();
        return Math.max(0, rootPath.relativize(filePath).getNameCount() - 1);
    }

    /**
     * 判断是否应该忽略该文件或目录
     */
    private boolean shouldIgnore(String fileName) {
        // 检查是否在忽略名称列表中
        if (IGNORED_NAMES.contains(fileName)) {
            return true;
        }
        // 检查文件扩展名
        return IGNORED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
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

