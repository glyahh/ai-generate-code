package com.dbts.glyahhaigeneratecode;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication(exclude =  {RedisEmbeddingStoreAutoConfiguration.class})
@MapperScan("com.dbts.glyahhaigeneratecode.mapper")
@EnableCaching
public class GlyahhAiGenerateCodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(GlyahhAiGenerateCodeApplication.class, args);
    }
}
