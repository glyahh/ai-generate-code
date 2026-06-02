package com.dbts.glyahhaigeneratecode.model.enums;

import lombok.Getter;

/**
 * memory_shrink 压缩类型。
 * <p>
 * conversation_summary（两轮合并）| message_truncate（单条 AI 超长截断）-> 写入 shrinkType 字段区分落库语义
 */
@Getter
public enum MemoryShrinkTypeEnum {

    CONVERSATION_SUMMARY("conversation_summary"),
    MESSAGE_TRUNCATE("message_truncate");

    private final String value;

    /**
     * 绑定枚举与数据库存储字符串
     *
     * @param value shrinkType 列值
     */
    MemoryShrinkTypeEnum(String value) {
        this.value = value;
    }

    /**
     * 按 shrinkType 字符串反查枚举
     *
     * @param value 数据库存储值
     * @return 匹配枚举；无匹配或 null 时返回 null
     */
    public static MemoryShrinkTypeEnum getEnumByValue(String value) {
        if (value == null) {
            return null;
        }
        for (MemoryShrinkTypeEnum e : values()) {
            if (e.value.equals(value)) {
                return e;
            }
        }
        return null;
    }
}
