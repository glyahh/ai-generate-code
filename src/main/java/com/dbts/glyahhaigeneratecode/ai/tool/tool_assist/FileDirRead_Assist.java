package com.dbts.glyahhaigeneratecode.ai.tool.tool_assist;

import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * readDir 目录遍历辅助：忽略规则判定 -> 可剪枝递归收集 -> 相对深度计算
 */
@Component
public class FileDirRead_Assist {

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

    /**
     * 可剪枝的递归文件收集：遇到 IGNORED_NAMES 中的目录时跳过子树；
     * 以 IGNORED_EXTENSIONS 结尾的文件也被跳过。
     *
     * @param dir          当前遍历目录
     * @param projectRoot  项目根路径（保留入参以与原实现一致）
     * @param collector    收集到的文件路径列表
     */
    public void collectFilesWithPruning(Path dir, Path projectRoot, List<Path> collector) {
        File[] children = dir.toFile().listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = child.getName();
            if (child.isDirectory()) {
                // 剪枝：遇到 node_modules / dist / build / .git 等目录直接跳过
                if (IGNORED_NAMES.contains(name)) {
                    continue;
                }
                collectFilesWithPruning(child.toPath(), projectRoot, collector);
            } else {
                if (!shouldIgnore(name)) {
                    collector.add(child.toPath());
                }
            }
        }
    }

    /**
     * 计算文件相对于项目根的深度（目录层级），从 0 开始
     *
     * @param projectRoot 项目根路径
     * @param filePath    目标文件路径
     * @return 相对深度，从 0 开始
     */
    public int getRelativePathDepth(Path projectRoot, Path filePath) {
        Path relative = projectRoot.relativize(filePath);
        return Math.max(0, relative.getNameCount() - 1);
    }

    /**
     * 判断是否应该忽略该文件（仅按文件名和扩展名）
     *
     * @param fileName 文件名
     * @return true 表示应忽略
     */
    public boolean shouldIgnore(String fileName) {
        // 检查是否在忽略名称列表中
        if (IGNORED_NAMES.contains(fileName)) {
            return true;
        }
        // 检查文件扩展名
        return IGNORED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }
}
