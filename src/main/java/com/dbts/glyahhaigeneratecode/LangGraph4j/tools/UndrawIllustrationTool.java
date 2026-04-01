package com.dbts.glyahhaigeneratecode.LangGraph4j.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.LangGraph4j.enums.ImageCategoryEnum;
import com.dbts.glyahhaigeneratecode.LangGraph4j.model.ImageResource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class UndrawIllustrationTool {

    /**
     * unDraw 搜索接口现使用查询参数 {@code q}；旧版 {@code term} 会返回 400，导致列表永远为空。
     */
    private static final String UNDRAW_API_URL = "https://undraw.co/api/search?q=%s";

    @Tool("搜索插画图片，用于网站美化和装饰")
    public List<ImageResource> searchIllustrations(@P("搜索关键词") String query) {
        List<ImageResource> imageList = new ArrayList<>();
        int searchCount = 12;
        if (StrUtil.isBlank(query)) {
            return imageList;
        }
        String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        String apiUrl = String.format(UNDRAW_API_URL, encoded);

        // 使用 try-with-resources 自动释放 HTTP 资源
        try (HttpResponse response = HttpRequest.get(apiUrl).timeout(10000).execute()) {
            if (!response.isOk()) {
                // 非 2xx 时旧逻辑静默返回空列表，调用方无法区分「无结果」与「接口失败」；至少打日志便于排查
                log.warn("unDraw 搜索 HTTP 失败: status={}, body={}", response.getStatus(), response.body());
                return imageList;
            }
            JSONObject result = JSONUtil.parseObj(response.body());
            // 新版响应：根节点 results；旧版 Next 页面数据：pageProps.initialResults
            JSONArray initialResults = result.getJSONArray("results");
            if (initialResults == null || initialResults.isEmpty()) {
                JSONObject pageProps = result.getJSONObject("pageProps");
                if (pageProps != null) {
                    initialResults = pageProps.getJSONArray("initialResults");
                }
            }
            if (initialResults == null || initialResults.isEmpty()) {
                return imageList;
            }
            int actualCount = Math.min(searchCount, initialResults.size());
            for (int i = 0; i < actualCount; i++) {
                JSONObject illustration = initialResults.getJSONObject(i);
                String title = illustration.getStr("title", "插画");
                String media = illustration.getStr("media", "");
                if (StrUtil.isNotBlank(media)) {
                    imageList.add(ImageResource.builder()
                            .category(ImageCategoryEnum.ILLUSTRATION)
                            .description(title)
                            .url(media)
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("搜索插画失败：{}", e.getMessage(), e);
        }
        return imageList;
    }
}
