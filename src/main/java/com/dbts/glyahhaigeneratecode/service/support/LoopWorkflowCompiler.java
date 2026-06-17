package com.dbts.glyahhaigeneratecode.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * workflowJson → compiledPrompt 编译器。
 * 遍历 steps[].content，过滤空值后按模板顺序拼接 Markdown 块。
 */
@Slf4j
@Component
public class LoopWorkflowCompiler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 将 workflowJson 编译为注入用的纯文本。
     * @param workflowJson JSON 字符串，可为 null 或非法
     * @return 编译后文本，无匹配时返回空字符串
     */
    public static String compile(String workflowJson) {
        if (workflowJson == null || workflowJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(workflowJson);
            JsonNode steps = root.get("steps");
            if (steps == null || !steps.isArray()) {
                return "";
            }
            List<String> blocks = new ArrayList<>();
            for (JsonNode step : steps) {
                String label = getTextOrEmpty(step, "label");
                String content = getTextOrEmpty(step, "content");
                if (content.isBlank()) continue;
                blocks.add("## " + label + "\n" + content);
            }
            return String.join("\n\n", blocks);
        } catch (Exception e) {
            log.warn("LoopWorkflowCompiler compile failed for json: {}", workflowJson, e);
            return "";
        }
    }

    private static String getTextOrEmpty(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return f != null ? f.asText("") : "";
    }
}
