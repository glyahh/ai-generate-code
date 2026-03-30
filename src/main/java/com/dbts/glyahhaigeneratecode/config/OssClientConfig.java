package com.dbts.glyahhaigeneratecode.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云OSS配置类
 */
@Configuration
@ConfigurationProperties(prefix = "aliyun.oss")
@Data
public class OssClientConfig {

    /**
     * Endpoint，例如：oss-cn-hangzhou.aliyuncs.com（可带 scheme）
     */
    private String endpoint;

    /**
     * AccessKeyId
     */
    private String accessKeyId;

    /**
     * AccessKeySecret（注意不要泄露）
     */
    private String accessKeySecret;

    /**
     * 区域
     */
    private String region;

    /**
     * Bucket 名称
     */
    private String bucketName;

    @Bean(destroyMethod = "shutdown")
    public OSS ossClient() {
        return new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }
}

