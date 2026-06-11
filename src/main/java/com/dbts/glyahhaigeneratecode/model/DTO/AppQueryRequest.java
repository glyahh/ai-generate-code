package com.dbts.glyahhaigeneratecode.model.DTO;

import com.dbts.glyahhaigeneratecode.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 应用查询请求
 * 支持按除时间外的字段查询，并支持分页与排序
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class AppQueryRequest extends PageRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 应用封面
     */
    private String cover;

    /**
     * 应用初始化的 prompt
     */
    private String initPrompt;

    /**
     * 代码生成类型（枚举）
     */
    private String codeGenType;

    /**
     * 部署标识
     */
    private String deployKey;

    /**
     * 优先级
     */
    private Integer priority;

    /**
     * 创建用户id
     */
    private Long userId;

    /**
     * 是否删除
     */
    private Integer isDelete;

    /**
     * 是否工作流生成：1-是（isBeta=1），0-否（isBeta=0），null-全部
     */
    private Integer isWorkflow;

    /**
     * 是否已部署：1-已部署（deployKey 非空），0-未部署（deployKey 为空），null-全部
     */
    private Integer isDeployed;

    private static final long serialVersionUID = 1L;
}

