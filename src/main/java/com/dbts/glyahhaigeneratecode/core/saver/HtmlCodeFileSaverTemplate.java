package com.dbts.glyahhaigeneratecode.core.saver;

import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;

/**
 * HTML 单文件保存模板，将 HtmlCodeResult 写入目录下的 index.html
 * 子类（实现 CodeFileSaverTemplate）
 *
 * 大致思路: 业务类型 html → 在父类给的目录里写 index.html，内容来自 codeResult.getHtmlCode()
 */
public class HtmlCodeFileSaverTemplate extends CodeFileSaverTemplate<HtmlCodeResult> {

    /**
     * 返回 HTML 模式的业务类型标识，用于父类拼目录名
     *
     * @return html
     */
    @Override
    protected String getBizType() {
        return CodeGenTypeEnum.HTML.getValue();
    }

    /**
     * 在指定目录下保存 index.html，内容来自 codeResult
     *
     * @param uniqueDirName 父类已建好的唯一目录路径
     * @param codeResult    HTML 代码结果
     */
    @Override
    protected void saveFiles(String uniqueDirName, HtmlCodeResult codeResult) {
        writeSingleFile(uniqueDirName, "index.html", codeResult.getHtmlCode());
    }
}
