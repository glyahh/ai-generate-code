package com.dbts.glyahhaigeneratecode.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * information_schema 元数据查询（DDL 校验等）。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
public interface SchemaMetadataMapper {

    @Select("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = #{tableName} AND COLUMN_NAME = #{columnName}
            """)
    Long countColumn(@Param("tableName") String tableName, @Param("columnName") String columnName);
}
