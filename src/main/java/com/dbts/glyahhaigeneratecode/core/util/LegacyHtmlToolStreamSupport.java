package com.dbts.glyahhaigeneratecode.core.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.ai.tool.ToolManager;
import com.dbts.glyahhaigeneratecode.core.handler.JsonMessageStreamHandler;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.AiServiceStreamingResponseHandler;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.FluxSink;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Legacy HTML/MULTI_FILE 流式工具片段：将工具请求与 writeFile 的合成执行块以纯文本推到 SSE
 * （与 {@link JsonMessageStreamHandler} 落库文本形态一致）。
 */
@Slf4j
public final class LegacyHtmlToolStreamSupport {

    static final int WRITE_FILE_EXTRACT_WARN_THRESHOLD = 16 * 1024;

    private LegacyHtmlToolStreamSupport() {
    }

    /**
     * Legacy HTML/MULTI_FILE：将工具请求与 writeFile 的合成执行块以纯文本推到 SSE（与 {@link JsonMessageStreamHandler} 落库文本形态一致）。
     * <p>
     * 说明：部分兼容接口在流式阶段从不触发 {@code onPartialToolExecutionRequest}（arguments 只在流结束帧出现），
     * 但 {@link dev.langchain4j.model.openai.OpenAiStreamingChatModel} 会在收尾调用 {@code onCompleteToolExecutionRequest}；
     * 若仅监听 partial，则前端永远收不到「选择工具 / 写入文件」段落（已由 {@link AiServiceStreamingResponseHandler} 转发 complete）。
     *
     * @param toolManager               工具注册表
     * @param sink                      SSE sink
     * @param appId                     应用 ID（日志）
     * @param codeGenTypeEnum           当前生成类型
     * @param toolExecutionRequest      工具执行请求（partial 或 complete）
     * @param toolArgsById              按 toolCallId 累积的 arguments 缓冲
     * @param syntheticExecutedIds      已合成 tool_executed 的 id 集合
     * @param warnedLargeIncompleteIds  超大参数告警去重集合
     * @param seenToolRequestIds        已展示工具请求的 id 集合
     * @param nativeToolExecutedMode    是否已收到原生 onToolExecuted,每个工具是否闭合
     * @param isPartialDelta            true=流式片段；false=完整 arguments
     */
    public static void emitLegacyHtmlToolStreamChunk(
            ToolManager toolManager,
            FluxSink<String> sink,
            Long appId,
            CodeGenTypeEnum codeGenTypeEnum,
            ToolExecutionRequest toolExecutionRequest,
            Map<String, StringBuilder> toolArgsById,
            Set<String> syntheticExecutedIds,
            Set<String> warnedLargeIncompleteIds,
            Set<String> seenToolRequestIds,
            AtomicBoolean nativeToolExecutedMode,
            boolean isPartialDelta) {
        try {
            // 1. 取出工具调用 id / 名 / 参数字片段
            String toolCallId = toolExecutionRequest.id();
            String toolName = toolExecutionRequest.name();
            String argsPart = toolExecutionRequest.arguments();

            // 2. 每个 toolCallId 首次出现时，向前端推「选择工具」类展示（与 JSON 流形态对齐）
            if (toolCallId != null
                    && StrUtil.isNotBlank(toolName)
                    && seenToolRequestIds.add(toolCallId)) {
                BaseTool tool = toolManager.getTool(toolName);
                String reqText = tool != null
                        ? tool.generateToolRequestResponse()
                        : String.format("\n\n[选择工具] %s\n", toolName.trim());
                try {
                    sink.next(reqText);
                } catch (Exception ignore) {
                    // downstream closed
                }
            }

            // 3. 非 writeFile、或已走原生 executed、或已合成过：不再拼 synthetic executed
            if (nativeToolExecutedMode.get()
                    || toolCallId == null
                    || !"writeFile".equals(toolName)
                    || syntheticExecutedIds.contains(toolCallId)) {
                return;
            }

            // 4. 累积 arguments：partial 模式拼 buffer，complete 模式用整段
            String accumulated;
            if (isPartialDelta) {
                if (argsPart == null) {
                    return;
                }
                StringBuilder buf = toolArgsById.computeIfAbsent(toolCallId, k -> new StringBuilder());
                buf.append(argsPart);
                accumulated = buf.toString();
            } else {
                accumulated = StrUtil.nullToEmpty(argsPart);
            }

            // 5. 若能从累积串提取 path+content，则合成 tool_executed 文本推给 sink
            String syntheticJson = buildSyntheticWriteFileToolExecutedMessage(toolCallId, accumulated);
            if (syntheticJson != null) {
                syntheticExecutedIds.add(toolCallId);
                JSONObject syn = JSONUtil.parseObj(syntheticJson);
                JSONObject argsObj = syn.getJSONObject("arguments");
                BaseTool wf = toolManager.getTool("writeFile");
                String plain = wf != null && argsObj != null
                        ? wf.generateToolExecutedResult(argsObj)
                        : syntheticJson;
                try {
                    sink.next(plain);
                } catch (Exception ignore) {
                    // downstream closed
                }
                return;
            }

            if (isPartialDelta) {
                int bufLen = toolArgsById.getOrDefault(toolCallId, new StringBuilder()).length();
                if (bufLen >= WRITE_FILE_EXTRACT_WARN_THRESHOLD && warnedLargeIncompleteIds.add(toolCallId)) {
                    log.warn(
                            "writeFile 参数流超过 {} 字节仍未提取出 relativeFilePath/content，继续等待后续片段。toolCallId={}",
                            WRITE_FILE_EXTRACT_WARN_THRESHOLD,
                            toolCallId);
                }
            }
        } catch (Exception e) {
            log.warn("emitLegacyHtmlToolStreamChunk 异常 appId={} partial={}", appId, isPartialDelta, e);
        }
    }

    /**
     * 流式阶段解析工具 arguments 字符串；非严格 JSON 时放入 _rawArguments
     *
     * @param arguments JSON 字符串
     * @return JSONObject，永不为 null
     */
    public static JSONObject safeParseToolArgumentsForStream(String arguments) {
        // 1. 空串返回空对象
        if (StrUtil.isBlank(arguments)) {
            return new JSONObject();
        }
        try {
            // 2. 严格解析
            return JSONUtil.parseObj(arguments);
        } catch (Exception e) {
            // 3. 失败降级
            JSONObject fallback = new JSONObject();
            fallback.set("_rawArguments", arguments);
            return fallback;
        }
    }

    /**
     * 找不到 BaseTool 时的工具执行结果占位文本
     *
     * @param toolName   工具名
     * @param jsonObject 参数
     * @return 一行展示字符串
     */
    public static String fallbackToolExecutedPlain(String toolName, JSONObject jsonObject) {
        // 1. 路径字段优先顺序
        String path = StrUtil.blankToDefault(
                jsonObject.getStr("relativeFilePath"),
                jsonObject.getStr("relativeDirPath"));
        // 2. 固定模板输出
        return String.format("[滚木工具] %s %s",
                StrUtil.blankToDefault(toolName, "滚木"),
                StrUtil.blankToDefault(path, "-"));
    }

    /**
     * 从流式累积的 writeFile arguments 中若能提取 path+content，则拼出一条 tool_executed JSON 供前端渲染
     *
     * @param toolCallId    工具调用 id
     * @param rawArguments  累积的参数字符串
     * @return JSON 字符串；字段不齐则 null
     */
    public static String buildSyntheticWriteFileToolExecutedMessage(String toolCallId, String rawArguments) {
        // 1. 无 id 无法与前端卡片关联
        if (toolCallId == null) {
            return null;
        }
        // 2. 从流式累积的 arguments 中抽取 path + content
        JSONObject extractedArguments = tryExtractWriteFileArguments(rawArguments);
        if (extractedArguments == null) {
            return null;
        }

        // 3. 拼合成 tool_executed 形态的 Map（arguments 为对象，避免二次 JSON 编码）
        Map<String, Object> synthetic = new HashMap<>();
        synthetic.put("type", "tool_executed");
        synthetic.put("id", toolCallId);
        synthetic.put("name", "writeFile");
        synthetic.put("arguments", extractedArguments);
        synthetic.put("result", "");
        return JSONUtil.toJsonStr(synthetic);
    }

    /**
     * 从 writeFile 的原始 arguments 串中提取 relativeFilePath + content（先严格后容错）
     *
     * @param rawArguments 模型输出的参数字符串（可能不完整）
     * @return 含两字段的 JSONObject；无法提取则 null
     */
    public static JSONObject tryExtractWriteFileArguments(String rawArguments) {
        // 1. 优先严格 JSON 解析
        JSONObject strict = extractWriteFileArgumentsStrict(rawArguments);
        if (strict != null) {
            return strict;
        }
        // 2. 失败则走手写扫描容错
        return extractWriteFileArgumentsTolerant(rawArguments);
    }

    /**
     * 严格 JSON 解析 writeFile arguments
     *
     * @param rawArguments 原始字符串
     * @return 两字段齐全则返回；否则 null
     */
    private static JSONObject extractWriteFileArgumentsStrict(String rawArguments) {
        // 1. 空串不可解析
        if (rawArguments == null || rawArguments.isBlank()) {
            return null;
        }
        try {
            // 2. 整体 parse 后取字段并规范化
            JSONObject obj = JSONUtil.parseObj(rawArguments);
            return normalizeWriteFileArguments(obj.getStr("relativeFilePath"), obj.getStr("content"));
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 在 JSON 可能截断或不合法时，扫描字符串手工提取 relativeFilePath / content
     *
     * @param rawArguments 原始字符串
     * @return 两字段齐全则返回；否则 null
     */
    private static JSONObject extractWriteFileArgumentsTolerant(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return null;
        }

        // 1. 跳过空白找到首个 '{'
        int idx = skipWhitespace(rawArguments, 0);
        if (idx >= rawArguments.length() || rawArguments.charAt(idx) != '{') {
            return null;
        }
        idx++;

        String relativeFilePath = null;
        String content = null;

        // 2. 手工状态机扫描 key:value，直到凑齐两字段或无法继续
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

    /**
     * 仅当 path 与 content 均非 null 时组装 JSONObject
     *
     * @param relativeFilePath 相对路径
     * @param content          文件内容
     * @return 合法对象或 null
     */
    private static JSONObject normalizeWriteFileArguments(String relativeFilePath, String content) {
        if (relativeFilePath == null || content == null) {
            return null;
        }
        JSONObject normalized = new JSONObject();
        normalized.set("relativeFilePath", relativeFilePath);
        normalized.set("content", content);
        return normalized;
    }

    /**
     * 从 idx 起跳过空白字符，返回新下标
     *
     * @param text 全文
     * @param idx  起始下标
     * @return 第一个非空白字符下标或文末
     */
    private static int skipWhitespace(String text, int idx) {
        int cursor = idx;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    /**
     * 自 quoteStart 起解析 JSON 字符串字面量（支持常见转义）
     *
     * @param text       全文
     * @param quoteStart 双引号位置
     * @return 解析结果与下一下标；失败 null
     */
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

    /**
     * 跳过从 start 开始的完整 JSON 值（字符串/对象/数组/字面量），返回结束后的下标
     *
     * @param text  全文
     * @param start 值起点
     * @return 下一位置；失败 -1
     */
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

    /**
     * parseJsonString 的解析结果：字符串值与下一读取下标
     *
     * @param value     解码后的字符串
     * @param nextIndex 继续扫描的起始下标
     */
    private record JsonStringParseResult(String value, int nextIndex) {
    }
}
