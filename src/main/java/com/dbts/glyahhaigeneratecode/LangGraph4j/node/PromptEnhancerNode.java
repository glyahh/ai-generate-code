package com.dbts.glyahhaigeneratecode.LangGraph4j.node;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.LangGraph4j.enums.ImageCategoryEnum;
import com.dbts.glyahhaigeneratecode.LangGraph4j.model.ImageResource;
import com.dbts.glyahhaigeneratecode.LangGraph4j.state.WorkflowContext;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.util.ArrayList;
import java.util.List;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 把图片内容拼接到本来的普通提示词中去一起更好的生成网站
 */
@Slf4j
public class PromptEnhancerNode {
    private static final int MAX_CHARS = 2200;
    private static final int MAX_PIXEL_IMAGES = 7;
    private static final int MAX_IMAGE20_IMAGES = 2;
    private static final int MAX_MERMAID_IMAGES = 1;

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
                    int appendedCount = appendImageResourcesWithQuota(enhancedPromptBuilder, imageList);
                    log.info("提示词增强资源写入完成，计划上限={}, 实际写入={}", MAX_PIXEL_IMAGES + MAX_IMAGE20_IMAGES + MAX_MERMAID_IMAGES, appendedCount);
                } else {
                    if (enhancedPromptBuilder.length() + imageListStr.length() <= MAX_CHARS) {
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

    private static int appendImageResourcesWithQuota(StringBuilder enhancedPromptBuilder, List<ImageResource> imageList) {
        List<ImageResource> pixel = new ArrayList<>();
        List<ImageResource> image20 = new ArrayList<>();
        List<ImageResource> mermaid = new ArrayList<>();
        for (ImageResource image : imageList) {
            if (image == null || image.getCategory() == null) {
                continue;
            }
            if (image.getCategory() == ImageCategoryEnum.ARCHITECTURE) {
                mermaid.add(image);
            } else if (image.getCategory() == ImageCategoryEnum.LOGO) {
                image20.add(image);
            } else {
                pixel.add(image);
            }
        }

        // 先确保类别覆盖：每类最多先写 1 条（必要时自动降级为短描述）
        int pixelOffset = 0;
        int image20Offset = 0;
        int mermaidOffset = 0;
        int appendedCount = 0;
        if (!pixel.isEmpty() && tryAppendImageLine(enhancedPromptBuilder, pixel.get(0), true)) {
            pixelOffset = 1;
            appendedCount++;
        }
        if (!image20.isEmpty() && tryAppendImageLine(enhancedPromptBuilder, image20.get(0), true)) {
            image20Offset = 1;
            appendedCount++;
        }
        if (!mermaid.isEmpty() && tryAppendImageLine(enhancedPromptBuilder, mermaid.get(0), true)) {
            mermaidOffset = 1;
            appendedCount++;
        }

        // 再按配额追加剩余条目
        appendedCount += appendByCategory(enhancedPromptBuilder, pixel, pixelOffset, MAX_PIXEL_IMAGES - pixelOffset);
        appendedCount += appendByCategory(enhancedPromptBuilder, image20, image20Offset, MAX_IMAGE20_IMAGES - image20Offset);
        appendedCount += appendByCategory(enhancedPromptBuilder, mermaid, mermaidOffset, MAX_MERMAID_IMAGES - mermaidOffset);
        return appendedCount;
    }

    private static int appendByCategory(StringBuilder sb, List<ImageResource> images, int startIndex, int maxItems) {
        if (CollUtil.isEmpty(images) || maxItems <= 0 || startIndex >= images.size()) {
            return 0;
        }
        int appended = 0;
        for (int i = startIndex; i < images.size(); i++) {
            ImageResource image = images.get(i);
            if (appended >= maxItems) {
                break;
            }
            if (!tryAppendImageLine(sb, image, true)) {
                break;
            }
            appended++;
        }
        return appended;
    }

    private static boolean tryAppendImageLine(StringBuilder sb, ImageResource image, boolean preferCompact) {
        String category = image.getCategory() == null ? "图片" : image.getCategory().getText();
        String description = StrUtil.blankToDefault(image.getDescription(), "-");
        String url = StrUtil.blankToDefault(image.getUrl(), "-");
        String shortDescription = description.length() > 28 ? description.substring(0, 28) + "..." : description;

        String[] candidates = preferCompact
                ? new String[]{
                "- " + category + "：" + shortDescription + "（" + url + "）\n",
                "- " + category + "：" + url + "\n",
                "- " + category + "：资源可用\n"
        }
                : new String[]{
                "- " + category + "：" + description + "（" + url + "）\n",
                "- " + category + "：" + shortDescription + "（" + url + "）\n",
                "- " + category + "：" + url + "\n"
        };

        for (String candidate : candidates) {
            if (sb.length() + candidate.length() <= MAX_CHARS) {
                sb.append(candidate);
                return true;
            }
        }
        return false;
    }
}
