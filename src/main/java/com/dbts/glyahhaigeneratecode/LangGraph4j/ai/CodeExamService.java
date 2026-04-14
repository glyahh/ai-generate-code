package com.dbts.glyahhaigeneratecode.LangGraph4j.ai;

import com.dbts.glyahhaigeneratecode.LangGraph4j.state.QualityResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 代码质量检查 AI 服务接口
 * <p>
 * 依据 {@code Prompt/code_exam.txt} 中的系统提示，对用户提供的网站代码做语法与质量检查，
 * 返回约定 JSON 格式的检查结果（由调用方解析为 {@link com.dbts.glyahhaigeneratecode.LangGraph4j.state.QualityResult}）。
 */
public interface CodeExamService {

    /**
     * 分析用户提供的网站代码（可为多文件拼接或单文件内容），输出 JSON 格式的检查结果。
     *
     * @param codeContent 待检查的代码或项目内容描述
     * @return 符合提示词约定格式的 JSON 字符串（含 isValid、errors、suggestions）
     */
    @SystemMessage(fromResource = "Prompt/code_exam.txt")
    @UserMessage("{{codeContent}}")
    QualityResult examineCode(@V("codeContent") String codeContent);
}
