package com.dbts.glyahhaigeneratecode.ai.tool.tool_assist;

import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * modifyFile 辅助判定：同轮去重、重要文件守卫、内容匹配容错与诊断、卡片截断等
 */
@Component
public class FileModifyTool_Assist {

    /**
     * 同轮去重：记录已成功修改的 (relativeFilePath, oldContent, newContent) 组合，
     * 避免模型因重试/幻觉重复调用相同参数产生重复 UI 卡片。
     * key = "{appId}|{relativeFilePath}|{oldContentHash}|{newContentHash}"
     */
    private final Set<String> roundModifyDedupSet = ConcurrentHashMap.newKeySet();

    /**
     * 清空去重集合。
     *
     * 由 {@link com.dbts.glyahhaigeneratecode.core.AiCodeGeneratorFacade} 在每轮生成开始时调用，
     * 避免上一轮已成功修改的记录影响本轮 modifyFile 去重判断。
     */
    public void clearRoundDedup() {
        // 1. 清空本轮已成功修改的参数组合缓存
        roundModifyDedupSet.clear();
    }

    /**
     * 判断同轮去重 key 是否已存在。
     *
     * @param dedupKey 去重 key
     * @return true 表示本轮已成功执行过相同参数
     */
    public boolean isDuplicateModify(String dedupKey) {
        return dedupKey != null && !dedupKey.isEmpty() && roundModifyDedupSet.contains(dedupKey);
    }

    /**
     * 记录本轮已成功执行的 modifyFile 参数组合。
     *
     * @param dedupKey 去重 key
     */
    public void markModifyExecuted(String dedupKey) {
        if (dedupKey != null && !dedupKey.isEmpty()) {
            roundModifyDedupSet.add(dedupKey);
        }
    }

    /**
     * 构建同轮去重的 key。
     *
     * @param appId            应用 id
     * @param relativeFilePath 文件相对路径
     * @param oldContent       待替换旧内容
     * @param newContent       替换后新内容
     * @return 去重 key；appId 或路径为空时返回空串表示不参与去重
     */
    public String buildDedupKey(Long appId, String relativeFilePath, String oldContent, String newContent) {
        // 1. 必要参数缺失时不生成 key，调用方将跳过去重写入
        if (appId == null || relativeFilePath == null) {
            return "";
        }
        // 2. 用内容 hash 拼接 key，避免同轮相同参数重复写盘
        int oldHash = oldContent != null ? oldContent.hashCode() : 0;
        int newHash = newContent != null ? newContent.hashCode() : 0;
        return appId + "|" + relativeFilePath + "|" + oldHash + "|" + newHash;
    }

    /**
     * 将字符串中所有连续空白字符归一化为单个空格。
     *
     * @param s 原始字符串
     * @return 归一化后的字符串；null 或空串返回空串
     */
    public String normalizeWhitespace(String s) {
        // 1. 空输入直接返回空串
        if (s == null || s.isEmpty()) {
            return "";
        }
        // 2. 遍历字符，将连续空白折叠为单个空格并去除首尾空白
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace) {
                    sb.append(' ');
                    lastWasSpace = true;
                }
            } else {
                sb.append(c);
                lastWasSpace = false;
            }
        }
        return sb.toString().trim();
    }

    /**
     * 空白容错匹配：按行在原文中查找 oldContent 的对应位置，返回原文中的精确匹配片段。
     * 匹配逻辑：将每行的非空白内容作为"锚点"，在原文中按序查找，忽略行首缩进差异。
     *
     * @param original     文件原文
     * @param oldContent   模型提供的待替换片段
     * @return 原文中与 oldContent 对应的精确子串；匹配失败返回 null
     */
    public String tryWhitespaceTolerantMatch(String original, String oldContent) {
        // 方法大纲：
        // 1. 按行提取 oldContent 锚点并在原文中定位首行
        // 2. 逐行验证后续锚点是否在原文中按序出现
        // 3. 根据行偏移与缩进差异计算字符区间，返回原文精确子串

        // 1. 输入无效时无法做容错匹配
        if (original == null || oldContent == null || oldContent.isBlank()) {
            return null;
        }
        // 将 oldContent 按行拆分，取每行 trim 后的内容作为锚点
        String[] oldLines = oldContent.split("\\R", -1);
        // 去掉首尾空行（这些通常是噪声）
        // 2. 去掉首尾空行，确定有效锚点行范围
        int firstNonEmpty = 0;
        int lastNonEmpty = oldLines.length - 1;
        while (firstNonEmpty < oldLines.length && oldLines[firstNonEmpty].isBlank()) {
            firstNonEmpty++;
        }
        while (lastNonEmpty >= firstNonEmpty && oldLines[lastNonEmpty].isBlank()) {
            lastNonEmpty--;
        }
        if (firstNonEmpty > lastNonEmpty) {
            return null;
        }
        // 取第一行 trim 作为锚点，在原文中定位
        // 3. 用首行去缩进内容作为锚点，在原文中定位起始行
        String firstAnchor = oldLines[firstNonEmpty].stripLeading();
        if (firstAnchor.isEmpty()) {
            return null;
        }
        // 在原文中查找第一锚点（模糊匹配：原文行 stripLeading 后与 anchor 一致）
        String[] origLines = original.split("\\R", -1);
        int matchStartLine = -1;
        for (int i = 0; i < origLines.length; i++) {
            if (origLines[i].stripLeading().equals(firstAnchor)) {
                matchStartLine = i;
                break;
            }
        }
        if (matchStartLine < 0) {
            return null;
        }
        // 验证后续锚点是否在原文中连续匹配
        // 4. 校验后续各行锚点在原文中按序出现（允许小范围行偏移）
        int origLineIdx = matchStartLine + 1;
        for (int j = firstNonEmpty + 1; j <= lastNonEmpty; j++) {
            String anchor = oldLines[j].stripLeading();
            if (anchor.isEmpty()) {
                origLineIdx++;
                continue;
            }
            // 在后续 5 行内查找锚点
            boolean found = false;
            for (int k = origLineIdx; k < Math.min(origLineIdx + 5, origLines.length); k++) {
                if (origLines[k].stripLeading().equals(anchor)) {
                    origLineIdx = k + 1;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return null;
            }
        }
        // 提取原文中从 matchStartLine 到 origLineIdx-1 的精确片段
        // 5. 按匹配行号累加字符偏移，得到原文中的起止位置
        int charStart = 0;
        for (int i = 0; i < matchStartLine; i++) {
            charStart += origLines[i].length() + 1; // +1 for \n
        }
        // 跳过 matchStartLine 之前的行首空白，对齐 oldContent 的首行缩进
        String origFirstLine = origLines[matchStartLine];
        String oldFirstLine = oldLines[firstNonEmpty];
        int origLeadTrim = origFirstLine.length() - origFirstLine.stripLeading().length();
        int oldLeadTrim = oldFirstLine.length() - oldFirstLine.stripLeading().length();
        // 如果模型 oldContent 缩进少于原文，从原文更早位置开始（包含原文的缩进）
        // 6. 根据首尾行缩进差微调 charStart，使返回子串与磁盘原文一致
        if (oldLeadTrim < origLeadTrim) {
            charStart -= (origLeadTrim - oldLeadTrim);
            if (charStart < 0) charStart = 0;
        }
        // 如果模型 oldContent 缩进多于原文，从原文缩进开始处
        if (oldLeadTrim > origLeadTrim) {
            charStart += (oldLeadTrim - origLeadTrim);
        }
        int charEnd = 0;
        for (int i = 0; i < origLineIdx && i < origLines.length; i++) {
            charEnd += origLines[i].length() + 1;
        }
        if (charEnd > original.length()) {
            charEnd = original.length();
        }
        // 7. 截取原文精确子串供 replace 使用
        if (charEnd > charStart && charStart >= 0) {
            return original.substring(charStart, Math.min(charEnd, original.length()));
        }
        return null;
    }

    /**
     * 构建不匹配时的诊断片段，帮助模型定位问题。
     *
     * @param originalContent 磁盘原文
     * @param oldContent      模型提供的待替换片段
     * @return 附在错误信息后的诊断文本；无法定位时返回长度提示或空串
     */
    public String buildMismatchSnippet(String originalContent, String oldContent) {
        // 1. 输入缺失时不附加诊断
        if (originalContent == null || oldContent == null) {
            return "";
        }
        // 找到 oldContent 首行在原文中最近似的位置，提供前后各 100 字符的上下文
        // 2. 取 oldContent 首行作为模糊搜索锚点
        String firstLine = oldContent.trim().lines().findFirst().orElse("");
        if (firstLine.length() < 5) {
            return "";
        }
        // 归一化搜索
        // 3. 在归一化后的原文中查找首行近似位置
        String normFirst = normalizeWhitespace(firstLine);
        String normOriginal = normalizeWhitespace(originalContent);
        int idx = normOriginal.indexOf(normFirst);
        if (idx < 0) {
            return "\n[诊断] oldContent 首行在文件归一化后仍未匹配。文件总长度 " + originalContent.length()
                    + " 字符，oldContent 长度 " + oldContent.length() + " 字符。";
        }
        // 大致位置
        // 4. 将归一化索引映射回原文大致偏移，截取前后各 100 字符上下文
        int approxPos = idx * originalContent.length() / Math.max(1, normOriginal.length());
        int ctxStart = Math.max(0, approxPos - 100);
        int ctxEnd = Math.min(originalContent.length(), approxPos + 100);
        String ctx = originalContent.substring(ctxStart, ctxEnd);
        return "\n[诊断] 文件中粗略匹配位置的上下文 (…" + ctxStart + " → …" + ctxEnd + "):\n```\n" + ctx + "\n```";
    }

    /**
     * 检测文件的主导缩进风格。
     *
     * @param content 文件原文
     * @return 缩进风格中文描述，如「2 空格缩进」「Tab 缩进」
     */
    public String detectIndentStyle(String content) {
        // 1. 空内容无法判断缩进风格
        if (content == null || content.isEmpty()) {
            return "未知";
        }
        // 2. 统计各行行首 2 空格、4 空格、Tab 的出现次数
        int space2 = 0, space4 = 0, tab = 0;
        for (String line : content.lines().toList()) {
            if (line.startsWith("    ") && !line.startsWith("      ")) {
                space4++;
            } else if (line.startsWith("  ") && !line.startsWith("    ")) {
                space2++;
            } else if (line.startsWith("\t")) {
                tab++;
            }
        }
        // 3. 取出现次数最多的缩进方式作为主导风格
        if (space4 > space2 && space4 > tab) return "4 空格缩进";
        if (space2 > space4 && space2 > tab) return "2 空格缩进";
        if (tab > space2 && tab > space4) return "Tab 缩进";
        return "混合/未知缩进";
    }

    /**
     * 判断是否是重要文件，不允许修改
     *
     * @param fileName 文件名（不含路径）
     * @return true 表示属于受保护的重要文件
     */
    public boolean isImportantFile(String fileName) {
        // 1. 与 FileDeleteTool 类似，维护一份不允许随意修改的配置/入口文件名清单
        String[] importantFiles = {
                "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
                "vite.config.js", "vite.config.ts", "vue.config.js",
                "tsconfig.json", "tsconfig.app.json", "tsconfig.node.json",
                // 允许对 `src/App.vue` 进行局部可视化编辑（例如只改标题/文案），
                // 否则会导致 `modifyFile` 无法生效，从而出现“修改用户代码效果失败”。
                "index.html", "main.js", "main.ts", ".gitignore", "README.md"
        };
        // 2. 忽略大小写比对文件名
        for (String important : importantFiles) {
            if (important.equalsIgnoreCase(fileName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否为 HTML / MULTI_FILE 模式下允许修改的核心 Web 产物文件
     *
     * @param fileName 文件名（不含路径）
     * @return true 表示 index.html、style.css 或 script.js
     */
    public boolean isCoreWebOutputFile(String fileName) {
        // 1. 文件名为空时不视为核心产物
        if (fileName == null) {
            return false;
        }
        // 2. 仅这三类文件在 HTML/MULTI_FILE 增量编辑场景下允许修改
        return "index.html".equalsIgnoreCase(fileName)
                || "style.css".equalsIgnoreCase(fileName)
                || "script.js".equalsIgnoreCase(fileName);
    }

    /**
     * 根据应用 id 查询代码生成类型，供重要文件放行判断使用
     *
     * @param appId      应用 id
     * @param appService 应用查询服务
     * @return 代码生成类型枚举；查询失败返回 null
     */
    public CodeGenTypeEnum getCodeGenType(Long appId, AppService appService) {
        // 1. appId 无效时无法查询
        if (appId == null || appId <= 0) {
            return null;
        }
        try {
            // 2. 从 AppService 读取应用记录并解析 codeGenType 字段
            App app = appService == null ? null : appService.getById(appId);
            return app == null ? null : CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断当前文件名在指定 codeGenType 下是否禁止 modifyFile 修改。
     *
     * @param fileName    文件名（不含路径）
     * @param codeGenType 应用代码生成类型
     * @return true 表示不允许修改
     */
    public boolean isModifyBlockedForImportantFile(String fileName, CodeGenTypeEnum codeGenType) {
        if (!isImportantFile(fileName)) {
            return false;
        }
        return !(codeGenType == CodeGenTypeEnum.HTML || codeGenType == CodeGenTypeEnum.MULTI_FILE)
                || !isCoreWebOutputFile(fileName);
    }

    /**
     * 在原文中解析可替换片段：先精确匹配，失败再空白容错匹配。
     *
     * @param originalContent 磁盘原文
     * @param oldContent      模型提供的待替换片段
     * @return 匹配到的原文子串；未匹配返回 null
     */
    public String resolveMatchedOldContent(String originalContent, String oldContent) {
        if (originalContent == null || oldContent == null || oldContent.isEmpty()) {
            return null;
        }
        if (originalContent.contains(oldContent)) {
            return oldContent;
        }
        String tolerant = tryWhitespaceTolerantMatch(originalContent, oldContent);
        if (tolerant != null && originalContent.contains(tolerant)) {
            return tolerant;
        }
        return null;
    }
}
