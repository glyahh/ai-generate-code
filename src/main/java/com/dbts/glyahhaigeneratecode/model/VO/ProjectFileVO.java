package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 项目文件回显视图对象
 */
@Data
public class ProjectFileVO implements Serializable {

    /**
     * 文件相对路径（相对于项目根目录）
     */
    private String path;

    /**
     * 代码语言标识（vue / typescript / javascript / css / html / text）
     */
    private String language;

    /**
     * 文件内容（UTF-8 文本）
     */
    private String content;

    /**
     * 文件最后修改时间
     */
    private Long updatedAt;

    private static final long serialVersionUID = 1L;
}
