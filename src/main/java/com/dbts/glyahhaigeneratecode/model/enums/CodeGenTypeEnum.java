package com.dbts.glyahhaigeneratecode.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum CodeGenTypeEnum {

    HTML("原生 HTML 模式", "html"),
    MULTI_FILE("原生多文件模式", "multi_file");

    private final String text;
    private final String value;

    CodeGenTypeEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static CodeGenTypeEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        // 注意这里的value不是字段的value，而是返回所有枚举类的方法
        for (CodeGenTypeEnum anEnum : CodeGenTypeEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
