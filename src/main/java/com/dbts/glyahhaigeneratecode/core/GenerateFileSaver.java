package com.dbts.glyahhaigeneratecode.core;

import cn.hutool.Hutool;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;

import java.io.File;

/**
 * 文件保存类
 * 工具类
 *
 * 大致思路:
 * 创建目录,写入文件内容并给文件命名
 */
@Deprecated
public class GenerateFileSaver {

    // 文件保存的根目录
    private static final String Base_ROOT_PATH = "D:\\mainJava\\all Code\\program\\glyahh-ai-generate-code\\temp\\code_output";

    /**
     * 保存HTML文件
     * @param htmlCodeResult
     */
    public static File saveHtmlFile(HtmlCodeResult htmlCodeResult) {
        String uniqueDirName = buildUniqueDirName(CodeGenTypeEnum.HTML.getValue());
        saveSingleFile(uniqueDirName, "index.html", htmlCodeResult.getHtmlCode());
        return new File(uniqueDirName);
    }

    /**
     * 保存多个文件
     * @param multiFileCodeResult
     */
    public static File saveMultiFile(MultiFileCodeResult multiFileCodeResult) {
        String uniqueDirName = buildUniqueDirName(CodeGenTypeEnum.MULTI_FILE.getValue());
        saveSingleFile(uniqueDirName, "index.html", multiFileCodeResult.getHtmlCode());
        saveSingleFile(uniqueDirName, "style.css", multiFileCodeResult.getCssCode());
        saveSingleFile(uniqueDirName, "script.js", multiFileCodeResult.getJsCode());
        return new File(uniqueDirName);
    }

    /**
     * 构造文件目录,创建文件
     * @param bizType
     * @return
     */
    private static String buildUniqueDirName (String bizType){
        String uniqueDirName = Base_ROOT_PATH + File.separator + bizType + "_" + IdUtil.getSnowflakeNextIdStr();
        FileUtil.mkdir(uniqueDirName);
        return uniqueDirName;
    }

    /**
     * 保存单个文件
     * 在保存的文件中写入内容
     */
    private static void saveSingleFile(String Path, String fileName, String content) {
        String filePath = Path + File.separator + fileName;
        FileUtil.writeString(content, filePath, "UTF-8");
    }
}
