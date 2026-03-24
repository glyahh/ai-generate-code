package com.dbts.glyahhaigeneratecode.core.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为 HTML / MULTI_FILE 生成「片段修改」提示词上下文：
 * - 仅在修改意图 + 已存在历史代码文件时生效
 * - 追加少量命中片段，避免每轮把全量代码喂给模型
 * - 若任一条件不满足，直接返回原始用户消息，不改变既有逻辑
 */
@Slf4j
@Component
public class HtmlMultiFileEditContextBuilder {

    private static final int SNIPPET_CHAR_WINDOW = 1800;
    private static final int SNIPPET_HEAD_FALLBACK = 900;
    private static final int MAX_FILE_COUNT = 3;
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}_\\-]{2,}");
    private static final String[] EDIT_INTENT_WORDS = {
            "修改", "改", "调整", "优化", "修复", "替换", "改一下", "patch",
            "修正", "纠正", "修理", "bug", "error", "问题", "异常",
            "重构", "重写", "改写", "迭代", "增强", "强化", "升级", "更新", "改进",
            "新增", "增加", "补上", "实现", "做成", "做出来", "落地", "接入", "集成",
            "优化", "精简", "完善", "梳理", "整合", "清理", "清除", "移除", "删掉",
            "添加", "插入", "补充", "补丁", "改成", "hotfix", "hot fix",
            "重做", "返工", "微调", "微调一下", "精修", "对齐", "校正",
            "定向修改", "局部修改", "局部更新", "只改", "只需要改", "只改动",
            "modify", "change", "update", "fix", "refactor", "tune", "improve",
            "enhance", "refine", "tweak", "implement", "add", "remove", "delete",
            "insert", "patch", "hotfix", "upgrade", "improve"
    };

    /**
     * 仅对 HTML / MULTI_FILE 组装增量修改上下文。其余类型直接返回原消息。
     */
    public String buildPromptIfNeed (String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        // 第一层兜底：参数不完整时不增强，保持原链路行为
        if (StrUtil.isBlank(userMessage) || codeGenTypeEnum == null || appId == null || appId <= 0) {
            return userMessage;
        }
        // 只增强 HTML / MULTI_FILE，避免影响 VUE 的既有工具流
        if (codeGenTypeEnum != CodeGenTypeEnum.HTML && codeGenTypeEnum != CodeGenTypeEnum.MULTI_FILE) {
            return userMessage;
        }
        // 非“修改意图”请求直接透传，避免首次生成被误加约束
        if (!isEditIntent(userMessage)) {
            return userMessage;
        }

        Map<String, String> existingFiles = loadExistingFiles(codeGenTypeEnum, appId);
        if (existingFiles.isEmpty()) {
            return userMessage;
        }

        List<String> keywords = extractKeywords(userMessage);
        String context = buildEditContext(existingFiles, keywords, codeGenTypeEnum);
        if (StrUtil.isBlank(context)) {
            return userMessage;
        }

        log.info("启用片段修改上下文，appId={}, codeGenType={}, keywords={}",
                appId, codeGenTypeEnum.getValue(), keywords);
        return userMessage + "\n\n" + context;
    }

    private boolean isEditIntent(String userMessage) {
        String lower = userMessage.toLowerCase(Locale.ROOT);
        for (String word : EDIT_INTENT_WORDS) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> loadExistingFiles(CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        String baseDir = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + codeGenTypeEnum.getValue() + "_" + appId;
        File dir = new File(baseDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return Map.of();
        }

        Map<String, String> files = new LinkedHashMap<>();
        if (codeGenTypeEnum == CodeGenTypeEnum.HTML) {
            putIfExists(files, new File(dir, "index.html"));
        } else if (codeGenTypeEnum == CodeGenTypeEnum.MULTI_FILE) {
            putIfExists(files, new File(dir, "index.html"));
            putIfExists(files, new File(dir, "style.css"));
            putIfExists(files, new File(dir, "script.js"));
        }
        return files;
    }

    private void putIfExists(Map<String, String> files, File file) {
        if (file.exists() && file.isFile()) {
            files.put(file.getName(), FileUtil.readUtf8String(file));
        }
    }

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
            // 控制关键词数量，避免上下文噪音过高导致模型偏航
            if (out.size() >= 10) {
                break;
            }
        }
        return out;
    }

    private String buildEditContext(Map<String, String> existingFiles, List<String> keywords, CodeGenTypeEnum type) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("[系统附加上下文：定向片段修改]\n");
        sb.append("你正在修改一个已存在的 ")
                .append(type == CodeGenTypeEnum.HTML ? "单文件 HTML 页面" : "多文件原生网页项目")
                .append("。\n");
        sb.append("严格要求：\n");
        sb.append("1) 仅改动与用户需求直接相关的区域，不改无关逻辑与结构。\n");
        sb.append("2) 优先围绕下方“现有代码片段”修改，避免整站重写。\n");
        sb.append("3) 若必须改多处，保持改动最小化并与现有风格一致。\n\n");
        sb.append("现有文件片段（用于定位，不一定是完整文件）：\n");

        int count = 0;
        for (Map.Entry<String, String> entry : existingFiles.entrySet()) {
            // 限制片段文件数量，降低 token 消耗并聚焦问题文件
            if (count >= MAX_FILE_COUNT) {
                break;
            }
            String fileName = entry.getKey();
            String content = StrUtil.blankToDefault(entry.getValue(), "");
            String snippet = pickSnippet(content, keywords);
            String lang = detectFenceLang(fileName);
            sb.append("- 文件: ").append(fileName).append("\n");
            sb.append("```").append(lang).append("\n");
            sb.append(snippet).append("\n");
            sb.append("```\n\n");
            count++;
        }

        sb.append("输出要求（保持原有格式规则）：\n");
        if (type == CodeGenTypeEnum.HTML) {
            sb.append("- 只输出 1 个完整的 ```html``` 代码块（index.html）。\n");
        } else {
            sb.append("- 输出完整的 ```html```（index.html）、```css```（style.css）、```javascript```（script.js）代码块。\n");
        }
        return sb.toString();
    }

    private String pickSnippet(String content, List<String> keywords) {
        if (StrUtil.isBlank(content)) {
            return "";
        }
        String lower = content.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            int idx = lower.indexOf(keyword);
            if (idx >= 0) {
                // 命中关键词时优先返回命中窗口，提升“定向修改”命中率
                return sliceWindow(content, idx, SNIPPET_CHAR_WINDOW);
            }
        }
        // 无命中时退化为文件头部片段，保证仍有基础结构上下文
        return content.length() <= SNIPPET_HEAD_FALLBACK ? content : content.substring(0, SNIPPET_HEAD_FALLBACK);
    }

    private String sliceWindow(String content, int hitIndex, int window) {
        int half = Math.max(200, window / 2);
        int start = Math.max(0, hitIndex - half);
        int end = Math.min(content.length(), hitIndex + half);
        String raw = content.substring(start, end);
        // 用注释标记“节选边界”，避免模型把片段误判为完整文件
        if (start > 0) {
            raw = "// ...省略上文\n" + raw;
        }
        if (end < content.length()) {
            raw = raw + "\n// ...省略下文";
        }
        return raw;
    }

    private String detectFenceLang(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "html";
        if (lower.endsWith(".css")) return "css";
        if (lower.endsWith(".js")) return "javascript";
        return "text";
    }
}
