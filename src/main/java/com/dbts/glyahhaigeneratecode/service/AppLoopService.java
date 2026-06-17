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
