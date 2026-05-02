package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.Entity.User;
import reactor.core.publisher.Flux;

public interface ChatToGenCode {
    /**
     * 统一入口：基于应用配置和用户输入触发代码生成（流式）
     *
     * @param appId   应用 id
     * @param message 用户输入内容
     * @param user    当前登录用户
     * @return 代码内容的流式输出
     */
    Flux<String> chatToGenCode(Long appId, String message, User user);

    /**
     * workflow 入口：复用现有会话与权限链路，通过工作流编排生成代码（流式）
     *
     * @param appId   应用 id
     * @param message 用户输入内容
     * @param user    当前登录用户
     * @return 代码内容的流式输出
     */
    Flux<String> chatToGenCodeByWorkflow(Long appId, String message, User user);
}
