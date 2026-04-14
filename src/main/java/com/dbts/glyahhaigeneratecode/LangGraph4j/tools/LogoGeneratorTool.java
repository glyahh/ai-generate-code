package com.dbts.glyahhaigeneratecode.LangGraph4j.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.dbts.glyahhaigeneratecode.LangGraph4j.enums.ImageCategoryEnum;
import com.dbts.glyahhaigeneratecode.LangGraph4j.model.ImageResource;
import com.dbts.glyahhaigeneratecode.manage.OssManager;
import com.dbts.glyahhaigeneratecode.utils.WebScreenShotUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class LogoGeneratorTool {

    // ======================================================================
    // 配置 & 依赖
    // ======================================================================

    /**
     * Qwen Image 2.0 系列使用该 HTTP 端点最稳定（官方文档：千问-文生图）。
     * 北京地域：dashscope.aliyuncs.com
     */
    private static final String DASHSCOPE_QWEN_IMAGE_GENERATION_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    @Value("${dashscope.api-key}")
    private String dashScopeApiKey;

    // 默认使用 Qwen Image 2 Pro；如需切换可通过 dashscope.image-model 覆盖
    @Value("${dashscope.image-model}")
    private String imageModel;

    @Resource
    private OssManager ossManager;

    // ======================================================================
    // 对外工具方法
    // ======================================================================

    @Tool("根据描述生成 Logo 设计图片，用于网站品牌标识")
    public List<ImageResource> generateLogos(@P("Logo 设计描述，如名称、行业、风格等，尽量详细") String description) {
        List<ImageResource> logoList = new ArrayList<>();
        if (StrUtil.isBlank(description)) {
            return logoList;
        }
        try {
            // 构建 Logo 设计提示词
            String logoPrompt = String.format("生成 Logo，Logo 中禁止包含任何文字！Logo 介绍：%s", description.trim());

            // 走 HTTP multimodal-generation 端点，返回中取 output.choices[].message.content[].image。
            if (StrUtil.startWithIgnoreCase(imageModel, "qwen-image-2.0")) {
                logoList.addAll(callQwenImage2ByHttp(imageModel, logoPrompt, description));
                log.info("使用qwen-image-2.0系列模型~~~");
                return logoList;
            }

            // 其他模型仍沿用 SDK（例如 wanx 系列）
            ImageSynthesisParam param = ImageSynthesisParam.builder()
                    .apiKey(dashScopeApiKey)
                    .model(imageModel)
                    .prompt(logoPrompt)
                    // DashScope 图像生成接口通常只接受固定枚举尺寸；512*512 容易触发 InvalidParameter
                    .size("1024*1024")
                    .n(1) // 生成 1 张足够，因为 AI 不知道哪张最好
                    .build();

            ImageSynthesis imageSynthesis = new ImageSynthesis();
            ImageSynthesisResult result = imageSynthesis.call(param);
            if (result != null && result.getOutput() != null && result.getOutput().getResults() != null) {
                List<Map<String, String>> results = result.getOutput().getResults();
                for (Map<String, String> imageResult : results) {
                    String imageUrl = imageResult.get("url");
                    if (StrUtil.isNotBlank(imageUrl)) {
                        String ossUrl = uploadLogoScreenshotToOss(imageUrl, description);
                        logoList.add(ImageResource.builder()
                                .category(ImageCategoryEnum.LOGO)
                                .description(description)
                                .url(StrUtil.blankToDefault(ossUrl, imageUrl))
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            log.error("生成 Logo 失败: {}", e.getMessage(), e);
        }
        return logoList;
    }

    // ======================================================================
    // Qwen Image 2.0（HTTP 端点）
    // ======================================================================

    private List<ImageResource> callQwenImage2ByHttp(String model, String prompt, String description) {
        List<ImageResource> logoList = new ArrayList<>();

        JSONArray reqContent = new JSONArray().put(JSONUtil.createObj().set("text", prompt));
        JSONArray messages = new JSONArray().put(JSONUtil.createObj()
                .set("role", "user")
                .set("content", reqContent));
        JSONObject body = JSONUtil.createObj()
                .set("model", model)
                .set("input", JSONUtil.createObj().set("messages", messages))
                .set("parameters", JSONUtil.createObj()
                        .set("n", 1)
                        .set("size", "1024*1024")
                        .set("prompt_extend", false)
                        .set("watermark", false));

        try (HttpResponse resp = HttpRequest.post(DASHSCOPE_QWEN_IMAGE_GENERATION_URL)
                .header("Authorization", "Bearer " + dashScopeApiKey)
                .header("Content-Type", "application/json")
                .timeout(30000)
                .body(body.toString())
                .execute()) {
            if (!resp.isOk()) {
                log.warn("Qwen Image 2 HTTP 调用失败: status={}, body={}", resp.getStatus(), resp.body());
                return logoList;
            }
            JSONObject json = JSONUtil.parseObj(resp.body());
            JSONObject output = json.getJSONObject("output");
            if (output == null) {
                return logoList;
            }
            JSONArray choices = output.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return logoList;
            }
            for (Object c : choices) {
                JSONObject choice = (JSONObject) c;
                JSONObject message = choice.getJSONObject("message");
                if (message == null) {
                    continue;
                }
                JSONArray content = message.getJSONArray("content");
                if (content == null || content.isEmpty()) {
                    continue;
                }
                for (Object item : content) {
                    JSONObject contentItem = (JSONObject) item;
                    String imageUrl = contentItem.getStr("image");
                    if (StrUtil.isNotBlank(imageUrl)) {
                        String ossUrl = uploadLogoScreenshotToOss(imageUrl, description);
                        logoList.add(ImageResource.builder()
                                .category(ImageCategoryEnum.LOGO)
                                .description(description)
                                .url(StrUtil.blankToDefault(ossUrl, imageUrl))
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Qwen Image 2 HTTP 调用异常: {}", e.getMessage(), e);
        }
        return logoList;
    }

    // ======================================================================
    // OSS 存储（本地落盘 -> 上传 -> 删除）
    // ======================================================================

    /**
     * 将生成结果图片保存到本地 temp/Logo/...，上传 OSS，最后删除本地文件。
     *
     * @return OSS URL（失败返回 null）
     */
    private String uploadLogoScreenshotToOss(String imageUrl, String description) {
        String subjectFolder = buildLogoSubjectFolder(description);
        String fileName = UUID.randomUUID().toString().substring(0, 8) + "_" + RandomUtil.randomNumbers(4) + ".png";
        String keyName = String.format("/Logo/%s/%s", subjectFolder, fileName);
        String localImagePath = buildLocalTempPathByOssKey(keyName);
        try {
            boolean prepared = prepareLocalLogoFile(imageUrl, localImagePath);
            if (!prepared) {
                log.warn("Logo 下载与截图均失败，imageUrl={}", imageUrl);
                return null;
            }
            File localImageFile = new File(localImagePath);
            if (!localImageFile.exists()) {
                log.warn("Logo 本地文件不存在: {}", localImagePath);
                return null;
            }
            String ossUrl = ossManager.uploadFile(keyName, localImageFile);
            if (StrUtil.isBlank(ossUrl)) {
                log.warn("Logo 上传 OSS 失败，keyName={}", keyName);
            }
            return ossUrl;
        } catch (Exception e) {
            log.error("Logo 上传异常: {}", e.getMessage(), e);
            return null;
        } finally {
            File localImageFile = new File(localImagePath);
            if (localImageFile.exists()) {
                FileUtil.del(localImageFile);
                File parent = localImageFile.getParentFile();
                if (parent != null && FileUtil.isDirEmpty(parent)) {
                    FileUtil.del(parent);
                }
            }
        }
    }

    /**
     * 将 OSS key 映射为本地临时路径：temp + /Logo/xxx.png
     */
    private String buildLocalTempPathByOssKey(String keyName) {
        String relativePath = keyName.startsWith("/") ? keyName.substring(1) : keyName;
        File localFile = FileUtil.file("temp", relativePath);
        FileUtil.mkdir(localFile.getParentFile());
        return localFile.getAbsolutePath();
    }

    /**
     * 优先下载原图到指定本地路径；下载失败时回退截图并搬运到指定路径。
     */
    private boolean prepareLocalLogoFile(String imageUrl, String targetLocalPath) {
        try (HttpResponse response = HttpRequest.get(imageUrl)
                .timeout(30000)
                .execute()) {
            if (!response.isOk()) {
                log.warn("下载 Logo 原图失败: status={}, url={}", response.getStatus(), imageUrl);
                return fallbackScreenshotToTarget(imageUrl, targetLocalPath);
            }
            byte[] bytes = response.bodyBytes();
            if (bytes == null || bytes.length == 0) {
                log.warn("下载 Logo 原图为空: url={}", imageUrl);
                return fallbackScreenshotToTarget(imageUrl, targetLocalPath);
            }
            FileUtil.writeBytes(bytes, targetLocalPath);
            return true;
        } catch (Exception e) {
            log.warn("下载 Logo 原图异常: {}, url={}", e.getMessage(), imageUrl);
            return fallbackScreenshotToTarget(imageUrl, targetLocalPath);
        }
    }

    /**
     * 截图回退：将截图结果移动到目标路径（temp/Logo/...）。
     */
    private boolean fallbackScreenshotToTarget(String imageUrl, String targetLocalPath) {
        String screenshotPath = WebScreenShotUtil.saveWebPageScreenshot(imageUrl);
        if (StrUtil.isBlank(screenshotPath)) {
            return false;
        }
        try {
            // 确保目标目录存在
            File targetFile = new File(targetLocalPath);
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                FileUtil.mkdir(parentDir);
            }
            FileUtil.move(new File(screenshotPath), targetFile, true);
            return true;
        } catch (Exception e) {
            log.warn("截图文件迁移失败: {}, from={}, to={}", e.getMessage(), screenshotPath, targetLocalPath);
            return false;
        }
    }

    /**
     * 生成 Logo 文件夹名称，使用 UUID 避免路径过长和中文问题
     */
    private String buildLogoSubjectFolder(String description) {
        // 使用 UUID 前 8 位，避免路径过长和中文字符问题
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
