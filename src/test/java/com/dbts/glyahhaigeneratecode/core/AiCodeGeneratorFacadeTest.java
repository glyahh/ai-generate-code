package com.dbts.glyahhaigeneratecode.core;

import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class AiCodeGeneratorFacadeTest {

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;
    @Test
    void generateAndSaveCode() {
        File file = aiCodeGeneratorFacade.generateAndSaveCode("生成一个介绍glyahh的网站,越短越好", CodeGenTypeEnum.HTML, 1L);
        assertNotNull(file);

        File file1 = aiCodeGeneratorFacade.generateAndSaveCode("生成一个介绍glyahh的网站,越短越好", CodeGenTypeEnum.MULTI_FILE, 1L);
        assertNotNull(file1);
    }

    @Test
    void generateAndSaveCodeStream() {
        Flux<String> result = aiCodeGeneratorFacade.generateAndSaveCodeStream("生成一个介绍glyahh的网站,精简美观", CodeGenTypeEnum.MULTI_FILE, 1L);
        List<String> list = result.collectList().block();
        assertNotNull(list);

        String join = String.join("\n", list);
        assertNotNull(join);
    }
}