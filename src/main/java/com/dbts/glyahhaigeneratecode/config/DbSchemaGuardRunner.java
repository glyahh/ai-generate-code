package com.dbts.glyahhaigeneratecode.config;

import com.dbts.glyahhaigeneratecode.mapper.SchemaMetadataMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时校验关键表结构。
 * 若缺少必要字段则直接阻断启动
 */
@Component
@RequiredArgsConstructor
public class DbSchemaGuardRunner implements ApplicationRunner {

    private final SchemaMetadataMapper schemaMetadataMapper;

    @Override
    public void run(ApplicationArguments args) {
        ensureAppIsBetaColumnExists();
    }

    private void ensureAppIsBetaColumnExists() {
        Long count = schemaMetadataMapper.countColumn("app", "is_beta");
        if (count != null && count > 0) {
            return;
        }
        throw new IllegalStateException(
                "数据库结构校验失败：缺少 app.is_beta 字段。请先执行 sql/alter_app_add_is_beta.sql 后再启动应用。"
        );
    }
}
