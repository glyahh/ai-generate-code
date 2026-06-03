package com.dbts.glyahhaigeneratecode.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorService;
import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorServiceFactory;
import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.message.AiResponseMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolExecutedMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolRequestMessage;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.ai.tool.ToolManager;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.core.Builder.vueProjectBuilder;
import com.dbts.glyahhaigeneratecode.core.context.HtmlMultiFileEditContextBuilder;
import com.dbts.glyahhaigeneratecode.core.parser.CodeParserExecutor;
import com.dbts.glyahhaigeneratecode.core.saver.CodeFileSaverExecutor;
import com.dbts.glyahhaigeneratecode.core.util.LegacyHtmlStreamIntegrity;
import com.dbts.glyahhaigeneratecode.core.util.LegacyHtmlToolStreamSupport;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Constants;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.SignalType;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 代码生成门面，统一串起生成、流式适配、解析和保存。
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    static final int WRITE_FILE_EXTRACT_WARN_THRESHOLD = 16 * 1024;

    @Resource
    private aiCodeGeneratorService aiCodeGeneratorService;

    @Resource
    private aiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Resource
    private HtmlMultiFileEditContextBuilder htmlMultiFileEditContextBuilder;

    @Resource
    private vueProjectBuilder vueProjectBuilder;

    @Resource
    private ToolManager toolManager;

    private static final CodeFileSaverExecutor codeFileSaverExecutor = new CodeFileSaverExecutor();
    private static final CodeParserExecutor codeParserExecutor = new CodeParserExecutor();

    /**
     * 同步生成 HTML 或多文件代码并保存到磁盘（非流式入口）
     *
     * @param userMessage       用户提示词
     * @param codeGenTypeEnum   仅支持 HTML、MULTI_FILE
     * @param appId             应用主键
     * @return 保存目录对应的 File
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        // 1. 生成类型必填
        if (codeGenTypeEnum == null) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }

        return switch (codeGenTypeEnum) {
            case HTML -> {
                // 2. 判断是否「可编辑存量」场景：关键词命中且磁盘已有文件 → 走工具化编辑链路的 service 配置
                boolean editIntent = htmlMultiFileEditContextBuilder.isEditIntentMessage(userMessage)
                        && htmlMultiFileEditContextBuilder.hasExistingEditableFiles(CodeGenTypeEnum.HTML, appId);
                // 3. 非编辑意图则首轮可无工具自举生成
                boolean htmlMultiToollessBootstrap = !editIntent;
                aiCodeGeneratorService svc = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(
                        appId, CodeGenTypeEnum.HTML, htmlMultiToollessBootstrap, true);
                // 4. 调模型生成 HtmlCodeResult
                HtmlCodeResult result = svc.generateCodeHTML(appId, userMessage);
                // 5. 落盘
                yield codeFileSaverExecutor.execute(codeGenTypeEnum, result, appId);
            }
            case MULTI_FILE -> {
                boolean editIntent = htmlMultiFileEditContextBuilder.isEditIntentMessage(userMessage)
                        && htmlMultiFileEditContextBuilder.hasExistingEditableFiles(CodeGenTypeEnum.MULTI_FILE, appId);
                boolean htmlMultiToollessBootstrap = !editIntent;
                aiCodeGeneratorService svc = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(
                        appId, CodeGenTypeEnum.MULTI_FILE, htmlMultiToollessBootstrap, true);
                MultiFileCodeResult result = svc.generateCodeMultiFile(appId, userMessage);
                yield codeFileSaverExecutor.execute(codeGenTypeEnum, result, appId);
            }
            default -> throw new MyException(ErrorCode.SYSTEM_ERROR, "不支持的生成类型: " + codeGenTypeEnum.getValue());
        };
    }

    /**
     * 流式生成入口（默认非首轮、开启缓存命中时的记忆压缩）
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum HTML / MULTI_FILE / VUE
     * @param appId           应用主键
     * @return SSE 文本流
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        return generateAndSaveCodeStream(userMessage, codeGenTypeEnum, appId, false, true);
    }

    /**
     * 流式生成入口，可指定是否首轮
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用主键
     * @param firstRound      是否首轮（Vue 工具白名单等）
     * @return SSE 文本流
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId, boolean firstRound) {
        return generateAndSaveCodeStream(userMessage, codeGenTypeEnum, appId, firstRound, true);
    }

    /**
     * 流式生成入口（完整参数）：可控制缓存命中时是否压缩记忆
     *
     * @param userMessage               用户提示词
     * @param codeGenTypeEnum           生成类型
     * @param appId                     应用主键
     * @param firstRound                是否首轮
     * @param compactMemoryOnCacheHit   命中缓存时是否压缩 Redis 会话
     * 从「内存里的 AI 服务缓存」里拿到同一个 app 的实例时，要不要顺便把 Redis 里那份对话记忆做一轮「变短」处理
     *
     * @return SSE 文本流
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage,
                                                  CodeGenTypeEnum codeGenTypeEnum,
                                                  Long appId,
                                                  boolean firstRound,
                                                  boolean compactMemoryOnCacheHit) {
        // 1. 类型校验
        if (codeGenTypeEnum == null) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }

        // 2. 按类型分发到各自私有方法
        return switch (codeGenTypeEnum) {
            case HTML -> generateAndSaveHtmlCodeStream(userMessage, appId, firstRound, compactMemoryOnCacheHit);
            case MULTI_FILE -> generateAndSaveMultiFileCodeStream(userMessage, appId, firstRound, compactMemoryOnCacheHit);
            case VUE -> generateAndSaveVueCodeStream(userMessage, appId, firstRound, compactMemoryOnCacheHit);
            default -> throw new MyException(ErrorCode.SYSTEM_ERROR, "不支持的生成类型: " + codeGenTypeEnum.getValue());
        };
    }

    /**
     * Legacy：对已是纯文本的 Flux 做聚合，结束时解析并保存（供旧链路复用）
     *
     * @param codeGenTypeEnum 生成类型
     * @param result          上游文本流
     * @param appId           应用主键
     * @return 透传的 Flux（副作用在 doOnComplete）
     */
    private Flux<String> processCodeStream(CodeGenTypeEnum codeGenTypeEnum, Flux<String> result, Long appId) {
        StringBuilder codeBuilder = new StringBuilder();
        return result
                .doOnNext(codeBuilder::append)
                .doOnComplete(() -> {
                    try {
                        // 1. 聚合全文并打诊断日志（含截断尾部样例）
                        String full = codeBuilder.toString();
                        int len = full.length();
                        // 为了打印日志而量身定制
                        String tail = len > 200 ? full.substring(len - 200) : full;
                        log.info(
                                "legacy 流式聚合完成 appId={} codeGenType={} charLen={} possibleTruncatedTail={}\ntailSample=\n{}",
                                appId,
                                codeGenTypeEnum,
                                len,
                                LegacyHtmlStreamIntegrity.looksLikeIncompleteTrailingTag(full),
                                tail
                        );

                        // 2. 解析为结果对象
                        Object executeResult = codeParserExecutor.execute(codeGenTypeEnum, full);

                        // 3. 保存到 code_output
                        File file = codeFileSaverExecutor.execute(codeGenTypeEnum, executeResult, appId);
                        log.info("保存目录: {}", file.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("生成代码失败: {}", e.getMessage(), e);
                    }
                })
                .doFinally(signal -> {
                    // 1. 取消时记录已缓冲长度，便于排查客户端断开
                    if (signal == SignalType.CANCEL) {
                        log.warn(
                                "legacy 流式被取消 appId={} codeGenType={} partialCharLen={}",
                                appId,
                                codeGenTypeEnum,
                                codeBuilder.length()
                        );
                    }
                });
    }

    /**
     * HTML/MULTI_FILE：将 TokenStream 转为纯文本 SSE，可选在结束时聚合解析落盘。
     *
     * @param codeGenTypeEnum   代码生成类型
     * @param tokenStream       LangChain4j TokenStream
     * @param appId             应用主键
     * @param persistOnComplete true=流结束后解析全文并落盘；false=仅工具编辑流式，不在此落盘
     * true：流结束会落盘
     * false：这条管线只把 SSE 流透传出去，不在 complete 里做整段解析落盘
     *
     * @return 适配后的 Flux
     */
    private Flux<String> wireHtmlMultiFileTokenStream(
            CodeGenTypeEnum codeGenTypeEnum, TokenStream tokenStream, Long appId, boolean persistOnComplete) {
        // 根据工具ID每个工具对应的一个stringappend
        Map<String, StringBuilder> toolArgsById = new HashMap<>();
        // 工具执行
        Set<String> syntheticExecutedIds = new HashSet<>();
        // 工具警告
        Set<String> warnedLargeIncompleteIds = new HashSet<>();
        // 工具请求
        Set<String> seenToolRequestIds = new HashSet<>();
        // 表示 LangChain4j 是否已经回调过onToolExecuted那个工具真的执行完的那条路径
        AtomicBoolean nativeToolExecutedMode = new AtomicBoolean(false);

        return Flux.<String>create(sink -> {
                    StringBuilder codeBuilder = persistOnComplete ? new StringBuilder() : null;
                    tokenStream.onPartialResponse(partial -> {
                                if (partial == null || partial.isEmpty()) {
                                    return;
                                }
                                if (codeBuilder != null) {
                                    codeBuilder.append(partial);
                                }
                                try {
                                    sink.next(partial);
                                } catch (Exception ignore) {
                                    ThrowUtils.throwIf(true, ErrorCode.NOT_FOUND_ERROR, "未再Html/MultiFile中将sink->partial");
                                }
                            })
                            // index -> 第几个调用的工具
                            .onPartialToolExecutionRequest((Integer index, ToolExecutionRequest toolExecutionRequest) -> {
                                // 按 toolCallId 第一次出现时往 sink 推 [选择工具] %s
                                // 一旦toolArgsById能解析出路径和内容，就 合成一段[执行工具] %s
                                // 这样就算没有onCompleteToolExecutionRequest也能正常返回Flux<string>
                                LegacyHtmlToolStreamSupport.emitLegacyHtmlToolStreamChunk(
                                        toolManager,
                                        sink,
                                        appId,
                                        codeGenTypeEnum,
                                        toolExecutionRequest,
                                        toolArgsById,
                                        syntheticExecutedIds,
                                        warnedLargeIncompleteIds,
                                        seenToolRequestIds,
                                        nativeToolExecutedMode,
                                        true);
                            })
                            .onCompleteToolExecutionRequest((Integer index, ToolExecutionRequest completeToolExecutionRequest) -> {
                                //
                                LegacyHtmlToolStreamSupport.emitLegacyHtmlToolStreamChunk(
                                        toolManager,
                                        sink,
                                        appId,
                                        codeGenTypeEnum,
                                        completeToolExecutionRequest,
                                        toolArgsById,
                                        syntheticExecutedIds,
                                        warnedLargeIncompleteIds,
                                        seenToolRequestIds,
                                        nativeToolExecutedMode,
                                        false);
                            })
                            .onToolExecuted((ToolExecution toolExecution) -> {
                                try {
                                    String toolCallId = toolExecution.request().id();
                                    nativeToolExecutedMode.compareAndSet(false, true);
                                    if (toolCallId != null && syntheticExecutedIds.contains(toolCallId)) {
                                        return;
                                    }
                                } catch (Exception ignore) {
                                    // ignore
                                }
                                try {
                                    String name = toolExecution.request().name();
                                    JSONObject args = LegacyHtmlToolStreamSupport.safeParseToolArgumentsForStream(
                                            toolExecution.request().arguments());
                                    BaseTool t = toolManager.getTool(name);
                                    String out = t != null
                                            // 获取path
                                            ? t.generateToolExecutedResult(args)
                                            // 找不到工具的占位
                                            : LegacyHtmlToolStreamSupport.fallbackToolExecutedPlain(name, args);
                                    try {
                                        sink.next(out);
                                    } catch (Exception ignore) {
                                        // downstream closed
                                    }
                                } catch (Exception ignore) {
                                    // ignore
                                }
                            })
                            .onCompleteResponse((ChatResponse response) -> {
                                try {
                                    if (persistOnComplete && codeBuilder != null) {
                                        persistParsedResult(codeGenTypeEnum, codeBuilder.toString(), appId);
                                    }
                                    sink.complete();
                                } catch (Exception e) {
                                    sink.error(e);
                                }
                            })
                            .onError(sink::error)
                            .start();
                }, FluxSink.OverflowStrategy.BUFFER)
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        log.warn(
                                "legacy token stream 被取消 appId={} codeGenType={} persistOnComplete={}",
                                appId,
                                codeGenTypeEnum,
                                persistOnComplete);
                    }
                });
    }

    /**
     * 将 TokenStream 适配为 Flux，并在 complete 回调中执行解析落盘。
     *
     * @param codeGenTypeEnum 代码生成类型
     * @param tokenStream     langchain4j TokenStream
     * @param appId           应用 id
     * @return 适配后的 Flux 流
     */
    private Flux<String> processCodeTokenStream(CodeGenTypeEnum codeGenTypeEnum, TokenStream tokenStream, Long appId) {
        // 1. 委托统一管线，并在流结束时解析落盘
        return wireHtmlMultiFileTokenStream(codeGenTypeEnum, tokenStream, appId, true);
    }

    /**
     * 将 TokenStream 仅做流式透传（不做聚合解析落盘）。
     *
     * @param codeGenTypeEnum 代码生成类型
     * @param tokenStream     langchain4j TokenStream
     * @param appId           应用 id
     * @return 适配后的 Flux 流
     */
    private Flux<String> adaptCodeTokenStream(CodeGenTypeEnum codeGenTypeEnum, TokenStream tokenStream, Long appId) {
        // 1. 与 processCodeTokenStream 相同管线，但不在 complete 时落盘
        return wireHtmlMultiFileTokenStream(codeGenTypeEnum, tokenStream, appId, false);
    }

    /**
     * 当模型通过工具写入非 index.html 的页面时，从输出目录回读最长/ index 的 html 作为兜底内容
     *
     * @param appId 应用主键
     * @return 读到的 HTML 字符串；无法恢复则 null
     */
    private String tryRecoverHtmlFromOutputDir(Long appId) {
        // 1. 定位 html_{appId} 目录
        Path dir = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, CodeGenTypeEnum.HTML.getValue() + "_" + appId);
        File d = dir.toFile();
        if (!d.isDirectory()) {
            return null;
        }
        // 2. 列出所有 .html
        File[] list = d.listFiles((__, name) -> name.toLowerCase().endsWith(".html"));
        if (list == null || list.length == 0) {
            return null;
        }
        // 3. 优先读 index.html
        File index = new File(d, "index.html");
        if (index.isFile()) {
            String idx = FileUtil.readUtf8String(index);
            if (StrUtil.isNotBlank(idx)) {
                return idx.trim();
            }
        }
        // 4. 否则选内容最长的 html 文件
        File best = null;
        int bestLen = 0;
        for (File f : list) {
            if (!f.isFile()) {
                continue;
            }
            String c = FileUtil.readUtf8String(f);
            if (StrUtil.isBlank(c)) {
                continue;
            }
            if (c.length() > bestLen) {
                bestLen = c.length();
                best = f;
            }
        }
        return best == null ? null : FileUtil.readUtf8String(best).trim();
    }

    /**
     * 将聚合后的模型输出解析为结果对象并写入磁盘；HTML 空内容时尝试从目录恢复
     *
     * @param codeGenTypeEnum 生成类型
     * @param full            聚合全文
     * @param appId           应用主键
     */
    private void persistParsedResult(CodeGenTypeEnum codeGenTypeEnum, String full, Long appId) {
        // 1. 打日志（长度、尾部、是否疑似截断）
        int len = full == null ? 0 : full.length();
        String safeFull = full == null ? "" : full;
        String tail = len > 200 ? safeFull.substring(len - 200) : safeFull;
        log.info(
                "legacy 流式聚合完成 appId={} codeGenType={} charLen={} possibleTruncatedTail={}\ntailSample=\n{}",
                appId,
                codeGenTypeEnum,
                len,
                LegacyHtmlStreamIntegrity.looksLikeIncompleteTrailingTag(safeFull),
                tail
        );
        try {
            // 2. 解析
            Object executeResult = codeParserExecutor.execute(codeGenTypeEnum, safeFull);
            // 3. HTML 且解析结果正文为空：尝试从磁盘恢复
            if (codeGenTypeEnum == CodeGenTypeEnum.HTML && executeResult instanceof HtmlCodeResult hcr) {
                if (StrUtil.isBlank(hcr.getHtmlCode())) {
                    String recovered = tryRecoverHtmlFromOutputDir(appId);
                    if (StrUtil.isNotBlank(recovered)) {
                        hcr.setHtmlCode(recovered);
                    }
                }
            }
            // 4. 保存
            File file = codeFileSaverExecutor.execute(codeGenTypeEnum, executeResult, appId);
            log.info("保存目录: {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.error("生成代码失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 多文件流式生成：按编辑意图选择是否仅透传 TokenStream，否则流结束落盘
     *
     * @param userMessage               用户提示
     * @param appId                     应用主键
     * @param firstRound                是否首轮
     * @param compactMemoryOnCacheHit   缓存命中时是否压缩记忆
     * @return Flux
     */
    private Flux<String> generateAndSaveMultiFileCodeStream(String userMessage, Long appId, boolean firstRound, boolean compactMemoryOnCacheHit) {
        // 1. 编辑意图 + 已有文件 → 不在 complete 时整段解析落盘
        boolean editIntent = htmlMultiFileEditContextBuilder.isEditIntentMessage(userMessage)
                && htmlMultiFileEditContextBuilder.hasExistingEditableFiles(CodeGenTypeEnum.MULTI_FILE, appId);
        // 2. 首轮且无编辑意图：允许无工具自举
        boolean htmlMultiToollessBootstrap = firstRound && !editIntent;
        aiCodeGeneratorService aiCodeGeneratorService =
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(
                        appId, CodeGenTypeEnum.MULTI_FILE, htmlMultiToollessBootstrap, compactMemoryOnCacheHit);
        // 3. 可能拼接片段修改上下文
        String finalPrompt = htmlMultiFileEditContextBuilder.buildPromptIfNeed(userMessage, CodeGenTypeEnum.MULTI_FILE, appId);
        TokenStream tokenStream = aiCodeGeneratorService.generateCodeMultiFileTokenStream(appId, finalPrompt);
        // 4. 编辑走仅透传；否则流结束 persist
        if (editIntent) {
            return adaptCodeTokenStream(CodeGenTypeEnum.MULTI_FILE, tokenStream, appId);
        }
        return processCodeTokenStream(CodeGenTypeEnum.MULTI_FILE, tokenStream, appId);
    }

    /**
     * 单文件 HTML 流式生成：逻辑同 {@link #generateAndSaveMultiFileCodeStream(String, Long, boolean, boolean)}
     *
     * @param userMessage               用户提示
     * @param appId                     应用主键
     * @param firstRound                是否首轮
     * @param compactMemoryOnCacheHit   缓存命中时是否压缩记忆
     * @return Flux
     */
    private Flux<String> generateAndSaveHtmlCodeStream(String userMessage, Long appId, boolean firstRound, boolean compactMemoryOnCacheHit) {
        boolean editIntent = htmlMultiFileEditContextBuilder.isEditIntentMessage(userMessage)
                && htmlMultiFileEditContextBuilder.hasExistingEditableFiles(CodeGenTypeEnum.HTML, appId);
        boolean htmlMultiToollessBootstrap = firstRound && !editIntent;
        aiCodeGeneratorService aiCodeGeneratorService =
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(
                        appId, CodeGenTypeEnum.HTML, htmlMultiToollessBootstrap, compactMemoryOnCacheHit);
        String finalPrompt = htmlMultiFileEditContextBuilder.buildPromptIfNeed(userMessage, CodeGenTypeEnum.HTML, appId);
        TokenStream tokenStream = aiCodeGeneratorService.generateCodeHTMLTokenStream(appId, finalPrompt);
        if (editIntent) {
            return adaptCodeTokenStream(CodeGenTypeEnum.HTML, tokenStream, appId);
        }
        return processCodeTokenStream(CodeGenTypeEnum.HTML, tokenStream, appId);
    }

    /**
     * Vue 流式生成：TokenStream 适配为 JSON 行 + 流结束后触发本地 npm build
     *
     * @param userMessage               用户提示
     * @param appId                     应用主键
     * @param firstRound                是否首轮
     * @param compactMemoryOnCacheHit   缓存命中时是否压缩记忆
     * @return Flux
     */
    private Flux<String> generateAndSaveVueCodeStream(String userMessage, Long appId, boolean firstRound, boolean compactMemoryOnCacheHit) {
        // 1. 取 Vue 专用 service（首轮控制工具白名单）
        aiCodeGeneratorService aiCodeGeneratorService =
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, CodeGenTypeEnum.VUE, firstRound, compactMemoryOnCacheHit);
        // 2. 开流
        TokenStream tokenStream = aiCodeGeneratorService.generateCodeVueFileStream(appId, userMessage);
        // 3. 转为前端 JSON 行协议
        return adaptVueTokenStream(tokenStream, appId);
    }

    /**
     * 将 LangChain4j Vue TokenStream 转为 JSON 行 Flux，并处理 writeFile 参数流式拼接与构建
     *
     * @param tokenStream LangChain4j 流
     * @param appId       应用主键（决定 vue_project_{appId} 目录）
     * @return JSON 行流
     */
    Flux<String> adaptVueTokenStream(TokenStream tokenStream, Long appId) {
        // 按 toolCallId 累积流式 arguments，直到能拼出一个“可提取关键字段”的完整片段
        Map<String, StringBuilder> toolArgsById = new HashMap<>();
        // 记录已经发过 synthetic tool_executed 的 toolCallId，避免重复发卡片
        Set<String> syntheticExecutedIds = new HashSet<>();
        // 只在超大参数且长期提取失败时告警一次，避免日志刷屏
        Set<String> warnedLargeIncompleteIds = new HashSet<>();
        // 一旦收到模型原生 onToolExecuted，就切回原生模式，不再继续合成 synthetic 消息
        AtomicBoolean nativeToolExecutedMode = new AtomicBoolean(false);
        // 工具请求去重：每个 toolCallId 仅首次 emit ToolRequestMessage
        Set<String> seenToolRequestIds = new HashSet<>();

        return Flux.create(sink -> tokenStream.onPartialResponse((String partialResponse) -> {
                    AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                    sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                })
                .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                    String toolCallId = toolExecutionRequest.id();
                    // 每个 toolCallId 仅首次 emit ToolRequestMessage，避免重复 JSON 行
                    if (toolCallId != null && seenToolRequestIds.add(toolCallId)) {
                        ToolRequestMessage toolRequestMessage = new ToolRequestMessage(toolExecutionRequest);
                        sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                    }

                    try {
                        String toolName = toolExecutionRequest.name();
                        String argsPart = toolExecutionRequest.arguments();
                        if (!nativeToolExecutedMode.get()
                                && toolCallId != null
                                && toolName != null
                                && argsPart != null
                                && !syntheticExecutedIds.contains(toolCallId)) {
                            StringBuilder buf = toolArgsById.computeIfAbsent(toolCallId, k -> new StringBuilder());
                            buf.append(argsPart);

                            if ("writeFile".equals(toolName)) {
                                // 这里是“提前合成 tool_executed 卡片”的关键：
                                // 只要从当前 buffer 里提取到 relativeFilePath + content，就立即回放给前端
                                String syntheticMessage = buildSyntheticWriteFileToolExecutedMessage(toolCallId, buf.toString());
                                if (syntheticMessage != null) {
                                    syntheticExecutedIds.add(toolCallId);
                                    sink.next(syntheticMessage);
                                } else if (buf.length() >= WRITE_FILE_EXTRACT_WARN_THRESHOLD
                                        && warnedLargeIncompleteIds.add(toolCallId)) {
                                    log.warn("writeFile 参数流超过 {} 字节仍未提取出 relativeFilePath/content，继续等待后续片段。toolCallId={}",
                                            WRITE_FILE_EXTRACT_WARN_THRESHOLD, toolCallId);
                                }
                            }
                        }
                    } catch (Exception ignore) {
                        // ignore
                    }
                })
                // 
                .onCompleteToolExecutionRequest((index, completeToolExecutionRequest) -> {
                    // 兼容部分 API 仅在 complete 时给出完整 tool request；
                    // 若该 toolCallId 尚未通过 partial 发出，则在此补发 ToolRequestMessage
                    String toolCallId = completeToolExecutionRequest.id();
                    if (toolCallId != null && seenToolRequestIds.add(toolCallId)) {
                        ToolRequestMessage toolRequestMessage = new ToolRequestMessage(completeToolExecutionRequest);
                        sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                    }
                })
                .onToolExecuted((ToolExecution toolExecution) -> {
                    try {
                        String toolCallId = toolExecution.request().id();
                        nativeToolExecutedMode.compareAndSet(false, true);
                        // 只要该 toolCallId 已经发过 synthetic tool_executed，就始终跳过 native 同 ID 事件，避免同一工具卡片重复渲染。
                        if (toolCallId != null && syntheticExecutedIds.contains(toolCallId)) {
                            return;
                        }
                    } catch (Exception ignore) {
                        // ignore
                    }
                    ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                    sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
                })
                .onCompleteResponse((ChatResponse response) -> {
                    try {
                        String projectDirName = "vue_project_" + appId;
                        Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName);
                        String path = projectRoot.toString();
                        boolean ok = vueProjectBuilder.buildProject(path);
                        if (!ok) {
                            log.warn("Vue 项目构建未成功，预览可能不可用。appId={} path={}", appId, path);
                        }
                    } catch (Exception e) {
                        log.error("Vue 项目构建异常。appId={}", appId, e);
                    }
                    sink.complete();
                })
                .onError((Throwable error) -> {
                    error.printStackTrace();
                    sink.error(error);
                })
                .start());
    }

    /**
     * 从流式累积的 writeFile arguments 中若能提取 path+content，则拼出一条 tool_executed JSON 供前端渲染
     *
     * @param toolCallId    工具调用 id
     * @param rawArguments  累积的参数字符串
     * @return JSON 字符串；字段不齐则 null
     */
    static String buildSyntheticWriteFileToolExecutedMessage(String toolCallId, String rawArguments) {
        return LegacyHtmlToolStreamSupport.buildSyntheticWriteFileToolExecutedMessage(toolCallId, rawArguments);
    }

    /**
     * 从 writeFile 的原始 arguments 串中提取 relativeFilePath + content（先严格后容错）
     *
     * @param rawArguments 模型输出的参数字符串（可能不完整）
     * @return 含两字段的 JSONObject；无法提取则 null
     */
    static JSONObject tryExtractWriteFileArguments(String rawArguments) {
        return LegacyHtmlToolStreamSupport.tryExtractWriteFileArguments(rawArguments);
    }
}
