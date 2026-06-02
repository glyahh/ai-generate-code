package com.dbts.glyahhaigeneratecode.guardrail;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 用户可见助手输出脱敏：按行剔除内部技术栈/构建部署术语，不改动工具协议行与代码围栏内容。
 */
@Component
public class UserFacingOutputSanitizer {

    private static final Pattern TOOL_PROTOCOL_LINE_RE = Pattern.compile(
            "^\\[(?:选择工具|工具调用|workflow(?:_stage_status|_notice)?)\\]",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern[] STACK_JARGON_LINE_PATTERNS = {
            Pattern.compile("vue\\s*3", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bvite\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("vue\\s*router", Pattern.CASE_INSENSITIVE),
            Pattern.compile("hash\\s*模式", Pattern.CASE_INSENSITIVE),
            Pattern.compile("hash\\s*路由", Pattern.CASE_INSENSITIVE),
            Pattern.compile("子路径部署", Pattern.CASE_INSENSITIVE),
            Pattern.compile("部署配置", Pattern.CASE_INSENSITIVE),
            Pattern.compile("可构建", Pattern.CASE_INSENSITIVE),
            Pattern.compile("可运行", Pattern.CASE_INSENSITIVE),
            Pattern.compile("npm\\s+(?:install|run|build)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("主要技术栈", Pattern.CASE_INSENSITIVE),
            Pattern.compile("技术栈", Pattern.CASE_INSENSITIVE),
    };

    /**
     * 流式分片缓冲：按行切分后再做脱敏，避免 chunk 截断误删半行。
     */
    public static final class StreamBuffer {
        private final StringBuilder lineCarry = new StringBuilder();
        private boolean inFence;

        private StreamBuffer() {
        }
    }

    public StreamBuffer newStreamBuffer() {
        return new StreamBuffer();
    }

    /**
     * 整段文本脱敏（入库/回放）。
     */
    public String sanitizeText(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        StreamBuffer buffer = newStreamBuffer();
        String sanitized = sanitizeChunk(buffer, text);
        String tail = flush(buffer);
        if (StrUtil.isBlank(tail)) {
            return sanitized == null ? "" : sanitized;
        }
        if (StrUtil.isBlank(sanitized)) {
            return tail;
        }
        return sanitized + tail;
    }

    /**
     * 流式分片脱敏；仅输出已完整的行（以 {@code \n} 结尾的片段）。
     */
    public String sanitizeChunk(StreamBuffer buffer, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        buffer.lineCarry.append(chunk);
        return drainCompleteLines(buffer, false);
    }

    /**
     * 流结束时刷出剩余内容。
     */
    public String flush(StreamBuffer buffer) {
        if (buffer == null || buffer.lineCarry.isEmpty()) {
            return "";
        }
        return drainCompleteLines(buffer, true);
    }

    private String drainCompleteLines(StreamBuffer buffer, boolean flushRemainder) {
        String raw = buffer.lineCarry.toString();
        if (raw.isEmpty()) {
            return "";
        }

        int lastNewline = -1;
        StringBuilder out = new StringBuilder(raw.length());

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\n' && c != '\r') {
                continue;
            }
            int lineEnd = i;
            int lineStart = lastNewline + 1;
            String line = raw.substring(lineStart, lineEnd);
            appendSanitizedLine(buffer, out, line, true);
            if (c == '\r' && i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
                i++;
            }
            lastNewline = i;
        }

        if (flushRemainder && lastNewline < raw.length() - 1) {
            String tailLine = raw.substring(lastNewline + 1);
            appendSanitizedLine(buffer, out, tailLine, false);
            buffer.lineCarry.setLength(0);
        } else if (lastNewline >= 0) {
            buffer.lineCarry.delete(0, lastNewline + 1);
        } else if (flushRemainder) {
            appendSanitizedLine(buffer, out, raw, false);
            buffer.lineCarry.setLength(0);
        }

        return out.toString();
    }

    private void appendSanitizedLine(StreamBuffer buffer, StringBuilder out, String line, boolean lineHadNewline) {
        boolean drop = shouldDropLine(buffer, line);
        boolean fenceCandidate = line.replaceAll("^ {0,3}", "").startsWith("```");
        if (!drop) {
            out.append(line);
            if (lineHadNewline) {
                out.append('\n');
            }
        }
        if (fenceCandidate) {
            buffer.inFence = !buffer.inFence;
        }
    }

    private boolean shouldDropLine(StreamBuffer buffer, String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (buffer.inFence) {
            return false;
        }
        if (TOOL_PROTOCOL_LINE_RE.matcher(trimmed).find()) {
            return false;
        }
        for (Pattern pattern : STACK_JARGON_LINE_PATTERNS) {
            if (pattern.matcher(trimmed).find()) {
                return true;
            }
        }
        return false;
    }
}
