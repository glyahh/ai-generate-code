package com.dbts.glyahhaigeneratecode.ai;

import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.SystemMessage;
import reactor.core.publisher.Flux;

public interface aiCodeGeneratorService {
    /**
     * 返回用户提示词的输出单个HTML文件
     * @param userMessage
     * @return
     */
    @SystemMessage(fromResource = "Prompt/Single_File_Prompt.txt")
    HtmlCodeResult generateCodeHTML(String userMessage);


    /**
     * 返回用户提示词的输出多个文件
     * @param userMessage
     * @return
     */
    @SystemMessage(fromResource = "Prompt/Various_File_Prompt.txt")
    MultiFileCodeResult generateCodeMultiFile(String userMessage);



    /**
     * 返回用户提示词的输出单个HTML文件
     * @param userMessage
     * @return
     */
    @SystemMessage(fromResource = "Prompt/Single_File_Prompt.txt")
    Flux<String> generateCodeHTMLStream(String userMessage);


    /**
     * 返回用户提示词的输出多个文件
     * @param userMessage
     * @return
     */
    @SystemMessage(fromResource = "Prompt/Various_File_Prompt.txt")
    Flux<String> generateCodeMultiFileStream(String userMessage);
}
