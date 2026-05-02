package com.dbts.glyahhaigeneratecode.core;

import com.dbts.glyahhaigeneratecode.LangGraph4j.CodeGenWorkflow;
import com.dbts.glyahhaigeneratecode.LangGraph4j.state.WorkflowContext;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Workflow 代码生成门面。
 * 仅封装工作流执行与结果转换，避免控制层/服务层直接依赖工作流细节。
 */
@Service
@Slf4j
public class WorkflowCodeGeneratorFacade {

    public Flux<String> generateAndSaveCodeStream(String userMessage,
                                                  CodeGenTypeEnum codeGenTypeEnum,
                                                  Long appId,
                                                  boolean firstRound) {
        return Flux.create(sink -> {
            // 使用异步线程执行 workflow，避免同线程阻塞导致 SSE 早期消息滞后
            CompletableFuture.runAsync(() -> {
                try {
                    CodeGenWorkflow workflow = new CodeGenWorkflow();
                    Consumer<String> progress = msg -> {
                        try {
                            String normalized = normalizeWorkflowLine(msg);
                            sink.next(normalized);
                        } catch (Exception ignored) {
                            // 下游已取消等场景：不再向外推
                        }
                    };
                    WorkflowContext finalContext = workflow.executeWorkflow(
                            userMessage, codeGenTypeEnum, appId, firstRound, progress);
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
                        if (generatedDir != null && !generatedDir.isBlank()
                                && (codeGenTypeEnum == CodeGenTypeEnum.HTML || codeGenTypeEnum == CodeGenTypeEnum.MULTI_FILE)) {
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
                                .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                                .forEach(p -> {
                                    try {
                                        String body = Files.readString(p, StandardCharsets.UTF_8);
                                        String name = p.getFileName().toString();
                                        String lang = markdownFenceLanguageForFileName(name);
                                        sink.next(wrapMarkdownCodeBlock(name, body, lang));
                                    } catch (Exception ignore) {
                                        // 单文件读取失败不影响整体完成事件
                                    }
                                });
                    }
                }
            }
        } catch (Exception e) {
            log.warn("workflow 回显生成代码失败: type={}, dir={}", codeGenTypeEnum, generatedDir, e);
        }
    }

    /**
     * 与 aiservice / {@code Various_File_Prompt} 约定一致：html、css、javascript（.js 用 javascript 围栏）。
     */
    static String markdownFenceLanguageForFileName(String fileName) {
        if (fileName == null) {
            return "plaintext";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "html";
        }
        if (lower.endsWith(".css")) {
            return "css";
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) {
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
        return "plaintext";
    }

    /**
     * 生成与前端 {@code parseMarkdownWithCode} 兼容的 fenced 块：开始/结束围栏独占一行。
     */
    static String wrapMarkdownCodeBlock(String displayName, String content, String fenceLanguage) {
        String body = content == null ? "" : content;
        String tailNewline = body.endsWith("\n") ? "" : "\n";
        return "### " + displayName + "\n```" + fenceLanguage + "\n" + body + tailNewline + "```\n\n";
    }
}
