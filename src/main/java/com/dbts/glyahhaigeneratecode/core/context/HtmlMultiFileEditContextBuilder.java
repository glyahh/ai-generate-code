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
            "着重", "突出", "强调", "侧重", "偏向", "重点",
            "modify", "change", "update", "fix", "refactor", "tune", "improve",
            "enhance", "refine", "tweak", "implement", "add", "remove", "delete",
            "insert", "patch", "hotfix", "upgrade", "improve"
    };

    /**
     * 仅对 HTML / MULTI_FILE 组装增量修改上下文；其余类型或条件不满足时原样返回用户消息
     *
     * @param userMessage       用户输入
     * @param codeGenTypeEnum   生成类型
     * @param appId             应用 ID（用于定位输出目录）
     * @return 可能被追加上下文后的提示词
     */
    public String buildPromptIfNeed (String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        // 1. 参数不完整：直接透传，避免破坏其它链路
        if (StrUtil.isBlank(userMessage) || codeGenTypeEnum == null || appId == null || appId <= 0) {
            return userMessage;
        }
        // 2. 仅增强 HTML / MULTI_FILE
        if (codeGenTypeEnum != CodeGenTypeEnum.HTML && codeGenTypeEnum != CodeGenTypeEnum.MULTI_FILE) {
            return userMessage;
        }
        // 3. 非修改意图：不注入片段，避免首次生成被误判
        if (!isEditIntent(userMessage)) {
            return userMessage;
        }

        // 4. 读取磁盘上已有源码文件映射
        Map<String, String> existingFiles = loadExistingFiles(codeGenTypeEnum, appId);
        if (existingFiles.isEmpty()) {
            return userMessage;
        }

        // 5. 从用户话里抽关键词，用于在文件中定位片段
        List<String> keywords = extractKeywords(userMessage);
        String context = buildEditContext(existingFiles, keywords, codeGenTypeEnum);
        if (StrUtil.isBlank(context)) {
            return userMessage;
        }

        // 6. 打日志并拼接系统附加上下文
        log.info("启用片段修改上下文，appId={}, codeGenType={}, keywords={}",
                appId, codeGenTypeEnum.getValue(), keywords);
        return userMessage + "\n\n" + context;
    }

    /**
     * 对外暴露的「修改意图」判定，供门面层决定是否走工具化编辑链路
     *
     * @param userMessage 用户输入
     * @return true 表示命中修改类关键词
     */
    public boolean isEditIntentMessage(String userMessage) {
        // 1. 委托内部关键词表判断
        return isEditIntent(userMessage);
    }

    /**
     * 判断当前 app 输出目录下是否已有可编辑的 HTML/CSS/JS 文件
     *
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @return true 表示至少存在一个目标文件
     */
    public boolean hasExistingEditableFiles(CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        // 1. 入参非法直接 false
        if (codeGenTypeEnum == null || appId == null || appId <= 0) {
            return false;
        }
        // 2. 有任意可读文件即 true
        return !loadExistingFiles(codeGenTypeEnum, appId).isEmpty();
    }

    /**
     * 扫描用户消息是否包含「修改/优化/fix」等编辑意图词（中英）
     *
     * @param userMessage 用户输入
     * @return 是否编辑意图
     */
    private boolean isEditIntent(String userMessage) {
        // 1. 统一小写做包含判断
        String lower = userMessage.toLowerCase(Locale.ROOT);
        // 2. 遍历预置词表
        for (String word : EDIT_INTENT_WORDS) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        // 3. 未命中则 false
        return false;
    }

    /**
     * 从 code_output 目录加载当前 app 已生成的源码文件内容
     *
     * @param codeGenTypeEnum 类型（决定读哪些固定文件名）
     * @param appId           应用 ID
     * @return 文件名 → 全文；目录不存在则为空 Map
     */
    private Map<String, String> loadExistingFiles(CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        // 1. 拼业务目录 html_{appId} 或 multi_file_{appId}
        String baseDir = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + codeGenTypeEnum.getValue() + "_" + appId;
        File dir = new File(baseDir);
        // 2. 目录不存在直接空
        if (!dir.exists() || !dir.isDirectory()) {
            return Map.of();
        }

        // 3. 按类型把存在的文件读入 LinkedHashMap（顺序稳定）
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

    /**
     * 若文件存在则读 UTF-8 内容放入 map
     *
     * @param files 目标 map
     * @param file  物理文件
     */
    private void putIfExists(Map<String, String> files, File file) {
        // 1. 存在且为普通文件才读取
        if (file.exists() && file.isFile()) {
            files.put(file.getName(), FileUtil.readUtf8String(file));
        }
    }

    /**
     * 用正则从用户话中提取若干关键词（去重、上限 10）
     *
     * @param text 用户原文
     * @return 小写关键词列表
     */
    private List<String> extractKeywords(String text) {
        List<String> out = new ArrayList<>();
        // 1. 用 WORD_PATTERN 扫描 token
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
            // 2. 控制数量，避免上下文噪音
            if (out.size() >= 10) {
                break;
            }
        }
        return out;
    }

    /**
     * 拼出追加在 user 消息后的「定向片段修改」系统说明 + 代码围栏
     *
     * @param existingFiles 磁盘已有文件内容
     * @param keywords      用户关键词
     * @param type          HTML 或 MULTI_FILE
     * @return 组装好的附加上下文
     */
    private String buildEditContext(Map<String, String> existingFiles, List<String> keywords, CodeGenTypeEnum type) {
        StringBuilder sb = new StringBuilder(2048);
        // 1. 写固定约束说明头
        sb.append("[系统附加上下文：定向片段修改]\n");
        sb.append("你正在修改一个已存在的 ")
                .append(type == CodeGenTypeEnum.HTML ? "单文件 HTML 页面" : "多文件原生网页项目")
                .append("。\n");
        sb.append("严格要求：\n");
        sb.append("1) 仅改动与用户需求直接相关的区域，不改无关逻辑与结构。\n");
        sb.append("2) 优先围绕下方“现有代码片段”修改，避免整站重写。\n");
        sb.append("3) 若必须改多处，保持改动最小化并与现有风格一致。\n\n");
        sb.append("现有文件片段（用于定位，不一定是完整文件）：\n");

        // 2. 遍历文件，截断片段写入 fenced block
        int count = 0;
        for (Map.Entry<String, String> entry : existingFiles.entrySet()) {
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

        // 3. 写修改要求尾段, 这里暂时采用工具调用修改文件内容的方案
        sb.append("修改要求：\n");
        sb.append("- 必须使用 modifyFile / deleteFile 等工具对现有文件进行精确修改，禁止重新输出完整代码块。\n");
        sb.append("- 修改完毕确认无误后调用 exit 工具结束。\n");
        return sb.toString();
    }

    /**
     * 在全文里按关键词命中位置截取窗口片段；无命中则取文件头部 fallback
     *
     * @param content  文件全文
     * @param keywords 关键词列表
     * @return 片段字符串
     */
    private String pickSnippet(String content, List<String> keywords) {
        // 1. 空内容直接空串
        if (StrUtil.isBlank(content)) {
            return "";
        }
        String lower = content.toLowerCase(Locale.ROOT);
        // 2. 依次尝试关键词命中位置
        for (String keyword : keywords) {
            int idx = lower.indexOf(keyword);
            if (idx >= 0) {
                return sliceWindow(content, idx, SNIPPET_CHAR_WINDOW);
            }
        }
        // 3. 无命中：返回前缀子串
        return content.length() <= SNIPPET_HEAD_FALLBACK ? content : content.substring(0, SNIPPET_HEAD_FALLBACK);
    }

    /**
     * 以 hitIndex 为中心截取 window 大小的子串，并在边界加省略注释
     *
     * @param content  全文
     * @param hitIndex 命中下标
     * @param window   窗口总宽
     * @return 带边界提示的片段
     */
    private String sliceWindow(String content, int hitIndex, int window) {
        // 1. 计算半窗宽与起止下标
        int half = Math.max(200, window / 2);
        int start = Math.max(0, hitIndex - half);
        int end = Math.min(content.length(), hitIndex + half);
        String raw = content.substring(start, end);
        // 2. 非从头开始则加「省略上文」
        if (start > 0) {
            raw = "// ...省略上文\n" + raw;
        }
        // 3. 未到文末则加「省略下文」
        if (end < content.length()) {
            raw = raw + "\n// ...省略下文";
        }
        return raw;
    }

    /**
     * 根据扩展名选择 markdown 围栏语言标识
     *
     * @param fileName 文件名
     * @return fence lang 字符串
     */
    private String detectFenceLang(String fileName) {
        // 1. 小写后缀分支
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "html";
        if (lower.endsWith(".css")) return "css";
        if (lower.endsWith(".js")) return "javascript";
        return "text";
    }
}
