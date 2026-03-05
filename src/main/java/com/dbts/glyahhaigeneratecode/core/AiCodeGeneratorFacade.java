package com.dbts.glyahhaigeneratecode.core;

import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorService;
import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorServiceFactory;
import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.message.AiResponseMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolExecutedMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolRequestMessage;
import com.dbts.glyahhaigeneratecode.core.parser.CodeParserExecutor;
import com.dbts.glyahhaigeneratecode.core.saver.CodeFileSaverExecutor;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;

/**
 * AI 代码生成外观类，组合生成和保存功能
 * 门面类(工具类)
 * 大致思路: 按枚举走分支 → 调 AI 拿代码(普通拿结果/流式拼字符串) → 解析+保存走两个 Executor → 返回目录或 Flux
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    @Resource
    private aiCodeGeneratorService aiCodeGeneratorService;

    @Resource
    private aiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

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

        // 获取 AI 服务实例
        aiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);

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
            case VUE -> generateAndSaveVueCodeStream(userMessage, appId);
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new MyException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }












    /**
     * 根据代码类型和流式输出拼接代码,并保存到本地
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
        // 获取 AI 服务实例
        aiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, CodeGenTypeEnum.MULTI_FILE);
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
        // 获取 AI 服务实例
        aiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML);
        Flux<String> result = aiCodeGeneratorService.generateCodeHTMLStream(userMessage);

        // 2. 创建StringBuilder拼接HTML代码   save
        return processCodeStream(CodeGenTypeEnum.HTML, result, appId);
    }

    /**
     * 创建Vue文件并保存(流式)
     * @param userMessage
     * @return
     */
    private Flux<String> generateAndSaveVueCodeStream(String userMessage, Long appId) {
        // 1. 获取流式输出的Vue代码(部分)  generate
        // 获取 AI 服务实例
        aiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, CodeGenTypeEnum.VUE);
        TokenStream tokenStream = aiCodeGeneratorService.generateCodeVueFileStream(appId, userMessage);

        // 2. 返回拼接好的Vue+工具请求代码
        return turnTokenStreamToFlex(tokenStream);
    }


    /**
     * 将TokenStream转换为Flux  适配器
     * @param tokenStream
     * @return
     */
    private Flux<String> turnTokenStreamToFlex(TokenStream tokenStream) {
        // 响应式编程
        return Flux.create(sink -> {
                    // AI回复
            tokenStream.onPartialResponse((String partialResponse) -> {
                        AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                        sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                    })
                    // 工具执行请求
                    .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                        ToolRequestMessage toolRequestMessage = new ToolRequestMessage(toolExecutionRequest);
                        sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                    })
                    // 工具执行完成
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                        sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
                    })
                    // 响应完成
                    .onCompleteResponse((ChatResponse response) -> {
                        sink.complete();
                    })
                    .onError((Throwable error) -> {
                        error.printStackTrace();
                        sink.error(error);
                    })
                    .start();
        });
    }



}
