package com.dbts.glyahhaigeneratecode.ai.tool;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具管理器
 * 统一管理所有工具，提供根据名称获取工具的功能
 */
@Slf4j
@Component
public class ToolManager {

    /**
     * 工具名称到工具实例的映射
     */
    private final Map<String, BaseTool> toolMap = new HashMap<>();

    /**
     * 自动注入所有工具
     */
    @Resource
    private BaseTool[] tools;

    /**
     * 初始化工具映射
     */
    @PostConstruct
    // 在 Bean 初始化完成后、依赖注入完成后，自动执行一次 标注的方法
    public void initTools() {
        for (BaseTool tool : tools) {
            toolMap.put(tool.getToolName(), tool);
            log.info("注册工具: {} -> {}", tool.getToolName(), tool.getDisplayName());
        }
        log.info("工具管理器初始化完成，共注册 {} 个工具", toolMap.size());
    }

    /**
     * 根据工具名称获取工具实例
     *
     * @param toolName 工具英文名称
     * @return 工具实例
     */
    public BaseTool getTool(String toolName) {
        return toolMap.get(toolName);
    }

    /**
     * 获取已注册的工具集合
     *
     * @return 工具实例集合
     */
    public BaseTool[] getAllTools() {
        return tools;
    }

    /**
     * 首轮对话仅允许写文件，降低误调用 read/modify/delete 带来的副作用风险。
     */
    public BaseTool[] getWriteFileOnlyTools() {
        BaseTool writeFileTool = toolMap.get("writeFile");
        if (writeFileTool == null) {
            log.warn("writeFile 工具未注册");
            ThrowUtils.throwIf(true,  ErrorCode.OPERATION_ERROR, "writeFile 工具未注册");
        }
        return new BaseTool[]{writeFileTool};
    }
}
