package com.dbts.glyahhaigeneratecode.core.saver;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;

import java.io.File;

/**
 * 代码文件保存模板，按步骤完成校验、建目录、落盘、返回目录
 * 抽象类
 *
 * 大致思路: 校验入参 → 建唯一目录 → 子类写文件 → 返回目录 File
 */
public abstract class CodeFileSaverTemplate<T> {

    private static final String Base_ROOT_PATH = "D:\\mainJava\\all Code\\program\\glyahh-ai-generate-code\\temp\\code_output";

    /**
     * 统一入口：保存代码并返回所在目录
     *
     * @param codeResult 待保存的代码模型（HtmlCodeResult / MultiFileCodeResult）
     * @return 保存后的目录对象
     */
    public final File save(T codeResult, Long appId) {
        validateInput(codeResult);
        String uniqueDirName = buildUniqueDirName(appId);
        saveFiles(uniqueDirName, codeResult);
        return new File(uniqueDirName);
    }

    /**
     * 校验入参是否合法，为空则抛 MyException
     *
     * @param codeResult 待保存的代码模型
     */
    private void validateInput(T codeResult) {
        if (codeResult == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "保存的代码对象为空");
        }
    }

    /**
     * 构建唯一目录并创建，返回目录路径
     *
     * @return 唯一目录路径字符串
     */
    private String buildUniqueDirName(Long appId) {
        String uniqueDirName = buildUniqueDirPath(appId);
        ensureDirExists(uniqueDirName);
        return uniqueDirName;
    }

    /**
     * 拼出唯一目录路径（未创建）
     *
     * @return 目录路径字符串
     */
    private String buildUniqueDirPath(Long appId) {
        String bizType = getBizType();
        return Base_ROOT_PATH + File.separator + bizType + "_" + appId;
    }

    /**
     * 根据路径创建目录
     *
     * @param dirPath 目录路径
     */
    private void ensureDirExists(String dirPath) {
        FileUtil.mkdir(dirPath);
    }

    /**
     * 子类返回当前保存类型标识，用于拼进目录名
     *
     * @return 如 html / multi_file
     */
    protected abstract String getBizType();

    /**
     * 子类实现：在指定目录下写入具体文件
     *
     * @param uniqueDirName 已创建好的唯一目录路径
     * @param codeResult    待保存的代码模型
     */
    protected abstract void saveFiles(String uniqueDirName, T codeResult);

    /**
     * 在指定目录下写入单个文件（子类可调）
     *
     * @param Path     目录路径
     * @param fileName 文件名
     * @param content  文件内容
     */
    protected void writeSingleFile(String Path, String fileName, String content) {
        // 若生成内容为空，认为本次生成失败，抛出异常避免覆盖掉已有文件
        if (StrUtil.isBlank(content)) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "生成的 " + fileName + " 内容为空，已终止写入");
        }
        String filePath = Path + File.separator + fileName;
        FileUtil.writeString(content, filePath, "UTF-8");
    }
}
