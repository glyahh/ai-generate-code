package com.dbts.glyahhaigeneratecode.core;

import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorService;
import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import com.dbts.glyahhaigeneratecode.core.parser.CodeParserExecutor;
import com.dbts.glyahhaigeneratecode.core.saver.CodeFileSaverExecutor;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;

/**
 * AI 代码生成外观类，组合生成和保存功能
 * 门面类(工具类)
 *
 * 大致思路: 按枚举走分支 → 调 AI 拿代码(普通拿结果/流式拼字符串) → 解析+保存走两个 Executor → 返回目录或 Flux
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    @Resource
    private aiCodeGeneratorService aiCodeGeneratorService;

    private static final CodeFileSaverExecutor codeFileSaverExecutor = new CodeFileSaverExecutor();
    private static final CodeParserExecutor codeParserExecutor = new CodeParserExecutor();
    /**
     * 统一入口：根据类型生成并保存代码
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @return 保存的目录
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        return switch (codeGenTypeEnum) {
            case HTML -> {
                HtmlCodeResult result = aiCodeGeneratorService.generateCodeHTML(userMessage);
                yield codeFileSaverExecutor.execute(codeGenTypeEnum, result, appId);
            }
            case MULTI_FILE -> {
                MultiFileCodeResult result = aiCodeGeneratorService.generateCodeMultiFile(userMessage);
                yield codeFileSaverExecutor.execute(codeGenTypeEnum, result, appId);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new MyException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }


    /**
     * 统一入口：根据类型生成并保存代码(流式)
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @return 保存的目录
     */
    public Flux<String> generateAndSaveCodeStream (String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        return switch (codeGenTypeEnum) {
            case HTML -> generateAndSaveHtmlCodeStream(userMessage, appId);
            case MULTI_FILE -> generateAndSaveMultiFileCodeStream(userMessage, appId);
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new MyException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }












    /**
     * 根据代码类型和流式输出拼接代码
     * @param codeGenTypeEnum
     * @param result
     * @return
     */
    private Flux<String> processCodeStream(CodeGenTypeEnum codeGenTypeEnum, Flux<String> result, Long appId) {
        // 根据代码类型,拼接代码,并解析成对象保存
        StringBuilder CodeBuilder = new StringBuilder();
        return result.doOnNext(CodeBuilder::append)
                .doOnComplete(() -> {
                    try {
                        Object executeResult = codeParserExecutor.execute(codeGenTypeEnum, CodeBuilder.toString());
                        File file = codeFileSaverExecutor.execute(codeGenTypeEnum, executeResult, appId);
                        log.info("保存的目录: {}", file.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("生成代码失败: {}", e.getMessage());
                    }
                });
    }


    /**
     * 生成多文件模式的代码并保存(流式)
     * @param userMessage
     * @return
     */
    private Flux<String> generateAndSaveMultiFileCodeStream(String userMessage, Long appId) {
        // 1. 获取流式输出的复合代码(部分)   generate
        Flux<String> result = aiCodeGeneratorService.generateCodeMultiFileStream(userMessage);

        // 2. 创建StringBuilder拼接复合代码     save
        return processCodeStream(CodeGenTypeEnum.MULTI_FILE, result, appId);
    }

    /**
     * 生成 HTML 模式的代码并保存(流式)
     * @param userMessage
     * @return
     */
    private Flux<String> generateAndSaveHtmlCodeStream(String userMessage, Long appId) {
        // 1. 获取流式输出的HTML代码(部分)  generate
        Flux<String> result = aiCodeGeneratorService.generateCodeHTMLStream(userMessage);

        // 2. 创建StringBuilder拼接HTML代码   save
        return processCodeStream(CodeGenTypeEnum.HTML, result, appId);
    }
}
