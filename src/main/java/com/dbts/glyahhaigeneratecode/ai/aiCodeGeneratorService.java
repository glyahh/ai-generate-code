package com.dbts.glyahhaigeneratecode.ai;

import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

public interface aiCodeGeneratorService {
    /**
     * 返回用户提示词的输出单个HTML文件
     * @param appId 会话记忆 id，与文件工具 {@code @ToolMemoryId} 对齐
     * @param userMessage 用户输入
     * @return 解析结果
     */
    @SystemMessage(fromResource = "Prompt/Single_File_Prompt.txt")
    HtmlCodeResult generateCodeHTML(@MemoryId Long appId, @UserMessage String userMessage);


    /**
     * 返回用户提示词的输出多个文件
     * @param appId 会话记忆 id
     * @param userMessage 用户输入
     * @return 解析结果
     */
    @SystemMessage(fromResource = "Prompt/Various_File_Prompt.txt")
    MultiFileCodeResult generateCodeMultiFile(@MemoryId Long appId, @UserMessage String userMessage);



    /**
     * 返回用户提示词的输出单个HTML文件
     * @param appId 会话记忆 id
     * @param userMessage 用户输入
     * @return 流式片段
     */
    @SystemMessage(fromResource = "Prompt/Single_File_Prompt.txt")
    Flux<String> generateCodeHTMLStream(@MemoryId Long appId, @UserMessage String userMessage);


    /**
     * 返回用户提示词的输出多个文件
     * @param appId 会话记忆 id
     * @param userMessage 用户输入
     * @return 流式片段
     */
    @SystemMessage(fromResource = "Prompt/Various_File_Prompt.txt")
    Flux<String> generateCodeMultiFileStream(@MemoryId Long appId, @UserMessage String userMessage);

    /**
     * 返回用户提示词的输出单个HTML文件（TokenStream 流式）。
     * @param appId 会话记忆 id
     * @param userMessage 用户输入
     * @return TokenStream 流
     */
    @SystemMessage(fromResource = "Prompt/Single_File_Prompt.txt")
    TokenStream generateCodeHTMLTokenStream(@MemoryId Long appId, @UserMessage String userMessage);

    /**
     * 返回用户提示词的输出多个文件（TokenStream 流式）。
     * @param appId 会话记忆 id
     * @param userMessage 用户输入
     * @return TokenStream 流
     */
    @SystemMessage(fromResource = "Prompt/Various_File_Prompt.txt")
    TokenStream generateCodeMultiFileTokenStream(@MemoryId Long appId, @UserMessage String userMessage);


    /**
     * 使用流式输出返回Vue文件
     * @param appId  交给ai记忆
     * @param userMessage
     * @return
     */
    @SystemMessage(fromResource = "Prompt/Vue_File_Prompt.txt")
    TokenStream generateCodeVueFileStream(@MemoryId Long appId, @UserMessage String userMessage);
}
