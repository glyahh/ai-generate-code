package com.dbts.glyahhaigeneratecode.core.parser;

/**
 * 代码解析器接口，按类型将字符串解析为对应结果模型
 * 接口
 *
 * 大致思路: 字符串进 → parse() → 得到 T（要么 HTML 结果，要么多文件结果）
 */
public interface CodeParser<T> {

    /**
     * 将原始代码字符串解析为对应类型结果
     *
     * @param codeContent 原始代码内容（可能含 markdown 代码块等）
     * @return 解析后的结果，类型由实现类泛型决定
     */
    T parse(String codeContent);
}
