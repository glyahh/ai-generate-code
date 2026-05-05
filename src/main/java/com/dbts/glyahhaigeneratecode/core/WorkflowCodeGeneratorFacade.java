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
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.ai.tool.ToolManager;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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

    public Flux<String> generateAndSaveCodeStream (String userMessage,
                                                  CodeGenTypeEnum codeGenTypeEnum,
                                                  Long appId,
                                                  boolean firstRound) {
        return Flux.create(sink -> {
            // 使用异步线程执行 workflow，避免同线程阻塞导致 SSE 早期消息滞后
            CompletableFuture.runAsync(() -> {
                try {
                    CodeGenWorkflow workflow = new CodeGenWorkflow();
                    // 工作流第...步完成
                    Consumer<String> progress = msg -> {
                        try {
                            // 将每一行的message的换行符保证不丢失
                            String normalized = normalizeWorkflowLine(msg);
                            sink.next(normalized);
                        } catch (Exception ignored) {
                            // downstream closed
                        }
                    };
                    // 透传工作流中的工具调用信息
                    // 声明有了chunk该怎么拼接 (Consumer<String>本质就是声明一个处理字符串的方法)
                    Consumer<String> codeStreamChunkConsumer = rawChunk -> {
                        try {
                            // 解析ai返回的请求/意图
                            // 工具请求,工具调用结果,ai相应
                            String adapted = adaptWorkflowCodeChunk(rawChunk);
                            if (adapted != null && !adapted.isEmpty()) {
                                sink.next(adapted);
                            }
                        } catch (Exception e) {
                            log.debug("workflow realtime chunk adapter failed: {}", e.getMessage());
                        }
                    };

                    WorkflowContext finalContext = workflow.executeWorkflow(
                            userMessage,
                            codeGenTypeEnum,
                            appId,
                            firstRound,
                            // 两种处理不同字符串的方法, 在这里生命好了传给工作流用
                            progress,
                            codeStreamChunkConsumer);

                    if (finalContext == null) {
                        sink.next("[workflow] 未获取到有效结果，请重试。\n");
                        sink.complete();
                        return;
                    }
                    if (finalContext.getErrorMessage() != null && !finalContext.getErrorMessage().isBlank()) {
                        sink.next("[workflow] 生成失败: " + finalContext.getErrorMessage() + "\n");
                    } else {
                        // 对非 Vue 的 HTML / multi_file 场景，workflow 内部会把文件落到 generatedCodeDir。
                        // 为了贴近原 AI 流式体验（以及让 StreamHandlerExecutor 能把“代码内容”写入会话记忆），这里读取落盘文件并回显到 SSE。
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

    private String normalizeWorkflowLine(String msg) {
        if (msg == null || msg.isBlank()) {
            return "";
        }
        return msg.endsWith("\n") ? msg : msg + "\n";
    }

    private String adaptWorkflowCodeChunk(String rawChunk) {
        if (rawChunk == null || rawChunk.isBlank()) {
            return "";
        }

        StreamMessage streamMessage;
        try {
            streamMessage = JSONUtil.toBean(rawChunk, StreamMessage.class);
        } catch (Exception e) {
            // Non-JSON chunk fallback.
            return rawChunk;
        }

        StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
        if (typeEnum == null) {
            return rawChunk;
        }

        return switch (typeEnum) {
            case AI_RESPONSE -> {
                // 将rawchunk转换为AiResponseMessage
                AiResponseMessage aiMessage = JSONUtil.toBean(rawChunk, AiResponseMessage.class);
                yield aiMessage.getData() == null ? "" : aiMessage.getData();
            }
            case TOOL_REQUEST -> {
                //
                ToolRequestMessage toolRequest = JSONUtil.toBean(rawChunk, ToolRequestMessage.class);
                String toolName = toolRequest.getName();
                BaseTool tool = toolManager.getTool(toolName);
                if (tool != null) {
                    yield tool.generateToolRequestResponse();
                }
                // 实在不行用工具名不用专门给人看的展示名了
                String label = (toolName == null || toolName.isBlank()) ? "未知工具" : toolName;
                yield String.format("\n\n[选择工具] %s\n", label);
            }
            case TOOL_EXECUTED -> {
                ToolExecutedMessage executed = JSONUtil.toBean(rawChunk, ToolExecutedMessage.class);
                JSONObject arguments = safeParseArguments(executed.getArguments());
                BaseTool tool = toolManager.getTool(executed.getName());
                if (tool != null) {
                    // 直接获取工具调用的结果string
                    yield tool.generateToolExecutedResult(arguments);
                }
                // 强行构造一种工具调用的结果格式返回
                yield fallbackToolExecutedFormatting(executed.getName(), arguments);
            }
        };
    }

    private JSONObject safeParseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(arguments);
        } catch (Exception e) {
            JSONObject fallback = new JSONObject();
            fallback.set("_rawArguments", arguments);
            return fallback;
        }
    }

    private String fallbackToolExecutedFormatting(String toolName, JSONObject arguments) {
        String safeToolName = (toolName == null || toolName.isBlank()) ? "工具" : toolName;
        String path = arguments.getStr("relativeFilePath");
        if (path == null || path.isBlank()) {
            path = arguments.getStr("relativeDirPath");
        }
        if (path == null || path.isBlank()) {
            path = "-";
        }
        return String.format("[工具调用] %s %s\n", safeToolName, path);
    }

    private void emitGeneratedCodeIfPresent(reactor.core.publisher.FluxSink<String> sink,
                                            CodeGenTypeEnum codeGenTypeEnum,
                                            String generatedDir) {
        try {
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
                                        // single file read failure should not break final response
                                    }
                                });
                    }
                }
                return;
            }

            if (codeGenTypeEnum == CodeGenTypeEnum.VUE) {
                return;
            }
        } catch (Exception e) {
            log.warn("workflow 回显生成代码失败: type={}, dir={}", codeGenTypeEnum, generatedDir, e);
        }
    }

    private void emitVueGeneratedCode(reactor.core.publisher.FluxSink<String> sink, String generatedDir) {
        Path root = Path.of(generatedDir);
        if (!Files.isDirectory(root)) {
            return;
        }

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
                // skip unreadable/non-utf8 files
            }
        }
    }

    private boolean shouldEmitVueFile(Path root, Path file) {
        Path relative = root.relativize(file);
        int count = relative.getNameCount();
        if (count == 0) {
            return false;
        }

        for (int i = 0; i < count - 1; i++) {
            String dir = relative.getName(i).toString();
            if (VUE_ECHO_IGNORED_DIR_NAMES.contains(dir)) {
                return false;
            }
        }
        return true;
    }

    private boolean isLikelyTextFile(Path path) {
        byte[] sample = new byte[4096];
        int read;
        try (InputStream in = Files.newInputStream(path)) {
            read = in.read(sample);
        } catch (Exception e) {
            return false;
        }

        if (read <= 0) {
            return true;
        }

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
     * 与 aiservice / Various_File_Prompt 约定一致：html、css、javascript（js 用 javascript 围栏）。
     */
    static String markdownFenceLanguageForFileName(String fileName) {
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
     * 生成与前端 parseMarkdownWithCode 兼容的 fenced 块：开始/结束围栏独占一行。
     */
    static String wrapMarkdownCodeBlock(String displayName, String content, String fenceLanguage) {
        String body = content == null ? "" : content;
        String tailNewline = body.endsWith("\n") ? "" : "\n";
        return "### " + displayName + "\n```" + fenceLanguage + "\n" + body + tailNewline + "```\n\n";
    }
}
