package com.dbts.glyahhaigeneratecode.core.saver;

import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;

/**
 * 多文件保存模板，将 MultiFileCodeResult 写入目录下的 index.html、style.css、script.js
 * 子类（实现 CodeFileSaverTemplate）
 *
 * 大致思路: 业务类型 multi_file → 在父类给的目录里依次写 index.html / style.css / script.js
 */
public class MultiFileCodeFileSaverTemplate extends CodeFileSaverTemplate<MultiFileCodeResult> {

    /**
     * 返回多文件模式的业务类型标识，用于父类拼目录名
     *
     * @return multi_file
     */
    @Override
    protected String getBizType() {
        return CodeGenTypeEnum.MULTI_FILE.getValue();
    }

    /**
     * 在指定目录下保存 index.html、style.css、script.js
     *
     * @param uniqueDirName 父类已建好的唯一目录路径
     * @param codeResult    多文件代码结果
     */
    @Override
    protected void saveFiles(String uniqueDirName, MultiFileCodeResult codeResult) {
        writeSingleFile(uniqueDirName, "index.html", codeResult.getHtmlCode());
        writeSingleFile(uniqueDirName, "style.css", codeResult.getCssCode());
        writeSingleFile(uniqueDirName, "script.js", codeResult.getJsCode());
    }
}
