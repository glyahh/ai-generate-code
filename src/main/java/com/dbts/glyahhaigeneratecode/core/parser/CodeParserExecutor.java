package com.dbts.glyahhaigeneratecode.core.parser;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;

/**
 * 代码解析执行器，按生成类型选择对应解析器并返回 Object，以兼容 CodeParser 的两种泛型
 * 执行器
 *
 * 大致思路: 按类型 switch → 调对应 parser.parse() → 返回 Object；失败抛 MyException
 */
public class CodeParserExecutor {

    private static final HtmlCodeParser htmlCodeParser = new HtmlCodeParser();
    private static final MultiFileCodeParser multiFileCodeParser = new MultiFileCodeParser();

    /**
     * 使用外部 new 出来的两个解析器实例
     *
     * @param htmlCodeParser      HTML 解析器
     * @param multiFileCodeParser 多文件解析器
     */


    /**
     * 根据生成类型解析代码，返回 Object 以兼容两种泛型结果
     *
     * @param codeGenTypeEnum 生成类型（HTML / MULTI_FILE）
     * @param codeContent     原始代码字符串
     * @return 解析结果，HTML 时为 HtmlCodeResult，MULTI_FILE 时为 MultiFileCodeResult
     */
    public  Object execute(CodeGenTypeEnum codeGenTypeEnum, String codeContent) {
        if (codeGenTypeEnum == null) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        try {
            return switch (codeGenTypeEnum) {
                case HTML -> htmlCodeParser.parse(codeContent);
                case MULTI_FILE -> multiFileCodeParser.parse(codeContent);
                default -> {
                    String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                    throw new MyException(ErrorCode.SYSTEM_ERROR, errorMessage);
                }
            };
        } catch (MyException e) {
            throw e;
        } catch (Exception e) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "代码解析失败: " + e.getMessage());
        }
    }
}
