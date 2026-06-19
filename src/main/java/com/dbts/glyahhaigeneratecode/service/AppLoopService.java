package com.dbts.glyahhaigeneratecode.service;

import java.util.List;
import java.util.Map;

/**
 * 应用 Loop 库服务接口。
 * 管理应用与 Loop 技能的关联关系，维护 Redis 反向索引与缓存。
 */
public interface AppLoopService {

    /**
     * 批量绑定 Loop 到应用（创建应用时调用）。
     *
     * @param appId     应用 ID
     * @param loopIds   Loop ID 列表
     * @param addedFrom 来源标识（creation / chat / market）
     */
    void bindLoops(Long appId, List<Long> loopIds, String addedFrom);

    /**
     * 单个添加 Loop 到应用（从市场添加时调用）。
     *
     * @param appId     应用 ID
     * @param loopId    Loop ID
     * @param addedFrom 来源标识
     */
    void addLoop(Long appId, Long loopId, String addedFrom);

    /**
     * 从「我的 Loop」批量绑定到应用，校验每个 Loop 的归属（loop.userId == 当前用户）。
     * <p>与 bindLoops 的区别：多了一步归属校验，防止用户 A 在创建应用时
     * 把用户 B 的 Loop（通过市场克隆后未验证的引用）挂入自己应用。</p>
     *
     * @param appId  应用 ID
     * @param loopIds  要绑定的 Loop ID 列表（必须全部属于当前用户）
     * @param userId  当前用户 ID
     * @param addedFrom 来源标识
     */
    void bindLoopsFromMyLoop(Long appId, List<Long> loopIds, Long userId, String addedFrom);

    /**
     * 查询应用绑定的 Loop 列表（返回名称/描述等摘要信息）。
     *
     * @param appId 应用 ID
     * @return Loop 摘要列表，每项包含 loopId / loopName / description
     */
    List<Map<String, Object>> listLoopVOs(Long appId);

    /**
     * 从应用移除指定 Loop。
     *
     * @param appId  应用 ID
     * @param loopId Loop ID
     */
    void removeLoop(Long appId, Long loopId);
}
