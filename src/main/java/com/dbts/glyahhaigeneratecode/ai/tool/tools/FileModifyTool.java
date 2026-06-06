package com.dbts.glyahhaigeneratecode.ai.tool.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.ai.tool.tool_assist.FileModifyTool_Assist;
import com.dbts.glyahhaigeneratecode.config.ConversationMemoryProperties;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryFileNoteSupport;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.ConversationMemoryFileNoteService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 文件修改工具
 * 支持 AI 通过工具调用的方式修改文件内容
 *
 * 解析项目根与目标路径并越界校验 -> 匹配 oldContent 并替换写盘 -> 登记 fileNote 待摘要 -> 返回执行结果给模型与前端卡片
 *
 * 整体逻辑:
 * 1. 先获取文件的绝对路径，并做越界校验
 * 2. 文件错误排查 (不存在/不是文件)
 * 3. 校验是否为关键文件，避免直接修改核心配置/入口
 * 4. 读取原始内容并按旧内容 → 新内容进行替换后回写
 */
@Slf4j
@Component
public class FileModifyTool extends BaseTool {

    @Resource
    private AppService appService;

    @Resource
    private ConversationMemoryFileNoteService conversationMemoryFileNoteService;

    @Resource
    private ConversationMemoryProperties conversationMemoryProperties;

    @Resource
    private FileModifyTool_Assist fileModifyToolAssist;

    /**
     * 清空去重集合。
     *
     * 由 {@link com.dbts.glyahhaigeneratecode.core.AiCodeGeneratorFacade} 在每轮生成开始时调用，
     * 避免上一轮已成功修改的记录影响本轮 modifyFile 去重判断。
     */
    public void clearRoundDedup() {
        fileModifyToolAssist.clearRoundDedup();
    }

    @Tool("对文件进行小范围精确替换。oldContent 和 newContent 应尽可能简短，"
        + "仅描述需变更的具体片段。禁止将整个文件内容作为替换目标。"
        + "若需大规模重构，请分多次小范围修改。"
        + "重要：oldContent 必须从 readFile 返回的原文中逐字符复制（含缩进空格），禁止自行想象缩进。"
        + "工具调用期间禁止输出任何自然语言内容；全部工具调用完成后先调用 exit 工具，再输出一段最终修改总结")
    /**
     * 工具回调：对项目内文件做小范围 oldContent → newContent 精确替换并写回磁盘
     *
     * @param relativeFilePath 项目内相对路径或已规范化的绝对路径
     * @param oldContent       待替换的原文片段，须与 readFile 返回值逐字符一致
     * @param newContent       替换后的新片段，可为空串表示删除
     * @param appId            应用 id，用于解析项目根目录与会话记忆登记
     * @return 成功、跳过或失败的中文说明，供模型下一轮决策
     */
    public String modifyFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要替换的旧内容")
            String oldContent,
            @P("替换后的新内容")
            String newContent,
            @ToolMemoryId Long appId
    ) {
        // 方法大纲：
        // 1. 解析项目根与目标绝对路径，完成越界与文件存在性校验
        // 2. 按 codeGenType 判断是否允许修改重要文件，并校验替换片段长度与同轮去重
        // 3. 读取原文、匹配 oldContent（精确或空白容错）、执行替换写盘
        // 4. 登记 fileNote 并返回执行结果；写盘失败由 catch 转为错误文案

        try {
            // 1. 解析项目根目录，失败则无法定位代码产物目录
            Path projectRoot = resolveNormalizedProjectRoot(appId, appService);
            if (projectRoot == null) {
                return "错误：无效的应用 ID，无法解析项目目录 - " + relativeFilePath;
            }
            // 2. 将相对路径解析为规范化绝对路径，供后续越界校验与读写使用
            Path raw = Paths.get(relativeFilePath);
            Path path = raw.isAbsolute()
                    ? raw.normalize().toAbsolutePath()
                    : projectRoot.resolve(raw).normalize().toAbsolutePath();

            // 3. 禁止修改项目目录外的文件，防止路径穿越
            if (!path.startsWith(projectRoot)) {
                return "错误：禁止修改项目目录外的文件 - " + relativeFilePath;
            }

            // 4. 目标必须是已存在的普通文件
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return "错误：文件不存在或不是文件 - " + relativeFilePath;
            }

            // 避免直接修改关键配置/入口文件
            String fileName = path.getFileName().toString();
            if (fileModifyToolAssist.isImportantFile(fileName)) {
                // 对 HTML / MULTI_FILE：允许修改核心产物文件（否则“增量编辑”无法落地）
                // 对 vue 无法修改核心产物文件
                // 5. 按应用代码生成类型决定是否放行 index.html 等核心 Web 产物
                CodeGenTypeEnum codeGenType = fileModifyToolAssist.getCodeGenType(appId, appService);
                if (fileModifyToolAssist.isModifyBlockedForImportantFile(fileName, codeGenType)) {
                    return "错误：不允许直接修改重要文件 - " + fileName;
                }
            }

            // Guard against full-file replacements masquerading as "modify".
            // The larger of oldContent and newContent must not exceed 5000 chars.
            // 6. 限制单次替换片段体积，防止模型用 modifyFile 变相整文件覆盖
            int maxReplaceLen = Math.max(
                    oldContent != null ? oldContent.length() : 0,
                    newContent != null ? newContent.length() : 0);
            if (maxReplaceLen > 8000) {
                return String.format(
                        "错误：修改内容过大（%d 字符，最大允许 8000）。"
                        + "请缩小修改范围，仅替换需变更的具体片段，不要将整个文件作为替换内容。"
                        + "如需大规模重构，请分多次小范围修改。",
                        maxReplaceLen);
            }

            // 同轮去重：相同 (path, oldContent, newContent) 已成功执行过则直接返回
            // 7. 构建去重 key, 供后续set<string>校验是否重复，命中则跳过重复写盘与重复 UI 卡片
            String dedupKey = fileModifyToolAssist.buildDedupKey(appId, relativeFilePath, oldContent, newContent);
            if (fileModifyToolAssist.isDuplicateModify(dedupKey)) {
                return "文件已通过相同参数修改过，跳过重复执行: " + relativeFilePath
                        + "。请勿重复调用 modifyFile，如需进一步修改请使用不同的 oldContent/newContent。";
            }

            // 8. 读取磁盘原文，作为替换操作的唯一真实来源
            String originalContent = Files.readString(path);
            if (oldContent == null || oldContent.isEmpty()) {
                return "错误：旧内容不能为空";
            }

            // 匹配策略：先精确 contains，失败再尝试空白容错匹配
            // 9. 优先精确匹配 oldContent, 确保当前磁盘上一定有oldContent；失败时调用 Assist 做缩进容错
            String matchedOldContent = fileModifyToolAssist.resolveMatchedOldContent(originalContent, oldContent);
            boolean matched = matchedOldContent != null;
            if (matched && !matchedOldContent.equals(oldContent)) {
                log.info("modifyFile 精确匹配失败但空白容错匹配成功: {}", relativeFilePath);
            }

            if (!matched) {
                // 提供文件片段帮助模型定位问题
                // 10. 匹配失败时附带诊断片段与缩进风格提示，引导模型重新 readFile
                String snippet = fileModifyToolAssist.buildMismatchSnippet(originalContent, oldContent);
                return "警告：文件中未找到要替换的内容，文件未修改 - " + relativeFilePath
                        + "\n可能原因：① oldContent 的缩进空格数与文件实际缩进不一致（文件使用 "
                        + fileModifyToolAssist.detectIndentStyle(originalContent)
                        + "）；② oldContent 包含额外/缺失的空白字符；③ oldContent 片段不存在于当前文件中。"
                        + "\n请使用 readFile 工具重新读取文件，确认目标片段的精确内容（含缩进）后再试。"
                        + snippet;
            }

            // 最核心的替换string内容
            // 11. 用匹配到的原文片段执行 replace，并校验替换后内容确有变化
            if (newContent == null) log.warn("修改文件工具 -> ai返回了空的更新字符串");
            String modifiedContent = originalContent.replace(matchedOldContent, newContent == null ? "" : newContent);
            if (originalContent.equals(modifiedContent)) {
                return "错误：替换后文件内容未发生变化（" + relativeFilePath + "）。"
                        + "可能是 oldContent 与 newContent 等价。"
                        + "禁止使用相同参数重复调用 modifyFile；若无需进一步修改，请立即调用 exit 工具结束。";
            }

            // 12. 写回磁盘并记录日志
            Files.writeString(path, modifiedContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("成功修改文件: {} (匹配方式: {})", path.toAbsolutePath(),
                    matchedOldContent.equals(oldContent) ? "精确" : "空白容错");

            // 记录成功修改，防止同轮重复
            // 13. 写入set<string>，防止同轮内模型重复调用相同参数
            fileModifyToolAssist.markModifyExecuted(dedupKey);

            // 14. 异步登记 fileNote，供轮次收口后批量 LLM 摘要（失败不阻塞改盘）
            registerFileNoteAfterModify(appId, projectRoot, path, oldContent, newContent);
            return "文件修改成功: " + relativeFilePath;

        } catch (IOException e) {

            // 15. 读写磁盘异常时记录日志并返回可读错误信息给模型
            String errorMessage = "修改文件失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    /**
     * 返回工具在 LangChain4j 注册表中的英文方法名
     *
     * @return 固定为 modifyFile
     */
    @Override
    public String getToolName() {
        return "modifyFile";
    }

    /**
     * 返回工具在前端卡片与日志中的中文显示名
     *
     * @return 固定为「修改文件」
     */
    @Override
    public String getDisplayName() {
        return "修改文件";
    }

    /**
     * 改盘成功后登记待摘要路径，供轮次收口后批量生成 fileNote
     *
     * @param appId        应用 id
     * @param projectRoot  项目根目录
     * @param absoluteFile 已修改文件的绝对路径
     * @param oldContent   替换前片段
     * @param newContent   替换后片段
     */
    private void registerFileNoteAfterModify(
            Long appId, Path projectRoot, Path absoluteFile, String oldContent, String newContent) {
        try {
            // 1. 将绝对路径转为项目内相对路径
            String relative = this.toRelativePath(projectRoot, absoluteFile);
            if (relative == null) {
                return;
            }
            // 2. 按配置截断前后片段，组装变更 hint 供后续 LLM 摘要
            int maxChars = conversationMemoryProperties == null ? 8000 : conversationMemoryProperties.getFileNoteInputChars();
            String hint = "替换前片段:\n"
                    + ConversationMemoryFileNoteSupport.truncateHint(StrUtil.blankToDefault(oldContent, ""), maxChars / 2)
                    + "\n替换后片段:\n"
                    + ConversationMemoryFileNoteSupport.truncateHint(StrUtil.blankToDefault(newContent, ""), maxChars / 2);
            // 3. 登记到 ConversationMemoryFileNoteService，同路径本轮以最新 hint 覆盖
            conversationMemoryFileNoteService.registerPendingFileChange(appId, relative, hint);
        } catch (Exception ignore) {
            // fileNote 失败不阻塞改盘
        }
    }

    /**
     * 生成工具执行结果文案，供聊天历史与前端工具卡片展示
     *
     * @param arguments 工具调用参数的 JSON 对象
     * @return 含路径与替换前后代码块的格式化文本
     */
    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        // 1. 从参数中取出替换前后内容
        String oldContent = arguments.getStr("oldContent");
        String newContent = arguments.getStr("newContent");
        // 2. 截断后嵌入卡片，避免超大代码块拖慢前端渲染
        String oldTruncated = fileModifyToolAssist.truncateForCard(oldContent, 1024);
        String newTruncated = fileModifyToolAssist.truncateForCard(newContent, 1024);
        return String.format("""
            [工具调用] %s %s
            替换前:
            ```
            %s
            ```
            替换后:
            ```
            %s
            ```
            """, getDisplayName(), arguments.getStr("relativeFilePath"),
                oldTruncated, newTruncated);
    }
}
