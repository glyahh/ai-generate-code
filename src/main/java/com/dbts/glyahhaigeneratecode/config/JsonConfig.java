package com.dbts.glyahhaigeneratecode.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring MVC Json 配置：
 * 在后端发往前端时
 * 将所有 Long 序列化为字符串
 * 这样json中封装的就是string不是number
 *
 * java: Long -> json:number 可能会出现精度丢失问题
 */
@Configuration
public class JsonConfig {

    /**
     * 添加 Long 转 json 精度丢失的配置
     * 使用 Jackson2ObjectMapperBuilderCustomizer 确保被 Spring MVC 正确使用。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        return builder -> builder.modulesToInstall(module);
    }
}
