//package com.dbts.glyahhaigeneratecode.config;
//
//import lombok.Data;
//import org.openqa.selenium.remote.http.ClientConfig;
//import org.springframework.boot.context.properties.ConfigurationProperties;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//
//import javax.swing.plaf.synth.Region;
//
///**
// * 腾讯云COS配置类
// *
// * @author yupi
// */
//@Configuration
//@ConfigurationProperties(prefix = "cos.client")
//@Data
//public class CosClientConfig {
//
//    /**
//     * 域名
//     */
//    private String host;
//
//    /**
//     * secretId
//     */
//    private String secretId;
//
//    /**
//     * 密钥（注意不要泄露）
//     */
//    private String secretKey;
//
//    /**
//     * 区域
//     */
//    private String region;
//
//    /**
//     * 桶名
//     */
//    private String bucket;
//
//    @Bean
//    public COSClient cosClient() {
//        // 初始化用户身份信息(secretId, secretKey)
//        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
//        // 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
//        ClientConfig clientConfig = new ClientConfig(new Region(region));
//        // 生成cos客户端
//        return new COSClient(cred, clientConfig);
//    }
//}
