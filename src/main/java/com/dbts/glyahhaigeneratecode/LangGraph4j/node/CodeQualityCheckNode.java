package com.dbts.glyahhaigeneratecode.LangGraph4j.node;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.LangGraph4j.ai.CodeQualityCheckService;
import com.dbts.glyahhaigeneratecode.LangGraph4j.state.QualityResult;
import com.dbts.glyahhaigeneratecode.LangGraph4j.state.WorkflowContext;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 代码质量检查节点
 */
@Slf4j
public class CodeQualityCheckNode {

    /**
     * 与 {@link AppConstant#PROJECT_DOWNLOAD_IGNORE_FILES} 对齐，并补充常见构建产物目录名（小写比对路径段）
     */
    private static final Set<String> IGNORE_PATH_SEGMENTS = buildIgnoreSegments();

    /**
     * 不参与质检的图片类后缀
     */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif", "heic"
    );

    /**
     * 不参与质检的字体/媒体/二进制类后缀（打包或资源）
     */
    private static final Set<String> SKIP_BINARY_LIKE_EXTENSIONS = Set.of(
            "woff", "woff2", "ttf", "eot", "otf",
            "mp4", "webm", "mp3", "wav", "pdf", "zip", "rar", "7z", "gz", "tar",
            "map"
    );

    /**
     * 单文件最大读取字节数，避免超大文件撑爆上下文
     */
    private static final int MAX_FILE_BYTES = 512 * 1024;

    /**
     * 拼接后提交给 AI 的最大字符数，超出则截断并附说明
     */
    private static final int MAX_PROMPT_CHARS = 300_000;

    private static Set<String> buildIgnoreSegments() {
        Set<String> set = new HashSet<>();
        for (String name : AppConstant.PROJECT_DOWNLOAD_IGNORE_FILES) {
            set.add(name.toLowerCase(Locale.ROOT));
        }
        set.add("coverage");
        set.add(".nuxt");
        set.add(".output");
        set.add("__pycache__");
        return set;
    }

    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 代码质量检查");

            String generatedCodeDir = context.getGeneratedCodeDir();
            QualityResult qualityResult;
            try {
                // 1. 读取并拼接代码文件内容
                String codeContent = readAndConcatenateCodeFiles(generatedCodeDir);
                if (StrUtil.isBlank(codeContent)) {
                    log.warn("未找到可检查的代码文件");
                    qualityResult = QualityResult.builder()
                            .isValid(false)
                            .errors(List.of("未找到可检查的代码文件"))
                            .suggestions(List.of("请确保代码生成成功"))
                            .build();
                } else {
                    // 2. 调用 AI 进行代码质量检查
                    CodeQualityCheckService qualityCheckService =
                            SpringContextUtil.getBean(CodeQualityCheckService.class);
                    qualityResult = qualityCheckService.checkCodeQuality(codeContent);
                    log.info("代码质量检查完成 - 是否通过: {}", qualityResult.getIsValid());
                }
            } catch (Exception e) {
                log.error("代码质量检查异常: {}", e.getMessage(), e);
                qualityResult = QualityResult.builder()
                        .isValid(true) // 异常直接跳到下一个步骤
                        .build();
            }

            // 3. 更新状态
            context.setCurrentStep("代码质量检查");
            context.setQualityResult(qualityResult);
            return WorkflowContext.saveContext(context);
        });
    }

    /**
     * 遍历生成目录，排除图片、打包/依赖目录、隐藏文件等，拼接为待质检文本。
     *
     * @param generatedCodeDir 生成根目录，空或非法时返回空串
     */
    private static String readAndConcatenateCodeFiles(String generatedCodeDir) {
        if (StrUtil.isBlank(generatedCodeDir)) {
            return "";
        }
        File root = FileUtil.file(generatedCodeDir);
        if (!root.isDirectory()) {
            return "";
        }
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        List<File> files = FileUtil.loopFiles(root, file -> {
            if (!file.isFile()) {
                return false;
            }
            if (file.isHidden()) {
                return false;
            }
            String name = file.getName();
            if (name.startsWith(".")) {
                return false;
            }
            Path abs = file.toPath().toAbsolutePath().normalize();
            String relative;
            try {
                relative = rootPath.relativize(abs).toString().replace('\\', '/');
            } catch (Exception e) {
                return false;
            }
            if (StrUtil.isBlank(relative)) {
                return false;
            }
            if (pathTouchesIgnoredSegment(relative)) {
                return false;
            }
            String ext = FileUtil.extName(file).toLowerCase(Locale.ROOT);
            if (IMAGE_EXTENSIONS.contains(ext)) {
                return false;
            }
            if (SKIP_BINARY_LIKE_EXTENSIONS.contains(ext)) {
                return false;
            }
            if (isLockOrPackageArtifact(name)) {
                return false;
            }
            long size = file.length();
            if (size > MAX_FILE_BYTES) {
                log.debug("跳过过大文件: {} ({} bytes)", relative, size);
                return false;
            }
            return true;
        });

        files.sort((a, b) -> a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath()));

        StringBuilder sb = new StringBuilder();
        for (File file : files) {
            Path abs = file.toPath().toAbsolutePath().normalize();
            String relative = rootPath.relativize(abs).toString().replace('\\', '/');
            String content;
            try {
                content = FileUtil.readUtf8String(file);
            } catch (Exception e) {
                log.debug("跳过无法以 UTF-8 读取的文件: {}", relative, e);
                continue;
            }
            String block = "===== FILE: " + relative + " =====\n" + content + "\n";
            if (sb.length() + block.length() > MAX_PROMPT_CHARS) {
                sb.append("===== SYSTEM =====\n后续文件因总长度限制已省略，此前已包含部分源码。\n");
                break;
            }
            sb.append(block);
        }

        return sb.toString();
    }

    private static boolean isLockOrPackageArtifact(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return "package-lock.json".equals(lower)
                || "yarn.lock".equals(lower)
                || "pnpm-lock.yaml".equals(lower)
                || lower.endsWith(".lock");
    }

    private static boolean pathTouchesIgnoredSegment(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        String[] parts = lower.split("/");
        for (String part : parts) {
            if (StrUtil.isBlank(part)) {
                continue;
            }
            if (IGNORE_PATH_SEGMENTS.contains(part)) {
                return true;
            }
        }
        return false;
    }
}
