package com.dbts.glyahhaigeneratecode.core.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为 Vue3 项目生成「片段修改」提示词上下文：
 * - 仅在修改意图 + 已存在 Vue 项目源码文件时生效
 * - 扫描 vue_project_{appId}/src/ 下的 .vue/.js/.ts/.css 文件
 * - 追加少量命中片段，避免每轮把全量代码喂给模型
 * - 若任一条件不满足，直接返回原始用户消息，不改变既有逻辑
 */
@Slf4j
@Component
public class VueEditContextBuilder {

    @Resource
    private HtmlMultiFileEditContextBuilder htmlBuilder;

    private static final int SNIPPET_CHAR_WINDOW = 1800;
    private static final int SNIPPET_HEAD_FALLBACK = 900;
    private static final int MAX_FILE_COUNT = 5;
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}_\\-]{2,}");

    /** Vue 源码目录下需要扫描的文件扩展名 */
    private static final String[] VUE_SOURCE_EXTENSIONS = {".vue", ".js", ".ts", ".css"};

    /**
     * 仅对 Vue 项目组装增量修改上下文；条件不满足时原样返回用户消息
     *
     * @param userMessage 用户输入
     * @param appId       应用 ID（用于定位 Vue 项目目录）
     * @return 可能被追加上下文后的提示词
     */
    public String buildPromptIfNeed(String userMessage, Long appId) {
        // 1. 参数不完整：直接透传，避免破坏其它链路
        if (StrUtil.isBlank(userMessage) || appId == null || appId <= 0) {
            return userMessage;
        }
        // 2. 非修改意图：不注入片段，避免首次生成被误判
        if (!htmlBuilder.isEditIntentMessage(userMessage)) {
            return userMessage;
        }

        // 3. 读取磁盘上已有 Vue 源码文件
        Map<String, String> existingFiles = loadVueProjectFiles(appId);
        if (existingFiles.isEmpty()) {
            return userMessage;
        }

        // 4. 从用户话里抽关键词，用于在文件中定位片段
        List<String> keywords = extractKeywords(userMessage);
        String context = buildEditContext(existingFiles, keywords);
        if (StrUtil.isBlank(context)) {
            return userMessage;
        }

        // 5. 打日志并拼接系统附加上下文
        log.info("启用 Vue 片段修改上下文，appId={}, keywords={}", appId, keywords);
        return userMessage + "\n\n" + context;
    }

    /**
     * 扫描 vue_project_{appId}/src/ 目录下的 .vue/.js/.ts/.css 文件，
     * 优先加载 .vue 文件，最多 MAX_FILE_COUNT 个，每个文件限截取字符。
     *
     * @param appId 应用 ID
     * @return 相对路径 → 文件内容（截断后）；目录不存在则为空 Map
     */
    private Map<String, String> loadVueProjectFiles(Long appId) {
        String baseDir = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + "vue_project_" + appId;
        File projectDir = new File(baseDir);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            return Map.of();
        }

        File srcDir = new File(projectDir, "src");
        if (!srcDir.exists() || !srcDir.isDirectory()) {
            return Map.of();
        }

        // 两轮扫描：先 .vue，再其他扩展名，保证 .vue 优先级
        Map<String, String> files = new LinkedHashMap<>();
        int maxCharsPerFile = SNIPPET_CHAR_WINDOW * 2;

        // 第一轮：优先加载 .vue 文件
        collectFiles(srcDir, srcDir, files, maxCharsPerFile);
        // 第二轮：补充 .js/.ts/.css（如果 .vue 还没排满 MAX_FILE_COUNT）
        if (files.size() < MAX_FILE_COUNT) {
            // .vue 已在第一轮中加载，collectFiles 会自动跳过已存在键
            // 这里利用 loadVueProjectFiles 只扫描 VUE_SOURCE_EXTENSIONS，
            // 由于第一轮已收集所有 .vue，第二轮自然只补充 .js/.ts/.css
        }

        return files;
    }

    /**
     * 递归收集 src 下的源码文件，优先 .vue 文件
     */
    private void collectFiles(File dir, File srcRoot, Map<String, String> files, int maxCharsPerFile) {
        if (files.size() >= MAX_FILE_COUNT) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        // 排序：.vue 文件先处理
        List<File> sortedFiles = new ArrayList<>();
        List<File> otherFiles = new ArrayList<>();
        List<File> subDirs = new ArrayList<>();

        for (File child : children) {
            if (child.isDirectory()) {
                subDirs.add(child);
            } else if (isVueSourceFile(child.getName())) {
                if (child.getName().endsWith(".vue")) {
                    sortedFiles.add(child);
                } else {
                    otherFiles.add(child);
                }
            }
        }

        // 先处理 .vue 文件
        for (File vueFile : sortedFiles) {
            if (files.size() >= MAX_FILE_COUNT) {
                return;
            }
            String relativePath = srcRoot.toPath().relativize(vueFile.toPath()).toString().replace('\\', '/');
            if (!files.containsKey(relativePath)) {
                String content = FileUtil.readUtf8String(vueFile);
                if (content != null && !content.isEmpty()) {
                    files.put(relativePath, truncateContent(content, maxCharsPerFile));
                }
            }
        }
        // 再处理其他源码文件
        for (File otherFile : otherFiles) {
            if (files.size() >= MAX_FILE_COUNT) {
                return;
            }
            String relativePath = srcRoot.toPath().relativize(otherFile.toPath()).toString().replace('\\', '/');
            if (!files.containsKey(relativePath)) {
                String content = FileUtil.readUtf8String(otherFile);
                if (content != null && !content.isEmpty()) {
                    files.put(relativePath, truncateContent(content, maxCharsPerFile));
                }
            }
        }
        // 递归子目录
        for (File subDir : subDirs) {
            collectFiles(subDir, srcRoot, files, maxCharsPerFile);
        }
    }

    private boolean isVueSourceFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : VUE_SOURCE_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private String truncateContent(String content, int maxCharsPerFile) {
        if (content.length() <= maxCharsPerFile) {
            return content;
        }
        return content.substring(0, maxCharsPerFile) + "\n// ...省略后续内容";
    }

    /**
     * 用正则从用户话中提取若干关键词（去重、上限 10）
     */
    private List<String> extractKeywords(String text) {
        List<String> out = new ArrayList<>();
        Matcher matcher = WORD_PATTERN.matcher(StrUtil.blankToDefault(text, ""));
        while (matcher.find()) {
            String w = matcher.group();
            if (StrUtil.isBlank(w)) {
                continue;
            }
            String lw = w.toLowerCase(Locale.ROOT);
            if (out.contains(lw)) {
                continue;
            }
            out.add(lw);
            if (out.size() >= 10) {
                break;
            }
        }
        return out;
    }

    /**
     * 在全文里按关键词命中位置截取窗口片段；无命中则取文件头部 fallback
     */
    private String pickSnippet(String content, List<String> keywords) {
        if (StrUtil.isBlank(content)) {
            return "";
        }
        String lower = content.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            int idx = lower.indexOf(keyword);
            if (idx >= 0) {
                return sliceWindow(content, idx, SNIPPET_CHAR_WINDOW);
            }
        }
        return content.length() <= SNIPPET_HEAD_FALLBACK ? content : content.substring(0, SNIPPET_HEAD_FALLBACK);
    }

    /**
     * 以 hitIndex 为中心截取 window 大小的子串，并在边界加省略注释
     */
    private String sliceWindow(String content, int hitIndex, int window) {
        int half = Math.max(200, window / 2);
        int start = Math.max(0, hitIndex - half);
        int end = Math.min(content.length(), hitIndex + half);
        String raw = content.substring(start, end);
        if (start > 0) {
            raw = "// ...省略上文\n" + raw;
        }
        if (end < content.length()) {
            raw = raw + "\n// ...省略下文";
        }
        return raw;
    }

    /**
     * 根据 Vue 文件扩展名选择 markdown 围栏语言标识
     */
    private String detectFenceLang(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".vue")) return "vue";
        if (lower.endsWith(".html")) return "html";
        if (lower.endsWith(".css")) return "css";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".ts")) return "typescript";
        return "text";
    }

    /**
     * 拼出追加在 user 消息后的「定向片段修改」系统说明 + 代码围栏
     */
    private String buildEditContext(Map<String, String> existingFiles, List<String> keywords) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("[系统附加上下文：定向片段修改]\n");
        sb.append("你正在修改一个已存在的 Vue3 项目。\n");
        sb.append("严格要求：\n");
        sb.append("1) 仅改动与用户需求直接相关的区域，不改无关逻辑与结构。\n");
        sb.append("2) 优先围绕下方「现有代码片段」修改，避免整站重写。\n");
        sb.append("3) 若必须改多处，保持改动最小化并与现有风格一致。\n\n");
        sb.append("现有文件片段（用于定位，不一定是完整文件）：\n");

        int count = 0;
        for (Map.Entry<String, String> entry : existingFiles.entrySet()) {
            if (count >= MAX_FILE_COUNT) {
                break;
            }
            String fileName = entry.getKey();
            String content = StrUtil.blankToDefault(entry.getValue(), "");
            String snippet = pickSnippet(content, keywords);
            String lang = detectFenceLang(fileName);
            sb.append("- 文件: src/").append(fileName).append("\n");
            sb.append("```").append(lang).append("\n");
            sb.append(snippet).append("\n");
            sb.append("```\n\n");
            count++;
        }

        sb.append("修改要求：\n");
        sb.append("- 已存在的文件必须使用 modifyFile 进行增量修改，writeFile 对已有文件会被拒绝。\n");
        sb.append("- 创建新的 .vue 组件或页面文件时，可用 writeFile（文件不存在时允许写入）。\n");
        sb.append("- modifyFile 的 oldContent 必须从 readFile 返回的原文中逐字符复制（含缩进空格、换行），禁止自行想象缩进。\n");
        sb.append("- 修改完毕确认无误后调用 exit 工具结束。\n");
        return sb.toString();
    }
}