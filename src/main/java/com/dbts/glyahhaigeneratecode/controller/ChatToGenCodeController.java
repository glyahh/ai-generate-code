package com.dbts.glyahhaigeneratecode.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
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

import java.util.Map;


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
    //这个接口专门生成 text/event-stream 类型的响应内容, 浏览器会按照 SSE 协议来解析和处理这个响应，实现服务器向客户端的单向实时推送
    public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId,
                                                       @RequestParam String message,
                                                       HttpServletRequest request) {
        // 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID无效");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        // 获取当前登录用户
        User loginUser = userService.getUserInSession(request);
        // 调用服务生成代码（流式）
        Flux<String> contentFlux = chatToGenCodeService.chatToGenCode(appId, message, loginUser);
        // 转换为 ServerSentEvent 格式
        return contentFlux
                .map(chunk -> {
                    Map<String, String> wrapper = Map.of("d", chunk);
                    String jsonData = JSONUtil.toJsonStr(wrapper);
                    return ServerSentEvent.<String>builder()
                            .data(jsonData)
                            .build();
                })
                .concatWith(Mono.just(
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()
                ));

    }
}
