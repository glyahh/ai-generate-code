package com.dbts.glyahhaigeneratecode.manage;

import com.aliyun.oss.OSS;
import com.dbts.glyahhaigeneratecode.config.OssClientConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URI;

/**
 * 对象存储管理器（阿里云 OSS）。
 */
@Component
@Slf4j
public class OssManager {

    @Resource
    private OssClientConfig ossClientConfig;

    @Resource
    private OSS ossClient;

    /**
     * 上传文件到 OSS 并返回访问 URL
     *
     * @param key  对象 key（允许以 '/' 开头，例如：/screenshots/xxx.jpg）,指存放在OSS中的文件路径
     * @param file 要上传的文件
     * @return 文件访问 URL，失败返回 null
     */
    public String uploadFile(String key, File file) {
        if (key == null || key.isBlank()) {
            log.error("上传失败：key 为空");
            return null;
        }
        if (file == null || !file.exists()) {
            log.error("上传失败：文件不存在，file={}", file);
            return null;
        }

        String objectKey = normalizeKey(key);
        try {
            // 上传对象内容
            ossClient.putObject(ossClientConfig.getBucketName(), objectKey, file);
        } catch (Exception e) {
            log.error("文件上传 OSS 失败: file={}, bucket={}, key={}", file.getName(), ossClientConfig.getBucketName(), objectKey, e);
            return null;
        }

        String url = buildPublicUrl(objectKey);
        log.info("文件上传 OSS 成功: {} -> {}", file.getName(), url);
        return url;
    }

    /**
     * 删除 OSS 对象（支持传入完整 URL 或 objectKey）。
     *
     * @param urlOrKey 形如 https://bucket.endpoint/path/to.jpg 或 /screenshots/xxx.jpg 或 screenshots/xxx.jpg
     * @return 删除成功或对象不存在返回 true；删除失败返回 false
     */
    public boolean deleteFile(String urlOrKey) {
        String objectKey = tryExtractObjectKey(urlOrKey);
        if (objectKey == null || objectKey.isBlank()) {
            log.warn("删除 OSS 对象失败：无法解析 objectKey, urlOrKey={}", urlOrKey);
            return false;
        }
        objectKey = normalizeKey(objectKey);
        try {
            // 不存在也视为“已清理”，避免阻塞主流程
            if (!ossClient.doesObjectExist(ossClientConfig.getBucketName(), objectKey)) {
                return true;
            }
            ossClient.deleteObject(ossClientConfig.getBucketName(), objectKey);
            return true;
        } catch (Exception e) {
            log.error("删除 OSS 对象失败: bucket={}, key={}", ossClientConfig.getBucketName(), objectKey, e);
            return false;
        }
    }

    private String normalizeKey(String key) {
        String normalized = key.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * 从完整 URL 中提取 objectKey（取 path 部分），否则原样返回（按 key 处理）。
     */
    private String tryExtractObjectKey(String urlOrKey) {
        if (urlOrKey == null) {
            return null;
        }
        String input = urlOrKey.trim();
        if (input.isBlank()) {
            return input;
        }
        if (input.startsWith("http://") || input.startsWith("https://")) {
            try {
                URI uri = URI.create(input);
                String path = uri.getPath();
                if (path == null) {
                    return null;
                }
                return path.startsWith("/") ? path.substring(1) : path;
            } catch (Exception e) {
                log.warn("解析 OSS URL 失败，将按 key 处理: {}", input, e);
                return input;
            }
        }
        return input;
    }

    /**
     * 按虚拟主机样式拼接公网访问 URL： https://{bucket}.{endpoint}/{objectKey}
     */
    private String buildPublicUrl(String objectKey) {
        String endpoint = ossClientConfig.getEndpoint();
        String bucketName = ossClientConfig.getBucketName();

        // endpoint 可能配置成无协议（oss-cn-hangzhou.aliyuncs.com），也可能带 http(s)://
        String scheme = "https";
        String host = endpoint;
        if (endpoint != null) {
            if (endpoint.startsWith("http://")) {
                scheme = "http";
                host = endpoint.substring("http://".length());
            } else if (endpoint.startsWith("https://")) {
                host = endpoint.substring("https://".length());
            }
        }

        if (host != null) {
            while (host.endsWith("/")) {
                host = host.substring(0, host.length() - 1);
            }
        }

        return String.format("%s://%s.%s/%s", scheme, bucketName, host, objectKey);
    }
}

