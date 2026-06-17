package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.DTO.LoopAddRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopQueryRequest;
import com.dbts.glyahhaigeneratecode.model.VO.LoopVO;

import java.util.List;

/**
 * Loop 领域服务接口。
 */
public interface LoopService {

    /**
     * 创建 Loop。
     */
    Long addLoop(LoopAddRequest req, Long userId);

    /**
     * 更新 Loop。
     */
    void updateLoop(LoopUpdateRequest req, Long userId);

    /**
     * 删除 Loop（逻辑删除 + Redis 反向索引清理）。
     */
    void deleteLoop(Long id, Long userId);

    /**
     * 获取 Loop 视图对象。
     */
    LoopVO getLoopVO(Long id);

    /**
     * 分页查询当前用户自己的 Loop。
     */
    List<LoopVO> myListPage(LoopQueryRequest req, Long userId);

    /**
     * 分页查询精选 Loop（priority >= 99，公开可见）。
     */
    List<LoopVO> goodListPage(LoopQueryRequest req);

    /**
     * 导入 Loop（解析 frontmatter + body → workflowJson）。
     */
    Long importLoop(String rawContent, Long userId);

    /**
     * 申请精选。
     */
    void applyGood(Long loopId, String reason, Long userId);

    /**
     * 管理员分页查询所有 Loop。
     */
    List<LoopVO> adminListPage(LoopQueryRequest req);

    /**
     * 管理员更新 Loop（可调整 priority）。
     */
    void adminUpdate(LoopUpdateRequest req);
}
