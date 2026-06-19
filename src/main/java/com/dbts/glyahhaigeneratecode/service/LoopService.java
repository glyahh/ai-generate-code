package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.DTO.LoopAddRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopQueryRequest;
import com.dbts.glyahhaigeneratecode.model.VO.LoopVO;

import java.util.List;
import java.util.Map;

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
     * 分页查询公开 Loop（所有 visibility=public 的，不带 priority 过滤）。
     */
    List<LoopVO> publicListPage(LoopQueryRequest req);

    /**
     * 导入 Loop（解析 frontmatter + body → workflowJson）。
     */
    Long importLoop(String rawContent, Long userId);

    /**
     * 从市场克隆一个公开 Loop 到当前用户的个人库。
     * <p>只会克隆 visibility=public 且未删除的 Loop。克隆后 sourceType=market_imported，
     * visibility=private，priority=0。</p>
     *
     * @param loopId 市场中要导入的 Loop ID
     * @param userId 当前用户 ID
     * @return 新创建的 Loop ID（在当前用户名下）
     */
    Long marketImport(Long loopId, Long userId);

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

    /**
     * 管理员删除 Loop（绕过用户归属校验）。
     */
    void adminDeleteLoop(Long id);

    // ==================== 管理员 Loop 申请审批 ====================

    /**
     * 管理员分页查询 Loop 精选申请列表。
     */
    List<Map<String, Object>> adminListApply(LoopQueryRequest req);

    /**
     * 管理员通过 Loop 精选申请（设 priority=99 + 更新申请状态）。
     */
    void adminApproveApply(Long applyId, Long reviewUserId);

    /**
     * 管理员拒绝 Loop 精选申请。
     */
    void adminRejectApply(Long applyId, String reviewRemark, Long reviewUserId);
}
