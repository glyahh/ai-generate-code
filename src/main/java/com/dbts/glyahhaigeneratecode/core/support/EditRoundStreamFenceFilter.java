package com.dbts.glyahhaigeneratecode.core.support;

import cn.hutool.core.util.StrUtil;

/**
 * 编辑轮流式输出过滤器：抑制模型误输出的 Markdown 代码围栏内容，仅保留围栏外的简短说明。
 * 工具落盘结果由 {@code onToolExecuted} 等回调单独推送，不依赖围栏内文本。
 * 换句话说:
 * 流式分片处理模型 partial 输出
 * 丢弃 ``` 围栏内的代码
 * 只保留围栏外的自然语言说明（如「我来改一下导航栏颜色」）
 */
public final class EditRoundStreamFenceFilter {

    private final StringBuilder carry = new StringBuilder();
    private boolean inFence;

    /**
     * 过滤分片，返回可推送给前端的文本（可能为空）。
     *
     * @param chunk 模型 partial 原文
     * @return 围栏外可见文本
     */
    public String filter(String chunk) {
        if (StrUtil.isEmpty(chunk)) {
            return "";
        }
        carry.append(chunk);
        StringBuilder out = new StringBuilder(chunk.length());
        int i = 0;
        while (i < carry.length()) {
            if (!inFence) {
                int fenceStart = indexOfFenceOpen(carry, i);
                if (fenceStart < 0) {
                    out.append(carry.substring(i));
                    carry.setLength(0);
                    break;
                }
                if (fenceStart > i) {
                    out.append(carry.substring(i, fenceStart));
                }
                int lineEnd = indexOfLineEnd(carry, fenceStart);
                if (lineEnd < 0) {
                    if (fenceStart > 0) {
                        carry.delete(0, fenceStart);
                    }
                    break;
                }
                inFence = true;
                i = lineEnd;
                continue;
            }
            int fenceClose = indexOfFenceClose(carry, i);
            if (fenceClose < 0) {
                if (i > 0) {
                    carry.delete(0, i);
                }
                break;
            }
            int afterClose = fenceClose;
            if (afterClose < carry.length() && carry.charAt(afterClose) == '\r') {
                afterClose++;
            }
            if (afterClose < carry.length() && carry.charAt(afterClose) == '\n') {
                afterClose++;
            }
            inFence = false;
            i = afterClose;
        }
        if (!inFence && carry.length() > 0 && i > 0) {
            carry.delete(0, i);
        }
        return out.toString();
    }

    private static int indexOfFenceOpen(CharSequence s, int from) {
        for (int i = from; i + 2 < s.length(); i++) {
            if (s.charAt(i) == '`' && s.charAt(i + 1) == '`' && s.charAt(i + 2) == '`') {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfFenceClose(CharSequence s, int from) {
        for (int i = from; i + 2 < s.length(); i++) {
            if (s.charAt(i) == '`' && s.charAt(i + 1) == '`' && s.charAt(i + 2) == '`') {
                int lineEnd = indexOfLineEnd(s, i);
                if (lineEnd >= 0) {
                    return lineEnd;
                }
                return -1;
            }
        }
        return -1;
    }

    private static int indexOfLineEnd(CharSequence s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                return i + 1;
            }
            if (c == '\r') {
                return (i + 1 < s.length() && s.charAt(i + 1) == '\n') ? i + 2 : i + 1;
            }
        }
        return -1;
    }
}
