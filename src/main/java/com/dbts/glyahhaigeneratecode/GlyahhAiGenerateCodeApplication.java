package com.dbts.glyahhaigeneratecode;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.Mapping;

@SpringBootApplication
@MapperScan("com.dbts.glyahhaigeneratecode.mapper")
public class GlyahhAiGenerateCodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(GlyahhAiGenerateCodeApplication.class, args);
    }

}
