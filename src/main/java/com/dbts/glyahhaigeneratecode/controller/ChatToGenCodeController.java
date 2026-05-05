package com.dbts.glyahhaigeneratecode.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatToGenCodeController {

    private static final Pattern WORKFLOW_STEP_PATTERN =
            Pattern.compile("\\[workflow\\]\\s*第\\s*(\\d+)\\s*步完成[：:]\\s*([^\\r\\n\\[]+)");

    private final ChatToGenCode chatToGenCodeService;
    private final UserService userService;

    @GetMapping(value = "/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60, message = "你先别急")
    public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId,
                                                       @RequestParam String message,
                                                       HttpServletRequest request) {
        return toSseEvent(chatToGenCodeService.chatToGenCode(appId, message, getLoginUserWithValidation(appId, message, request)));
    }

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
        return userService.getUserInSession(request);
    }

    private Flux<ServerSentEvent<String>> toSseEvent(Flux<String> contentFlux) {
        Flux<String> safeContentFlux = contentFlux.onErrorResume(e -> {
            log.error("SSE 流式生成失败，将以错误文本+done事件收尾。", e);
            return Flux.just("[生成失败] 代码生成流异常中断，请重试。");
        });

        return safeContentFlux
                .flatMapIterable(this::toSseEvents)
                .concatWith(Mono.just(
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()
                ));
    }

    private List<ServerSentEvent<String>> toSseEvents(String chunk) {
        List<ServerSentEvent<String>> events = new ArrayList<>();
        if (chunk == null) {
            return events;
        }

        Matcher matcher = WORKFLOW_STEP_PATTERN.matcher(chunk);
        while (matcher.find()) {
            Integer step = null;
            try {
                step = Integer.parseInt(matcher.group(1));
            } catch (Exception ignored) {
                // keep compatibility text event even if parse failed
            }
            String label = matcher.group(2) == null ? "" : matcher.group(2).trim();
            if (step != null && !label.isBlank()) {
                String payload = JSONUtil.createObj()
                        .set("step", step)
                        .set("label", label)
                        .toString();
                events.add(ServerSentEvent.<String>builder()
                        .event("workflow-step")
                        .data(payload)
                        .build());
            }
        }

        events.add(ServerSentEvent.<String>builder()
                .data(chunk)
                .build());
        return events;
    }
}
