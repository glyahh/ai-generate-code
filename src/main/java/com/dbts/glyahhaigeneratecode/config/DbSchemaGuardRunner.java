package com.dbts.glyahhaigeneratecode.config;

import com.dbts.glyahhaigeneratecode.mapper.SchemaMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时校验关键表结构。
 * 若缺少必要字段则直接阻断启动；若缺少必要表则自动创建（使用仓库内的增量 SQL）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbSchemaGuardRunner implements ApplicationRunner {

    private final SchemaMetadataMapper schemaMetadataMapper;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureAppIsBetaColumnExists();
        ensureUserPersonalizationTableExists();
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

    /**
     * 自动创建 user_personalization 表（如不存在）。
     * <p>对应增量 SQL 文件：sql/user_personalization.sql。
     * 使用 CREATE TABLE IF NOT EXISTS，多次执行安全。</p>
     */
    private void ensureUserPersonalizationTableExists() {
        Long count = schemaMetadataMapper.countTable("user_personalization");
        if (count != null && count > 0) {
            return;
        }
        log.info("user_personalization 表不存在，自动创建...");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_personalization (
                    id          BIGINT       NOT NULL PRIMARY KEY COMMENT '主键（Snowflake）',
                    userId      BIGINT       NOT NULL COMMENT '用户ID',
                    app_style   TEXT                  COMMENT '应用风格 prompt',
                    answer_style TEXT                 COMMENT '回答风格 prompt',
                    createTime  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updateTime  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    isDelete    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
                    UNIQUE KEY uk_userId (userId)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户个性化配置'
                """);
        log.info("user_personalization 表创建完成");
    }
}
