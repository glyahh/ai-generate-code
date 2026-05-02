package com.dbts.glyahhaigeneratecode.LangGraph4j.node;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.LangGraph4j.model.ImageResource;
import com.dbts.glyahhaigeneratecode.LangGraph4j.state.WorkflowContext;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.util.List;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 把图片内容拼接到本来的普通提示词中去一起更好的生成网站
 */
@Slf4j
public class PromptEnhancerNode {

    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 提示词增强");
            // 获取原始提示词和图片列表
            String originalPrompt = context.getOriginalPrompt();
            String imageListStr = context.getImageListStr();
            List<ImageResource> imageList = context.getImageList();
            // 构建增强后的提示词
            StringBuilder enhancedPromptBuilder = new StringBuilder();
            enhancedPromptBuilder.append(originalPrompt);
            // 如果有图片资源，则添加图片信息
            if (CollUtil.isNotEmpty(imageList) || StrUtil.isNotBlank(imageListStr)) {
                enhancedPromptBuilder.append("\n\n## 可用素材资源\n");
                enhancedPromptBuilder.append("请在生成网站使用以下图片资源，将这些图片合理地嵌入到网站的相应位置中。\n");
                // 两种拼接逻辑
                if (CollUtil.isNotEmpty(imageList)) {
                    // 防止提示词过长触发输入护栏（当前护栏要求 < 1000 字）。
                    // 这里采用“边拼接边限长”的策略：优先保留原始提示词，其次保留少量高价值素材条目。
                    int maxChars = 900;
                    int maxItems = 8;
                    int appended = 0;
                    for (ImageResource image : imageList) {
                        if (appended >= maxItems) {
                            break;
                        }
                        String line = "- "
                                + image.getCategory().getText()
                                + "："
                                + image.getDescription()
                                + "（"
                                + image.getUrl()
                                + "）\n";
                        if (enhancedPromptBuilder.length() + line.length() > maxChars) {
                            break;
                        }
                        enhancedPromptBuilder.append("- ")
                                .append(image.getCategory().getText())
                                .append("：")
                                .append(image.getDescription())
                                .append("（")
                                .append(image.getUrl())
                                .append("）\n");
                        appended++;
                    }
                } else {
                    int maxChars = 900;
                    if (enhancedPromptBuilder.length() + imageListStr.length() <= maxChars) {
                        enhancedPromptBuilder.append(imageListStr);
                    }
                }
            }
            String enhancedPrompt = enhancedPromptBuilder.toString();
            // 更新状态
            context.setCurrentStep("提示词增强");
            context.setEnhancedPrompt(enhancedPrompt);
            log.info("提示词增强完成，增强后长度: {} 字符", enhancedPrompt.length());
            return WorkflowContext.saveContext(context);
        });
    }
}
