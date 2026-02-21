package com.dbts.glyahhaigeneratecode.core;

import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AiCodeGeneratorFacadeTest {

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;
    @Test
    void generateAndSaveCode() {
        File file = aiCodeGeneratorFacade.generateAndSaveCode("生成一个介绍glyahh的网站,越短越好", CodeGenTypeEnum.HTML);
        assertNotNull(file);

        File file1 = aiCodeGeneratorFacade.generateAndSaveCode("生成一个介绍glyahh的网站,越短越好", CodeGenTypeEnum.MULTI_FILE);
        assertNotNull(file1);
    }
}