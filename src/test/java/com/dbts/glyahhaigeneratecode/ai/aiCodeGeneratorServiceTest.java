package com.dbts.glyahhaigeneratecode.ai;

import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class aiCodeGeneratorServiceTest {

    @Resource
    private aiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Test
    void generateCodeHTML() {
        aiCodeGeneratorService service = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(
                0L, CodeGenTypeEnum.HTML, false);
        HtmlCodeResult Result = service.generateCodeHTML(0L, "生成一个介绍glyahh的网站,越短越好");
        Assertions.assertNotNull(Result);
        System.out.println(Result);
    }

    @Test
    void generateCodeMultiFile() {
        aiCodeGeneratorService service = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(
                0L, CodeGenTypeEnum.MULTI_FILE, false);
        MultiFileCodeResult Result = service.generateCodeMultiFile(0L, "生成一个介绍glyahh的网站,越短越好");
        Assertions.assertNotNull(Result);
        System.out.println(Result);
    }
}