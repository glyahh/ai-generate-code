package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.VO.UserPersonalizationVO;
import com.dbts.glyahhaigeneratecode.model.DTO.UserPersonalizationUpdateRequest;

/**
 * 用户个性化配置 Service 接口。
 *
 * @author glyahh
 */
public interface UserPersonalizationService {

    /**
     * 按 userId 获取完整个性化配置（含缓存）。
     *
     * @param userId 用户ID
     * @return 配置 VO，未配置时字段为 null
     */
    UserPersonalizationVO getByUserId(Long userId);

    /**
     * 保存或更新用户个性化配置。
     * 先 MySQL upsert，再删除 Redis 缓存（cache-aside 写策略）。
     *
     * @param userId  用户ID
     * @param request 请求 DTO（appStyle/answerStyle 可选）
     */
    void saveOrUpdate(Long userId, UserPersonalizationUpdateRequest request);

    /**
     * 构建个性化 prompt 注入块。
     * 分别读取两个 Redis key，若都有内容则拼接为标签块；
     * 两者都为空时返回空串（不注入）。
     *
     * @param userId 用户ID
     * @return 格式化注入文本（含优先级说明标签），无内容时返回 ""
     */
    String buildInjectPrompt(Long userId);

    /**
     * 获取缓存的应用风格 prompt（无配置返回 null）。
     *
     * @param userId 用户ID
     * @return 应用风格 prompt，未配置时返回 null
     */
    String getCachedAppStyle(Long userId);

    /**
     * 获取缓存的回答风格 prompt（无配置返回 null）。
     *
     * @param userId 用户ID
     * @return 回答风格 prompt，未配置时返回 null
     */
    String getCachedAnswerStyle(Long userId);
}