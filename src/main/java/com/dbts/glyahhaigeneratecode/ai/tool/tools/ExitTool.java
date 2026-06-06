package com.dbts.glyahhaigeneratecode.ai.tool.tools;

import cn.hutool.json.JSONObject;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ExitTool extends BaseTool {

    @Override
    public String getToolName() {
        return "exit";
    }

    @Override
    public String getDisplayName() {
        return "结束工具调用";
    }

    /**
     * 退出工具调用
     * 当任务完成或无需继续使用工具时调用此方法
     *
     * @return 退出确认信息
     */
    @Tool("当任务已完成或无需继续调用工具时，使用此工具退出操作，防止循环")
    public String exit() {
        log.info("AI 请求退出工具调用");
        return "不要继续调用工具, 针对用户的需求和工具调用的效果做一小段对整个修改的总结";
    }

    /**
     * 不向 SSE/聊天正文追加「执行结束」类占位；模型侧仍以 {@link #exit()} 返回值为准。
     */
    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return "";
    }
}
