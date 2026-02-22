package com.dbts.glyahhaigeneratecode.core;

import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorService;
import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
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
 * 大致思路:
 *
 * 外层根据枚举类获取生成类型👇
 *
 * 1.   (普通) : 根据用户输入的提示词，调用ai代码生成服务，获取代码类型
 *      (流式) : 根据用户输入的提示词，调用ai代码生成服务，拼接代码，获取代码类型
 * 3. 根据解析后的模型，保存代码
 * 4. 返回保存的目录/Flex<String>
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    @Resource
    private aiCodeGeneratorService aiCodeGeneratorService;

    /**
     * 统一入口：根据类型生成并保存代码
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @return 保存的目录
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum) {
        if (codeGenTypeEnum == null) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        return switch (codeGenTypeEnum) {
            case HTML -> generateAndSaveHtmlCode(userMessage);
            case MULTI_FILE -> generateAndSaveMultiFileCode(userMessage);
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
    public Flux<String> generateAndSaveCodeStream (String userMessage, CodeGenTypeEnum codeGenTypeEnum) {
        if (codeGenTypeEnum == null) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        return switch (codeGenTypeEnum) {
            case HTML -> generateAndSaveHtmlCodeStream(userMessage);
            case MULTI_FILE -> generateAndSaveMultiFileCodeStream(userMessage);
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new MyException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }


    /**
     * 生成多文件模式的代码并保存(流式)
     * @param userMessage
     * @return
     */
    private Flux<String> generateAndSaveMultiFileCodeStream(String userMessage) {
        // 1. 获取流式输出的复合代码(部分)
        Flux<String> result = aiCodeGeneratorService.generateCodeMultiFileStream(userMessage);

        // 2. 创建StringBuilder拼接复合代码
        StringBuilder htmlCodeBuilder = new StringBuilder();
        return result.doOnNext(htmlCodeBuilder::append)
                .doOnComplete(() -> {
                    try {
                        // 2.1 解析HTML代码
                        MultiFileCodeResult multiFileCodeResult = CodeParser.parseMultiFileCode(htmlCodeBuilder.toString());
                        File file = GenerateFileSaver.saveMultiFile(multiFileCodeResult);
                        log.info("保存的目录: {}", file.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("生成代码失败: {}", e.getMessage());
                    }
                });
    }

    /**
     * 生成 HTML 模式的代码并保存(流式)
     * @param userMessage
     * @return
     */
    private Flux<String> generateAndSaveHtmlCodeStream(String userMessage) {
        // 1. 获取流式输出的HTML代码(部分)
        Flux<String> result = aiCodeGeneratorService.generateCodeHTMLStream(userMessage);

        // 2. 创建StringBuilder拼接HTML代码
        StringBuilder htmlCodeBuilder = new StringBuilder();
        return result.doOnNext(htmlCodeBuilder::append)
                .doOnComplete(() -> {
                    try {
                        // 2.1 解析HTML代码
                        HtmlCodeResult htmlCodeResult = CodeParser.parseHtmlCode(htmlCodeBuilder.toString());
                        File file = GenerateFileSaver.saveHtmlFile(htmlCodeResult);
                        log.info("保存的目录: {}", file.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("生成代码失败: {}", e.getMessage());
                    }
                });
    }


    /**
     * 生成 HTML 模式的代码并保存
     *
     * @param userMessage 用户提示词
     * @return 保存的目录
     */
    private File generateAndSaveHtmlCode(String userMessage) {
        HtmlCodeResult result = aiCodeGeneratorService.generateCodeHTML(userMessage);
        return GenerateFileSaver.saveHtmlFile(result);
    }

    /**
     * 生成多文件模式的代码并保存
     *
     * @param userMessage 用户提示词
     * @return 保存的目录
     */
    private File generateAndSaveMultiFileCode(String userMessage) {
        MultiFileCodeResult result = aiCodeGeneratorService.generateCodeMultiFile(userMessage);
        return GenerateFileSaver.saveMultiFile(result);
    }
}
