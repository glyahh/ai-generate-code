package com.dbts.glyahhaigeneratecode.core.saver;

import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;

import java.io.File;

/**
 * 代码文件保存执行器，按枚举类型选对应 Template 保存，入参用 Object 兼容两种代码类
 * 执行器
 *
 * 大致思路: 按枚举 switch → 强转为对应代码类 → 调对应 Template.save() → 返回 File；失败抛 MyException
 */
public class CodeFileSaverExecutor {

    private final HtmlCodeFileSaverTemplate htmlCodeFileSaverTemplate = new HtmlCodeFileSaverTemplate();
    private final MultiFileCodeFileSaverTemplate multiFileCodeFileSaverTemplate = new MultiFileCodeFileSaverTemplate();

    /**
     * 根据生成类型保存代码（Object 强转为对应类型），返回保存目录
     *
     * @param codeGenTypeEnum 生成类型（HTML / MULTI_FILE）
     * @param codeResult      代码结果，HTML 时为 HtmlCodeResult，MULTI_FILE 时为 MultiFileCodeResult
     * @param appId           应用主键，用于拼输出目录名
     * @return 保存后的目录对象
     */
    public File execute(CodeGenTypeEnum codeGenTypeEnum, Object codeResult, Long appId) {
        // 1. 校验生成类型非空
        if (codeGenTypeEnum == null) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        try {
            // 2. 分支强转并调用对应 Template.save
            return switch (codeGenTypeEnum) {
                case HTML -> htmlCodeFileSaverTemplate.save((HtmlCodeResult) codeResult, appId);
                case MULTI_FILE -> multiFileCodeFileSaverTemplate.save((MultiFileCodeResult) codeResult, appId);
                default -> {
                    String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                    throw new MyException(ErrorCode.SYSTEM_ERROR, errorMessage);
                }
            };
        } catch (MyException e) {
            // 3. 捕获后统一加上「代码保存失败」前缀，便于日志与前端提示
            throw new MyException(ErrorCode.SYSTEM_ERROR, "代码保存失败: " + e.getMessage());
        }
    }
}
