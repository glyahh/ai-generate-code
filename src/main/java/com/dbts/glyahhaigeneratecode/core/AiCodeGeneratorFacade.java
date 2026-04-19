package com.dbts.glyahhaigeneratecode.core;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorService;
import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorServiceFactory;
import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.message.AiResponseMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolExecutedMessage;
import com.dbts.glyahhaigeneratecode.ai.model.message.ToolRequestMessage;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.core.Builder.vueProjectBuilder;
import com.dbts.glyahhaigeneratecode.core.context.HtmlMultiFileEditContextBuilder;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 代码生成外观类，组合生成和保存功能
 * 门面类(工具类)
 * 大致思路: 按枚举走分支 → 调 AI 拿代码(普通拿结果/流式拼字符串) → 解析+保存走两个 Executor → 返回目录或 Flux
 *
 * 这是整个项目的灵魂根源
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    @Resource
    private aiCodeGeneratorService aiCodeGeneratorService;

    @Resource
    private aiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Resource
    private HtmlMultiFileEditContextBuilder htmlMultiFileEditContextBuilder;

    @Resource
    private vueProjectBuilder vueProjectBuilder;

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
     * @param userMessage     用户提示词/
     *
     *
     *
     * enTypeEnum 生成类型
     * @return 保存的目录
     */
    public Flux<String> generateAndSaveCodeStream (String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        return generateAndSaveCodeStream(userMessage, codeGenTypeEnum, appId, false);
    }

    /**
     * 统一入口：根据类型生成并保存代码(流式)（可选首轮工具白名单）
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId, boolean firstRound) {
        if (codeGenTypeEnum == null) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }

        return switch (codeGenTypeEnum) {
            case HTML -> generateAndSaveHtmlCodeStream(userMessage, appId);
            case MULTI_FILE -> generateAndSaveMultiFileCodeStream(userMessage, appId);
            case VUE -> generateAndSaveVueCodeStream(userMessage, appId, firstRound);
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
                        // 拿到代码的类型(Html这种)
                        Object executeResult = codeParserExecutor.execute(codeGenTypeEnum, CodeBuilder.toString());
                        // 根据类型保存代码
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
        // 仅在“修改意图 + 已有文件”时附加片段上下文；否则保持原消息不变
        String finalPrompt = htmlMultiFileEditContextBuilder.buildPromptIfNeed(userMessage, CodeGenTypeEnum.MULTI_FILE, appId);
        Flux<String> result = aiCodeGeneratorService.generateCodeMultiFileStream(finalPrompt);

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
        // 构造提示词    (压缩ai对话历史)
        String finalPrompt = htmlMultiFileEditContextBuilder.buildPromptIfNeed(userMessage, CodeGenTypeEnum.HTML, appId);
        Flux<String> result = aiCodeGeneratorService.generateCodeHTMLStream(finalPrompt);

        // 2. 创建StringBuilder拼接HTML代码   save
        return processCodeStream(CodeGenTypeEnum.HTML, result, appId);
    }

    /**
     * 创建Vue文件并保存(流式)
     * @param userMessage
     * @return
     */
    private Flux<String> generateAndSaveVueCodeStream(String userMessage, Long appId, boolean firstRound) {
        // 1. 获取流式输出的Vue代码(部分)  generate
        // 获取 AI 服务实例
        aiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, CodeGenTypeEnum.VUE, firstRound);
        TokenStream tokenStream = aiCodeGeneratorService.generateCodeVueFileStream(appId, userMessage);

        // 2. 返回拼接好的Vue+工具请求代码（流结束后在 onCompleteResponse 内同步打包，再 complete）
        return turnTokenStreamToFlex(tokenStream, appId);
    }


    /**
     * 将工具调用的TokenStream转化成Flux<String>发往前端
     *
     * 将TokenStream转换为Flux  适配器
     * @param tokenStream
     * @return
     */
    private Flux<String> turnTokenStreamToFlex(TokenStream tokenStream, Long appId) {
        // 将 onPartialToolExecutionRequest 的 arguments 片段累积成完整 JSON（用于提前合成 TOOL_EXECUTED）
        Map<String, StringBuilder> toolArgsById = new HashMap<>();
        Set<String> syntheticExecutedIds = new HashSet<>();
        // 兼容两类模型(因为有的模型不支持onToolExecuted回调,后端无法知晓什么时候工具调用结束)：
        // 1) 无 onToolExecuted：持续走 synthetic
        // 2) 有 onToolExecuted：首次真实回包后切换到 native 模式（首包若重复则丢弃）
        AtomicBoolean nativeToolExecutedMode = new AtomicBoolean(false);

        // 响应式编程
        return Flux.<String>create(sink -> {
                    // AI回复
                    tokenStream.onPartialResponse((String partialResponse) -> {
                        AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                        // 将ai普通恢复转换成Json格式
                        sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                    })
                    // 工具执行请求
                    .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                        ToolRequestMessage toolRequestMessage = new ToolRequestMessage(toolExecutionRequest);
                        sink.next(JSONUtil.toJsonStr(toolRequestMessage));

                        // 尝试把零碎 arguments 累积成完整 JSON，一旦完整就提前发出 TOOL_EXECUTED
                        try {
                            String toolCallId = toolExecutionRequest.id();
                            String toolName = toolExecutionRequest.name();
                            String argsPart = toolExecutionRequest.arguments();
                            if (!nativeToolExecutedMode.get()
                                    && toolCallId != null
                                    && toolName != null
                                    && argsPart != null
                                    && !syntheticExecutedIds.contains(toolCallId)) {
                                StringBuilder buf = toolArgsById.computeIfAbsent(toolCallId, k -> new StringBuilder());
                                buf.append(argsPart);
                                // 仅对 writeFile 做提前合成（其它工具保持原样）
                                if ("writeFile".equals(toolName)) {
                                    String bufStr = buf.toString();
                                    JSONObject obj;
                                    try {
                                        obj = JSONUtil.parseObj(bufStr);
                                    } catch (Exception ignore) {
                                        obj = null;
                                    }
                                    if (obj != null) {
                                        String relativeFilePath = obj.getStr("relativeFilePath");
                                        String content = obj.getStr("content");
                                        if (relativeFilePath != null && content != null) {
                                            syntheticExecutedIds.add(toolCallId);
                                            // 合成 ToolExecutedMessage JSON，让 JsonMessageStreamHandler 走同一条渲染路径
                                            Map<String, Object> synthetic = new HashMap<>();
                                            synthetic.put("type", "tool_executed");
                                            synthetic.put("id", toolCallId);
                                            synthetic.put("name", toolName);
                                            synthetic.put("arguments", JSONUtil.toJsonStr(obj));
                                            synthetic.put("result", "");
                                            sink.next(JSONUtil.toJsonStr(synthetic));
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignore) {
                            // ignore
                        }
                    })
                    // 工具执行完成
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        // 一旦检测到真实 onToolExecuted，后续统一走 native 模式。
                        // 若当前这一条已被 synthetic 提前发出，则仅丢弃这第一条重复回包。
                        try {
                            String toolCallId = toolExecution.request().id();
                            boolean switched = nativeToolExecutedMode.compareAndSet(false, true);
                            if (switched && toolCallId != null && syntheticExecutedIds.contains(toolCallId)) {
                                return;
                            }
                        } catch (Exception ignore) {
                            // ignore
                        }
                        ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                        sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
                    })
                    // TODO:深度思考
//                    .onPartialThinking((String partialThinking) -> {
//                        AiThinkingMessage aiThinkingMessage = new AiThinkingMessage(partialThinking);
//                        sink.next(JSONUtil.toJsonStr(aiThinkingMessage));
//                    })
                    // 响应完成：先同步 npm 构建，再结束 Flux，避免前端刚收完流就探测 dist 仍不存在
                    .onCompleteResponse((ChatResponse response) -> {
                        try {
                            String projectDirName = "vue_project_" + appId;
                            Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName);
                            String path = projectRoot.toString();
                            boolean ok = vueProjectBuilder.buildProject(path);
                            if (!ok) {
                                log.warn("Vue 项目构建未成功，预览可能不可用。appId={} path={}", appId, path);
                            }
                        } catch (Exception e) {
                            log.error("Vue 项目构建异常。appId={}", appId, e);
                        }
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
