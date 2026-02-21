package com.dbts.glyahhaigeneratecode.ai;

import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class aiCodeGeneratorServiceTest {

    @Resource
    private aiCodeGeneratorService aiCodeGeneratorService;

    @Test
    void generateCodeHTML() {
        HtmlCodeResult Result = aiCodeGeneratorService.generateCodeHTML("生成一个介绍glyahh的网站,越短越好");
        Assertions.assertNotNull(Result);
        System.out.println(Result);
    }

    @Test
    void generateCodeMultiFile() {
        MultiFileCodeResult Result = aiCodeGeneratorService.generateCodeMultiFile("生成一个介绍glyahh的网站,越短越好");
        Assertions.assertNotNull(Result);
        System.out.println(Result);
    }
}