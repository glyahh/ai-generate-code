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
        // 1. 多文件业务类型前缀
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
        // 1. 写 HTML 入口
        writeSingleFile(uniqueDirName, "index.html", codeResult.getHtmlCode());
        // 2. 写样式
        writeSingleFile(uniqueDirName, "style.css", codeResult.getCssCode());
        // 3. 写脚本
        writeSingleFile(uniqueDirName, "script.js", codeResult.getJsCode());
    }
}
