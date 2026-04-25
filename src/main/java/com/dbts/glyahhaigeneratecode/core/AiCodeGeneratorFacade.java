package com.dbts.glyahhaigeneratecode.core;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorService;
import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorServiceFactory;
import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.message.AiResponseMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolExecutedMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolRequestMessage;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.core.Builder.vueProjectBuilder;
import com.dbts.glyahhaigeneratecode.core.context.HtmlMultiFileEditContextBuilder;
import com.dbts.glyahhaigeneratecode.core.parser.CodeParserExecutor;
import com.dbts.glyahhaigeneratecode.core.saver.CodeFileSaverExecutor;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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

    private static final CodeFileSaverExecutor codeFileSaverExecutor = new CodeFileSaverExecutor();
    private static final CodeParserExecutor codeParserExecutor = new CodeParserExecutor();

    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }

        aiCodeGeneratorService aiCodeGeneratorService =
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);

        return switch (codeGenTypeEnum) {
            case HTML -> {
                HtmlCodeResult result = aiCodeGeneratorService.generateCodeHTML(userMessage);
                yield codeFileSaverExecutor.execute(codeGenTypeEnum, result, appId);
            }
            case MULTI_FILE -> {
                MultiFileCodeResult result = aiCodeGeneratorService.generateCodeMultiFile(userMessage);
                yield codeFileSaverExecutor.execute(codeGenTypeEnum, result, appId);
            }
            default -> throw new MyException(ErrorCode.SYSTEM_ERROR, "不支持的生成类型: " + codeGenTypeEnum.getValue());
        };
    }

    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        return generateAndSaveCodeStream(userMessage, codeGenTypeEnum, appId, false);
    }

    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId, boolean firstRound) {
        if (codeGenTypeEnum == null) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }

        return switch (codeGenTypeEnum) {
            case HTML -> generateAndSaveHtmlCodeStream(userMessage, appId);
            case MULTI_FILE -> generateAndSaveMultiFileCodeStream(userMessage, appId);
            case VUE -> generateAndSaveVueCodeStream(userMessage, appId, firstRound);
            default -> throw new MyException(ErrorCode.SYSTEM_ERROR, "不支持的生成类型: " + codeGenTypeEnum.getValue());
        };
    }

    private Flux<String> processCodeStream(CodeGenTypeEnum codeGenTypeEnum, Flux<String> result, Long appId) {
        StringBuilder codeBuilder = new StringBuilder();
        return result.doOnNext(codeBuilder::append)
                .doOnComplete(() -> {
                    try {
                        Object executeResult = codeParserExecutor.execute(codeGenTypeEnum, codeBuilder.toString());
                        File file = codeFileSaverExecutor.execute(codeGenTypeEnum, executeResult, appId);
                        log.info("保存目录: {}", file.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("生成代码失败: {}", e.getMessage(), e);
                    }
                });
    }

    private Flux<String> generateAndSaveMultiFileCodeStream(String userMessage, Long appId) {
        aiCodeGeneratorService aiCodeGeneratorService =
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, CodeGenTypeEnum.MULTI_FILE);
        String finalPrompt = htmlMultiFileEditContextBuilder.buildPromptIfNeed(userMessage, CodeGenTypeEnum.MULTI_FILE, appId);
        Flux<String> result = aiCodeGeneratorService.generateCodeMultiFileStream(finalPrompt);
        return processCodeStream(CodeGenTypeEnum.MULTI_FILE, result, appId);
    }

    private Flux<String> generateAndSaveHtmlCodeStream(String userMessage, Long appId) {
        aiCodeGeneratorService aiCodeGeneratorService =
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML);
        String finalPrompt = htmlMultiFileEditContextBuilder.buildPromptIfNeed(userMessage, CodeGenTypeEnum.HTML, appId);
        Flux<String> result = aiCodeGeneratorService.generateCodeHTMLStream(finalPrompt);
        return processCodeStream(CodeGenTypeEnum.HTML, result, appId);
    }

    private Flux<String> generateAndSaveVueCodeStream(String userMessage, Long appId, boolean firstRound) {
        aiCodeGeneratorService aiCodeGeneratorService =
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, CodeGenTypeEnum.VUE, firstRound);
        TokenStream tokenStream = aiCodeGeneratorService.generateCodeVueFileStream(appId, userMessage);
        return adaptVueTokenStream(tokenStream, appId);
    }

    Flux<String> adaptVueTokenStream(TokenStream tokenStream, Long appId) {
        // 按 toolCallId 累积流式 arguments，直到能拼出一个“可提取关键字段”的完整片段
        Map<String, StringBuilder> toolArgsById = new HashMap<>();
        // 记录已经发过 synthetic tool_executed 的 toolCallId，避免重复发卡片
        Set<String> syntheticExecutedIds = new HashSet<>();
        // 只在超大参数且长期提取失败时告警一次，避免日志刷屏
        Set<String> warnedLargeIncompleteIds = new HashSet<>();
        // 一旦收到模型原生 onToolExecuted，就切回原生模式，不再继续合成 synthetic 消息
        AtomicBoolean nativeToolExecutedMode = new AtomicBoolean(false);

        return Flux.create(sink -> tokenStream.onPartialResponse((String partialResponse) -> {
                    AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                    sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                })
                .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                    ToolRequestMessage toolRequestMessage = new ToolRequestMessage(toolExecutionRequest);
                    sink.next(JSONUtil.toJsonStr(toolRequestMessage));

                    try {
                        String toolCallId = toolExecutionRequest.id();
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
                .onToolExecuted((ToolExecution toolExecution) -> {
                    try {
                        String toolCallId = toolExecution.request().id();
                        boolean switched = nativeToolExecutedMode.compareAndSet(false, true);
                        if (switched && toolCallId != null && syntheticExecutedIds.contains(toolCallId)) {
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

    static String buildSyntheticWriteFileToolExecutedMessage(String toolCallId, String rawArguments) {
        if (toolCallId == null) {
            return null;
        }
        JSONObject extractedArguments = tryExtractWriteFileArguments(rawArguments);
        if (extractedArguments == null) {
            return null;
        }

        Map<String, Object> synthetic = new HashMap<>();
        synthetic.put("type", "tool_executed");
        synthetic.put("id", toolCallId);
        synthetic.put("name", "writeFile");
        synthetic.put("arguments", JSONUtil.toJsonStr(extractedArguments));
        synthetic.put("result", "");
        return JSONUtil.toJsonStr(synthetic);
    }

    static JSONObject tryExtractWriteFileArguments(String rawArguments) {
        // 两段式策略：
        // 1) 先走严格 JSON 解析（标准路径，最准确）
        // 2) 严格解析失败再走容错提取（只关心 writeFile 必需的两个字段）
        JSONObject strict = extractWriteFileArgumentsStrict(rawArguments);
        if (strict != null) {
            return strict;
        }
        return extractWriteFileArgumentsTolerant(rawArguments);
    }

    private static JSONObject extractWriteFileArgumentsStrict(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return null;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(rawArguments);
            return normalizeWriteFileArguments(obj.getStr("relativeFilePath"), obj.getStr("content"));
        } catch (Exception ignore) {
            return null;
        }
    }

    private static JSONObject extractWriteFileArgumentsTolerant(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return null;
        }

        int idx = skipWhitespace(rawArguments, 0);
        if (idx >= rawArguments.length() || rawArguments.charAt(idx) != '{') {
            return null;
        }
        idx++;

        String relativeFilePath = null;
        String content = null;

        while (idx < rawArguments.length()) {
            idx = skipWhitespace(rawArguments, idx);
            if (idx >= rawArguments.length()) {
                break;
            }

            char current = rawArguments.charAt(idx);
            if (current == ',') {
                idx++;
                continue;
            }
            if (current == '}') {
                idx++;
                break;
            }
            if (current != '"') {
                // 出现脏字符/截断时，不继续硬解析；直接尝试用当前已提取字段返回
                return normalizeWriteFileArguments(relativeFilePath, content);
            }

            JsonStringParseResult keyResult = parseJsonString(rawArguments, idx);
            if (keyResult == null) {
                return normalizeWriteFileArguments(relativeFilePath, content);
            }

            idx = skipWhitespace(rawArguments, keyResult.nextIndex());
            if (idx >= rawArguments.length() || rawArguments.charAt(idx) != ':') {
                return normalizeWriteFileArguments(relativeFilePath, content);
            }

            idx++;
            idx = skipWhitespace(rawArguments, idx);
            if (idx >= rawArguments.length()) {
                return normalizeWriteFileArguments(relativeFilePath, content);
            }

            String key = keyResult.value();
            char valueStart = rawArguments.charAt(idx);
            if (valueStart == '"') {
                JsonStringParseResult valueResult = parseJsonString(rawArguments, idx);
                if (valueResult == null) {
                    return normalizeWriteFileArguments(relativeFilePath, content);
                }
                if ("relativeFilePath".equals(key)) {
                    relativeFilePath = valueResult.value();
                } else if ("content".equals(key)) {
                    content = valueResult.value();
                }
                if (relativeFilePath != null && content != null) {
                    // 关键字段齐了就提前返回，不要求整段 JSON 完全无噪声
                    return normalizeWriteFileArguments(relativeFilePath, content);
                }
                idx = valueResult.nextIndex();
                continue;
            }

            int nextIdx = skipJsonValue(rawArguments, idx);
            if (nextIdx < 0) {
                return normalizeWriteFileArguments(relativeFilePath, content);
            }
            idx = nextIdx;
        }

        return normalizeWriteFileArguments(relativeFilePath, content);
    }

    private static JSONObject normalizeWriteFileArguments(String relativeFilePath, String content) {
        if (relativeFilePath == null || content == null) {
            return null;
        }
        JSONObject normalized = new JSONObject();
        normalized.set("relativeFilePath", relativeFilePath);
        normalized.set("content", content);
        return normalized;
    }

    private static int skipWhitespace(String text, int idx) {
        int cursor = idx;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static JsonStringParseResult parseJsonString(String text, int quoteStart) {
        if (quoteStart < 0 || quoteStart >= text.length() || text.charAt(quoteStart) != '"') {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = quoteStart + 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                return new JsonStringParseResult(sb.toString(), i + 1);
            }
            if (ch != '\\') {
                sb.append(ch);
                continue;
            }

            if (i + 1 >= text.length()) {
                return null;
            }

            char escaped = text.charAt(++i);
            switch (escaped) {
                case '"', '\\', '/' -> sb.append(escaped);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 >= text.length()) {
                        return null;
                    }
                    String hex = text.substring(i + 1, i + 5);
                    try {
                        sb.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    i += 4;
                }
                default -> {
                    return null;
                }
            }
        }

        return null;
    }

    private static int skipJsonValue(String text, int start) {
        if (start >= text.length()) {
            return -1;
        }

        char first = text.charAt(start);
        if (first == '"') {
            JsonStringParseResult parsed = parseJsonString(text, start);
            return parsed == null ? -1 : parsed.nextIndex();
        }

        if (first == '{' || first == '[') {
            char open = first;
            char close = open == '{' ? '}' : ']';
            int depth = 1;
            boolean inString = false;
            boolean escaping = false;

            for (int i = start + 1; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (inString) {
                    if (escaping) {
                        escaping = false;
                    } else if (ch == '\\') {
                        escaping = true;
                    } else if (ch == '"') {
                        inString = false;
                    }
                    continue;
                }

                if (ch == '"') {
                    inString = true;
                    continue;
                }
                if (ch == open) {
                    depth++;
                    continue;
                }
                if (ch == close) {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
            }
            return -1;
        }

        int idx = start;
        while (idx < text.length()) {
            char ch = text.charAt(idx);
            if (ch == ',' || ch == '}') {
                return idx;
            }
            idx++;
        }
        return idx;
    }

    private record JsonStringParseResult(String value, int nextIndex) {
    }
}
