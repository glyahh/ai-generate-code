package com.dbts.glyahhaigeneratecode.controller;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.rateLimiter.annotation.RateLimit;
import com.dbts.glyahhaigeneratecode.rateLimiter.enums.RateLimitType;
import com.dbts.glyahhaigeneratecode.service.ChatToGenCode;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 应用对话生成代码 控制层。
 * 提供基于应用的流式代码生成接口。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatToGenCodeController {

    private final ChatToGenCode chatToGenCodeService;
    private final UserService userService;

    /**
     * 【用户】根据应用 id 与用户输入，流式生成代码（SSE）
     * 仅应用创建人可调用；参数与权限在校验通过后调用门面流式生成并写回 SSE。
     *
     * @param
     * @param request              HTTP 请求（取登录用户）
     * @return SseEmitter，按片段推送生成的代码内容
     */
    @GetMapping(value = "/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60, message = "你先别急")
    //这个接口专门生成 text/event-stream 类型的响应内容, 浏览器会按照 SSE 协议来解析和处理这个响应，实现服务器向客户端的单向实时推送
    public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId,
                                                       @RequestParam String message,
                                                       HttpServletRequest request) {
        return toSseEvent(chatToGenCodeService.chatToGenCode(appId, message, getLoginUserWithValidation(appId, message, request)));
    }

    /**
     * 【用户】通过 workflow 编排流式生成代码（SSE）
     */
    @GetMapping(value = "/gen/workflow", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60, message = "你先别急")
    public Flux<ServerSentEvent<String>> chatToGenCodeByWorkflow(@RequestParam Long appId,
                                                                 @RequestParam String message,
                                                                 HttpServletRequest request) {
        return toSseEvent(chatToGenCodeService.chatToGenCodeByWorkflow(appId, message, getLoginUserWithValidation(appId, message, request)));
    }

    private User getLoginUserWithValidation(Long appId, String message, HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID无效");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        // 获取当前登录用户
        return userService.getUserInSession(request);
    }

    private Flux<ServerSentEvent<String>> toSseEvent(Flux<String> contentFlux) {
        // SSE 场景：必须保证异常时仍然以 text/event-stream 可写的方式结束；
        // 否则异常会被全局异常处理器捕获，尝试写普通 BaseResponse，导致
        // HttpMessageNotWritableException: No converter ... for 'text/event-stream'（二次报错）。
        Flux<String> safeContentFlux = contentFlux.onErrorResume(e -> {
            log.error("SSE 流式生成失败，将以错误文本+done 事件收尾。", e);
            // 不向前端透传底层 JSON 解析细节，避免暴露内部异常并减少用户困惑。
            return Flux.just("[生成失败] 代码生成流异常中断，请重试");
        });

        // 转换为 ServerSentEvent 格式
        return safeContentFlux
                .map(chunk -> ServerSentEvent.<String>builder()
                        // 这里event模型为"message"了
                        .data(chunk)
                        .build())
                .concatWith(Mono.just(
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()
                ));
    }
}
