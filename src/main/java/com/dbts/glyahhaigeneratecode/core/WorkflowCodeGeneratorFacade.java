package com.dbts.glyahhaigeneratecode.core;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.LangGraph4j.CodeGenWorkflow;
import com.dbts.glyahhaigeneratecode.LangGraph4j.state.WorkflowContext;
import com.dbts.glyahhaigeneratecode.ai.model.message.AiResponseMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.StreamMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.StreamMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolExecutedMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolRequestMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.WorkflowChunkDedupState;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.ai.tool.ToolManager;
import com.dbts.glyahhaigeneratecode.constant.ChatHistoryConstant;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Workflow 代码生成门面。
 * 仅封装工作流执行与结果转换，避免控制层/服务层直接依赖工作流细节。
 */
@Service
@Slf4j
public class WorkflowCodeGeneratorFacade {

    private static final Set<String> VUE_ECHO_IGNORED_DIR_NAMES = Set.of(
            "node_modules", "dist", "build", ".git", ".idea", ".vscode", "coverage", "target", ".mvn"
    );

    private static final long VUE_ECHO_MAX_FILE_BYTES = 512L * 1024L;

    @Resource
    private ToolManager toolManager;

    /**
     * 异步执行 LangGraph 工作流，并把进度/代码块适配为 SSE 文本流
     *
     * @param userMessage       用户输入
     * @param codeGenTypeEnum   目标生成类型
     * @param appId             应用 ID
     * @param firstRound        是否首轮（透传给工作流）
     * @return 冷启动的 Flux，订阅后在后台线程跑 workflow
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage,
                                                  CodeGenTypeEnum codeGenTypeEnum,
                                                  Long appId,
                                                  boolean firstRound) {
        return Flux.create(sink -> {
            // 1. 异步执行，避免阻塞订阅线程导致首包延迟
            CompletableFuture.runAsync(() -> {
                try {
                    // 2. 构造工作流与去重状态
                    CodeGenWorkflow workflow = new CodeGenWorkflow();
                    WorkflowChunkDedupState dedupState = new WorkflowChunkDedupState();
                    // 工作流第...步完成
                    Consumer<String> progress = msg -> {
                        try {
                            // 3. 进度行统一补换行，避免 SSE 粘包
                            String normalized = normalizeWorkflowLine(msg);
                            sink.next(normalized);
                        } catch (Exception ignored) {
                        }
                    };
                    // 透传工作流中的工具调用信息
                    // 声明有了chunk该怎么拼接 (Consumer<String>本质就是声明一个处理字符串的方法)
                    Consumer<String> codeStreamChunkConsumer = rawChunk -> {
                        try {
                            // 解析ai返回的请求/意图
                            // 工具请求,工具调用结果,ai相应
                            String adapted = adaptWorkflowCodeChunk(rawChunk, dedupState);
                            if (adapted != null && !adapted.isEmpty()) {
                                sink.next(adapted);
                            }
                        } catch (Exception e) {
                            log.debug("workflow realtime chunk adapter failed: {}", e.getMessage());
                        }
                    };

                    // 5. 执行工作流，拿到最终上下文
                    WorkflowContext finalContext = workflow.executeWorkflow(
                            userMessage,
                            codeGenTypeEnum,
                            appId,
                            firstRound,
                            progress,
                            codeStreamChunkConsumer);

                    // 6. 空结果：提示后结束
                    if (finalContext == null) {
                        sink.next("[workflow] 未获取到有效结果，请重试。\n");
                        sink.complete();
                        return;
                    }
                    // 7. 有错误信息则推送用户可见失败文案
                    if (finalContext.getErrorMessage() != null && !finalContext.getErrorMessage().isBlank()) {
                        log.warn("workflow finished with error, appId={}, codeGenType={}, detail={}",
                                appId, codeGenTypeEnum, finalContext.getErrorMessage());
                        sink.next(ChatHistoryConstant.GENERATION_FAILED_USER_MESSAGE + "\n");
                    } else {
                        // 8. 非 Vue：把落盘后的源码以 fenced 形式回显，便于会话记忆与前端展示
                        String generatedDir = finalContext.getGeneratedCodeDir();
                        if (generatedDir != null && !generatedDir.isBlank()) {
                            emitGeneratedCodeIfPresent(sink, codeGenTypeEnum, generatedDir);
                        }
                        if (Boolean.TRUE.equals(finalContext.getMermaidError())) {
                            sink.next("[workflow_notice] mermaid_error\n");
                        }
                        sink.next("[workflow] 代码生成完成。\n");
                    }
                    if (finalContext.getGeneratedCodeDir() != null && !finalContext.getGeneratedCodeDir().isBlank()) {
                        log.debug("workflow 生成目录: {}", finalContext.getGeneratedCodeDir());
                    }
                    if (finalContext.getBuildResultDir() != null && !finalContext.getBuildResultDir().isBlank()) {
                        log.debug("workflow 构建目录: {}", finalContext.getBuildResultDir());
                    }
                    sink.complete();
                } catch (Exception e) {
                    log.error("workflow 执行失败, appId={}, codeGenType={}", appId, codeGenTypeEnum, e);
                    sink.error(e);
                }
            });
        });
    }


    /**
     * 保证工作流进度字符串以换行结尾，便于 SSE 按行切分
     *
     * @param msg 原始进度文本
     * @return 规范化后的文本
     */
    private String normalizeWorkflowLine(String msg) {
        // 1. 空串直接返回，避免下游出现 "null" 文本
        if (msg == null || msg.isBlank()) {
            return "";
        }
        // 2. 已带换行则原样；否则补一个 \n，保证 SSE 按行切分稳定
        return msg.endsWith("\n") ? msg : msg + "\n";
    }


    /**
     * 将工作流实时推送的单行 JSON chunk 转为前端可展示的纯文本（AI 片段 / 工具卡片）
     *
     * @param rawChunk   原始 JSON 字符串（也可能偶发非 JSON 透传）
     * @param dedupState 同一流内工具请求/执行的去重状态
     * @return 适配后的文本；重复事件或空内容可能返回空串
     */
    private String adaptWorkflowCodeChunk(String rawChunk, WorkflowChunkDedupState dedupState) {
        // 1. 空 chunk 直接忽略
        if (rawChunk == null || rawChunk.isBlank()) {
            return "";
        }

        StreamMessage streamMessage;
        // 2. 尝试反序列化为 StreamMessage；失败则把原文当纯文本回退
        try {
            streamMessage = JSONUtil.toBean(rawChunk, StreamMessage.class);
        } catch (Exception e) {
            return rawChunk;
        }

        // 3. type 无法识别时同样原样透传
        StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
        if (typeEnum == null) {
            return rawChunk;
        }

        // 4. 按消息类型分支：AI 文本 / 工具请求 / 工具执行
        return switch (typeEnum) {
            case AI_RESPONSE -> {
                AiResponseMessage aiMessage = JSONUtil.toBean(rawChunk, AiResponseMessage.class);
                yield aiMessage.getData() == null ? "" : aiMessage.getData();
            }
            case TOOL_REQUEST -> {
                ToolRequestMessage toolRequest = JSONUtil.toBean(rawChunk, ToolRequestMessage.class);
                if (isDuplicateToolRequest(toolRequest, dedupState)) {
                    yield "";
                }
                String toolName = toolRequest.getName();
                BaseTool tool = toolManager.getTool(toolName);
                if (tool != null) {
                    yield tool.generateToolRequestResponse();
                }
                String label = (toolName == null || toolName.isBlank()) ? "未知工具" : toolName;
                yield String.format("\n\n[选择工具] %s\n", label);
            }
            case TOOL_EXECUTED -> {
                ToolExecutedMessage executed = JSONUtil.toBean(rawChunk, ToolExecutedMessage.class);
                JSONObject arguments = safeParseArguments(executed.getArguments());
                if (isDuplicateToolExecuted(executed, arguments, dedupState)) {
                    yield "";
                }
                BaseTool tool = toolManager.getTool(executed.getName());
                if (tool != null) {
                    yield tool.generateToolExecutedResult(arguments);
                }
                yield fallbackToolExecutedFormatting(executed.getName(), arguments);
            }
        };
    }

    /**
     * 将工具 arguments 字符串安全解析为 JSONObject；非严格 JSON 时降级为 _rawArguments
     *
     * @param arguments JSON 字符串，可能为 null
     * @return 解析结果，永不为 null
     */
    private JSONObject safeParseArguments(String arguments) {
        // 1. 空参返回空对象，避免 NPE
        if (arguments == null || arguments.isBlank()) {
            return new JSONObject();
        }
        try {
            // 2. 标准 Hutool JSON 解析
            return JSONUtil.parseObj(arguments);
        } catch (Exception e) {
            // 3. 解析失败：保留原文字段，供工具层兜底展示
            JSONObject fallback = new JSONObject();
            fallback.set("_rawArguments", arguments);
            return fallback;
        }
    }

    /**
     * 判定并记录工具请求去重状态（仅按 toolCallId 幂等）。
     * 与 Vue 的 adaptVueTokenStream 链路保持一致：只要 toolCallId 不变，就视为同一工具请求事件。
     * toolCallId 缺失时不做去重（宁可多展示一次，也不要误吞合法工具调用）。
     *
     * @param toolRequest 当前工具请求消息
     * @param dedupState  当前流内幂等状态
     * @return true 表示命中重复并应忽略；false 表示首次出现可继续处理
     */
    private boolean isDuplicateToolRequest(ToolRequestMessage toolRequest, WorkflowChunkDedupState dedupState) {
        String toolCallId = toolRequest.getId();
        if (toolCallId == null || toolCallId.isBlank()) {
            return false;
        }

        // 1. Set.add 返回 false 表示已存在 → 本 chunk 为重复工具请求
        return !dedupState.getSeenToolRequestIds().add(toolCallId);
    }

    /**
     * 判定并记录工具执行结果去重状态（仅按 toolCallId 幂等）。
     * toolCallId 缺失时不做去重，避免误吞合法执行结果。
     *
     * @param executed   当前工具执行消息
     * @param arguments  工具执行参数（参与签名外仅用于兼容旧调用，当前逻辑以 id 为准）
     * @param dedupState 当前流内幂等状态
     * @return true 表示命中重复并应忽略；false 表示首次出现可继续处理
     */
    private boolean isDuplicateToolExecuted(ToolExecutedMessage executed, JSONObject arguments, WorkflowChunkDedupState dedupState) {
        String toolCallId = executed.getId();
        if (toolCallId == null || toolCallId.isBlank()) {
            return false;
        }
        // 1. Set.add 返回 false 表示已处理过同 id 的执行结果
        return !dedupState.getSeenToolExecutedIds().add(toolCallId);
    }

    /**
     * 找不到具体 BaseTool 时的工具执行结果兜底展示
     *
     * @param toolName  工具名
     * @param arguments 已解析参数
     * @return 一行可读文本，末尾带换行
     */
    private String fallbackToolExecutedFormatting(String toolName, JSONObject arguments) {
        // 1. 工具名空则显示「工具」
        String safeToolName = (toolName == null || toolName.isBlank()) ? "工具" : toolName;
        // 2. 路径优先取 relativeFilePath，其次 relativeDirPath
        String path = arguments.getStr("relativeFilePath");
        if (path == null || path.isBlank()) {
            path = arguments.getStr("relativeDirPath");
        }
        if (path == null || path.isBlank()) {
            path = "-";
        }
        // 3. 拼固定格式
        return String.format("[工具调用] %s %s\n", safeToolName, path);
    }


    /**
     * 工作流结束后，把已落盘的 HTML / 多文件源码以 fenced 代码块形式推给 SSE（Vue 不在此回显）
     *
     * @param sink             Flux 下游
     * @param codeGenTypeEnum  生成类型
     * @param generatedDir     工作流写入的生成目录
     */
    private void emitGeneratedCodeIfPresent(FluxSink<String> sink,
                                            CodeGenTypeEnum codeGenTypeEnum,
                                            String generatedDir) {
        try {
            // 1. HTML：只读 index.html 并推送一个围栏块
            if (codeGenTypeEnum == CodeGenTypeEnum.HTML) {
                Path indexHtml = Path.of(generatedDir, "index.html");
                if (Files.exists(indexHtml)) {
                    String html = Files.readString(indexHtml, StandardCharsets.UTF_8);
                    if (html != null && !html.isBlank()) {
                        sink.next(wrapMarkdownCodeBlock("index.html", html, "html"));
                    }
                }
                return;
            }

            // 2. MULTI_FILE：列出目录下所有普通文件，逐个读入并推送
            if (codeGenTypeEnum == CodeGenTypeEnum.MULTI_FILE) {
                Path dir = Path.of(generatedDir);
                if (Files.isDirectory(dir)) {
                    try (var stream = Files.list(dir)) {
                        stream.filter(Files::isRegularFile)
                                .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                                .forEach(p -> {
                                    try {
                                        String body = Files.readString(p, StandardCharsets.UTF_8);
                                        String name = p.getFileName().toString();
                                        String lang = markdownFenceLanguageForFileName(name);
                                        sink.next(wrapMarkdownCodeBlock(name, body, lang));
                                    } catch (Exception ignore) {
                                    }
                                });
                    }
                }
                return;
            }

            // 3. VUE：由其它链路处理，这里直接返回
            if (codeGenTypeEnum == CodeGenTypeEnum.VUE) {
                return;
            }
        } catch (Exception e) {
            log.warn("workflow 回显生成代码失败: type={}, dir={}", codeGenTypeEnum, generatedDir, e);
        }
    }


    /**
     * 遍历 Vue 生成目录，将可读文本文件以 fenced 块推给 sink（过滤 node_modules 等）
     *
     * @param sink         Flux 下游
     * @param generatedDir 项目根目录
     */
    private void emitVueGeneratedCode(reactor.core.publisher.FluxSink<String> sink, String generatedDir) {
        // 1. 根路径必须是目录
        Path root = Path.of(generatedDir);
        if (!Files.isDirectory(root)) {
            return;
        }

        // 2. walk 收集候选文件并排序
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> shouldEmitVueFile(root, path))
                    .sorted(Comparator.comparing(
                            path -> root.relativize(path).toString().replace('\\', '/').toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("workflow vue 目录遍历失败: {}", generatedDir, e);
            return;
        }

        // 3. 逐个读文件：超大小或非文本跳过，其余 wrap 后推送
        for (Path path : files) {
            try {
                long size = Files.size(path);
                if (size > VUE_ECHO_MAX_FILE_BYTES) {
                    continue;
                }
                if (!isLikelyTextFile(path)) {
                    continue;
                }
                String body = Files.readString(path, StandardCharsets.UTF_8);
                String relative = root.relativize(path).toString().replace('\\', '/');
                String language = markdownFenceLanguageForFileName(relative);
                sink.next(wrapMarkdownCodeBlock(relative, body, language));
            } catch (Exception ignore) {
            }
        }
    }


    /**
     * 判断相对路径是否落在应忽略的目录（如 node_modules）之下
     *
     * @param root 项目根
     * @param file 绝对路径文件
     * @return true 表示可以回显
     */
    private boolean shouldEmitVueFile(Path root, Path file) {
        Path relative = root.relativize(file);
        int count = relative.getNameCount();
        if (count == 0) {
            return false;
        }

        // 1. 检查路径上任意父段是否为忽略目录名
        for (int i = 0; i < count - 1; i++) {
            String dir = relative.getName(i).toString();
            if (VUE_ECHO_IGNORED_DIR_NAMES.contains(dir)) {
                return false;
            }
        }
        return true;
    }


    /**
     * 读取文件头若干字节，启发式判断是否像文本（排除含 NUL 的二进制）
     *
     * @param path 文件路径
     * @return true 更可能为文本
     */
    private boolean isLikelyTextFile(Path path) {
        byte[] sample = new byte[4096];
        int read;
        // 1. 读最多 4KB 样本
        try (InputStream in = Files.newInputStream(path)) {
            read = in.read(sample);
        } catch (Exception e) {
            return false;
        }

        // 2. 读不到或空文件：按文本放行
        if (read <= 0) {
            return true;
        }

        // 3. 统计控制字符比例；出现 NUL 直接判二进制
        int controlChars = 0;
        for (int i = 0; i < read; i++) {
            int b = sample[i] & 0xFF;
            if (b == 0) {
                return false;
            }
            if (b < 9 || (b > 13 && b < 32)) {
                controlChars++;
            }
        }

        return controlChars < Math.max(4, read / 20);
    }


    /**
     * 根据文件名后缀选择 markdown 围栏语言（与 Various_File_Prompt / 前端解析约定一致）
     *
     * @param fileName 文件名或相对路径
     * @return fence 语言标识，如 html、typescript
     */
    static String markdownFenceLanguageForFileName(String fileName) {
        // 1. null 当纯文本
        if (fileName == null) {
            return "plaintext";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "html";
        }
        if (lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".less")) {
            return "css";
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs") || lower.endsWith(".jsx")) {
            return "javascript";
        }
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            return "typescript";
        }
        if (lower.endsWith(".json")) {
            return "json";
        }
        if (lower.endsWith(".vue")) {
            return "vue";
        }
        if (lower.endsWith(".md")) {
            return "markdown";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return "yaml";
        }
        if (lower.endsWith(".xml") || lower.endsWith(".svg")) {
            return "xml";
        }
        return "plaintext";
    }


    /**
     * 生成与前端 parseMarkdownWithCode 兼容的 fenced 代码块（### 标题 + 围栏各占行）
     *
     * @param displayName   展示用文件名/标题
     * @param content       代码正文
     * @param fenceLanguage markdown 语言标签
     * @return 完整 markdown 片段
     */
    static String wrapMarkdownCodeBlock(String displayName, String content, String fenceLanguage) {
        // 1. 正文 null 当空串
        String body = content == null ? "" : content;
        // 2. 若正文末尾无换行，在闭合围栏前补一个，避免 ``` 粘在最后一行字符上
        String tailNewline = body.endsWith("\n") ? "" : "\n";
        // 3. 拼 ### 标题 + 起始围栏 + 正文 + 结束围栏
        return "### " + displayName + "\n```" + fenceLanguage + "\n" + body + tailNewline + "```\n\n";
    }

}
