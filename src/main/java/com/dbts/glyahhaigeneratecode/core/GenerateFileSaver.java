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
     * 将单文件 HTML 结果写入磁盘（index.html），目录名带雪花 ID
     *
     * @param htmlCodeResult 含 html 正文的模型结果
     * @return 输出目录对应的 File
     */
    public static File saveHtmlFile(HtmlCodeResult htmlCodeResult) {
        // 1. 生成 html_{snowflakeId} 目录并创建
        String uniqueDirName = buildUniqueDirName(CodeGenTypeEnum.HTML.getValue());
        // 2. 写入 index.html
        saveSingleFile(uniqueDirName, "index.html", htmlCodeResult.getHtmlCode());
        // 3. 返回目录 File
        return new File(uniqueDirName);
    }

    /**
     * 将多文件结果写入磁盘（index.html、style.css、script.js）
     *
     * @param multiFileCodeResult 含 html/css/js 的结果对象
     * @return 输出目录对应的 File
     */
    public static File saveMultiFile(MultiFileCodeResult multiFileCodeResult) {
        // 1. 生成 multi_file_{snowflakeId} 目录
        String uniqueDirName = buildUniqueDirName(CodeGenTypeEnum.MULTI_FILE.getValue());
        // 2. 依次写入三个固定文件名
        saveSingleFile(uniqueDirName, "index.html", multiFileCodeResult.getHtmlCode());
        saveSingleFile(uniqueDirName, "style.css", multiFileCodeResult.getCssCode());
        saveSingleFile(uniqueDirName, "script.js", multiFileCodeResult.getJsCode());
        // 3. 返回目录 File
        return new File(uniqueDirName);
    }

    /**
     * 拼接唯一输出目录路径并创建该目录
     *
     * @param bizType 业务类型前缀（如 html、multi_file）
     * @return 已创建的目录绝对路径字符串
     */
    private static String buildUniqueDirName (String bizType){
        // 1. 路径 = 根目录 + 类型 + 雪花字符串
        String uniqueDirName = Base_ROOT_PATH + File.separator + bizType + "_" + IdUtil.getSnowflakeNextIdStr();
        // 2. 创建目录（不存在则建）
        FileUtil.mkdir(uniqueDirName);
        // 3. 返回路径
        return uniqueDirName;
    }

    /**
     * 在指定目录下写入单个文本文件（UTF-8）
     *
     * @param Path    目录路径
     * @param fileName 文件名
     * @param content  文件正文
     */
    private static void saveSingleFile(String Path, String fileName, String content) {
        // 1. 拼出完整文件路径
        String filePath = Path + File.separator + fileName;
        // 2. Hutool 按 UTF-8 写入磁盘
        FileUtil.writeString(content, filePath, "UTF-8");
    }
}
