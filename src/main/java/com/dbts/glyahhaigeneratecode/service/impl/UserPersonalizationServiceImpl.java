package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.constant.UserPersonalizationConstant;
import com.dbts.glyahhaigeneratecode.mapper.UserPersonalizationMapper;
import com.dbts.glyahhaigeneratecode.model.DTO.UserPersonalizationUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.UserPersonalization;
import com.dbts.glyahhaigeneratecode.model.VO.UserPersonalizationVO;
import com.dbts.glyahhaigeneratecode.service.UserPersonalizationService;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 用户个性化配置 Service 实现。
 * <p>
 * 缓存架构（Cache-Aside 模式 + 双 key 分离存储）：
 * ┌─────────────────────────────────────────────────────────────┐
 * │ 读路径：Redis GET → hit → 续 TTL → 返回                      │
 * │         → miss → 锁 → double-check → MySQL SELECT → 回填 → 返回 │
 * │ 写路径：MySQL upsert → Redis DELETE（两 key 各自删除）          │
 * └─────────────────────────────────────────────────────────────┘
 * <p>
 * Redis 双 key 设计（纯文本值，非 JSON）：
 * - user:favourite:app:{userId}   → 应用风格 prompt
 * - user:favourite:style:{userId} → 回答风格 prompt
 * <p>
 * 风险防护策略（企业级）：
 * 1. 【缓存穿透】MySQL 未命中时缓存 "{}" + 60s TTL，防止恶意 userId 穿透 DB
 * 2. 【缓存击穿】synchronized(userId.intern()) + double-check，
 *    同一 userId 只允许一个线程回源 MySQL，其余等待后命中缓存
 * 3. 【缓存雪崩】每次写缓存时 TTL 叠加 Random(0~10min) 随机抖动，
 *    使各 key 过期时间自然分散，不集中失效
 * 4. 【Redis 降级】Redis 异常时 catch 并降级到 MySQL 查询，不影响业务
 *
 * @author glyahh
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserPersonalizationServiceImpl implements UserPersonalizationService {

    private final UserPersonalizationMapper mapper;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 按 userId 获取完整个性化配置。
     * 分别读取两个独立 Redis key，最终聚合成一个 VO 返回。
     *
     * @param userId 用户ID
     * @return 配置 VO
     */
    @Override
    public UserPersonalizationVO getByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            return new UserPersonalizationVO();
        }
        String appStyle = getCachedPrompt(userId, true);
        String answerStyle = getCachedPrompt(userId, false);
        UserPersonalizationVO vo = new UserPersonalizationVO();
        vo.setAppStyle(appStyle);
        vo.setAnswerStyle(answerStyle);
        return vo;
    }

    /**
     * 保存或更新用户个性化配置。
     * <p>
     * 写策略（Cache-Aside）：
     * 1. 先操作 MySQL（存在则 update，否则 insert）
     * 2. 再删除两个 Redis key
     * <p>
     * 选 delete 而非 update 的原因：delete 是幂等操作，
     * 并发场景下不会出现 MySQL 与 Redis 数据不一致。
     * 后续首次读必然 cache miss → 回源 MySQL → 拿到最新值。
     *
     * @param userId  用户ID
     * @param request 请求内容
     */
    @Override
    public void saveOrUpdate(Long userId, UserPersonalizationUpdateRequest request) {
        if (userId == null || userId <= 0 || request == null) {
            return;
        }

        // 1. 截断超长输入
        String appStyle = StrUtil.maxLength(request.getAppStyle(), UserPersonalizationConstant.PROMPT_MAX_LENGTH);
        String answerStyle = StrUtil.maxLength(request.getAnswerStyle(), UserPersonalizationConstant.PROMPT_MAX_LENGTH);

        // 2. MySQL upsert：查是否存在，存在则 update 否则 insert
        UserPersonalization entity = selectByUserId(userId);
        boolean isNew = (entity == null);
        if (isNew) {
            entity = UserPersonalization.builder()
                    .userId(userId)
                    .appStyle(appStyle)
                    .answerStyle(answerStyle)
                    .build();
            mapper.insertSelective(entity);
        } else {
            entity.setAppStyle(appStyle);
            entity.setAnswerStyle(answerStyle);
            mapper.update(entity);
        }
        log.info("用户个性化配置已保存，userId={}, isNew={}", userId, isNew);

        // 3. 删除两个 Redis key（缓存更新策略）
        // 注意：此处 delete 而非 update，
        // 目的是避免并发写场景下 MySQL 与 Redis 状态不一致
        try {
            stringRedisTemplate.delete(buildAppKey(userId));
            stringRedisTemplate.delete(buildStyleKey(userId));
        } catch (Exception e) {
            log.warn("删除用户个性化 Redis 缓存失败，不影响 MySQL 落盘。userId={}", userId, e);
        }
    }

    /**
     * 构建个性化 prompt 注入块【核心方法】。
     * <p>
     * 分别从两个独立 Redis key 读取 prompt 内容，
     * 若任一为空则跳过对应注入块，两者都为空时返回空串。
     * <p>
     * 注入格式：
     * <pre>
     * [user_app_style]
     * （说明：优先级低于本轮显式指令、高于系统默认）
     * 用户偏好：{appStyle内容}
     *
     * [user_answer_style]
     * （说明：优先级低于本轮显式指令、高于系统默认）
     * 用户偏好：{answerStyle内容}
     * </pre>
     *
     * @param userId 用户ID
     * @return 格式化注入文本，无内容时返回 ""
     */
    @Override
    public String buildInjectPrompt(Long userId) {
        if (userId == null || userId <= 0) {
            return "";
        }

        // 1. 分别读取两个独立 key 的缓存值
        String appStyle = getCachedPrompt(userId, true);
        String answerStyle = getCachedPrompt(userId, false);

        // 2. 两者都为空 → 不注入
        boolean hasApp = StrUtil.isNotBlank(appStyle);
        boolean hasAns = StrUtil.isNotBlank(answerStyle);
        if (!hasApp && !hasAns) {
            return "";
        }

        // 3. 拼接注入块
        // 优先级说明写在块内首行，模型会按照注入顺序理解优先级：
        // 个性化提示 < 本轮用户显式指令，但 > 系统默认 SystemMessage
        StringBuilder sb = new StringBuilder("\n\n");
        if (hasApp) {
            sb.append(UserPersonalizationConstant.INJECT_TAG_APP_STYLE).append("\n");
            sb.append("（说明：以下为你的应用风格偏好，优先级低于本轮显式指令、高于系统默认）\n");
            sb.append("用户偏好：").append(appStyle).append("\n\n");
        }
        if (hasAns) {
            sb.append(UserPersonalizationConstant.INJECT_TAG_ANSWER_STYLE).append("\n");
            sb.append("（说明：以下为你的回答风格偏好，优先级低于本轮显式指令、高于系统默认）\n");
            sb.append("用户偏好：").append(answerStyle).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 获取缓存的应用风格 prompt。
     *
     * @param userId 用户ID
     * @return 应用风格 prompt，未配置时返回 null
     */
    @Override
    public String getCachedAppStyle(Long userId) {
        if (userId == null || userId <= 0) return null;
        return getCachedPrompt(userId, true);
    }

    /**
     * 获取缓存的回答风格 prompt。
     *
     * @param userId 用户ID
     * @return 回答风格 prompt，未配置时返回 null
     */
    @Override
    public String getCachedAnswerStyle(Long userId) {
        if (userId == null || userId <= 0) return null;
        return getCachedPrompt(userId, false);
    }

    // ======================== 私有方法 ========================

    /**
     * 按 userId + 风格类型，从缓存或 MySQL 读取 prompt 值。
     * <p>
     * Cache-Aside 完整读路径：
     * 1. Redis GET → 命中 + 有实际内容 → 滑动 TTL → 返回内容
     * 2. Redis GET → 命中空占位 "{}" → 短 TTL 续期 → 返回 null
     * 3. Redis GET → miss → 进入同步锁保护区
     * 3.1 double-check Redis（防止上个线程已回填）
     * 3.2 MySQL SELECT → 有数据 → 随机 TTL 回填 Redis → 返回内容
     * 3.3 MySQL SELECT → 无数据 → 空 "{}" + 60s TTL 缓存防穿透 → 返回 null
     * <p>
     * 【缓存穿透防护】
     * MySQL 未命中时以 "{}" + 60s TTL 写入 Redis。
     * 效果：后续同 userId 的请求直接命中空占位，不穿透 DB。
     * 选择 60s 而非永久：兼顾防护效果与数据一致性（用户可能在 60s 内首次配置）。
     * <p>
     * 【缓存击穿防护】
     * synchronized(userId.toString().intern()) + double-check。
     * userId.toString().intern() 确保同一 userId 的字符串引用相同，JVM 层面的锁。
     * 锁内先 double-check Redis：若上个线程已回填，则直接返回，避免二次查询 MySQL。
     * <p>
     * 【缓存雪崩防护】
     * Redis TTL = CACHE_TTL_SECONDS + Random(0, CACHE_TTL_JITTER_SECONDS)。
     * 同 userId 的两个 key 各自独立计算随机值，天然错开过期时间。
     * 不同 userId 之间因随机值不同，缓存不会集中失效。
     *
     * @param userId 用户ID
     * @param isApp  true=应用风格 false=回答风格
     * @return prompt 文本，未配置时返回 null
     */
    String getCachedPrompt(Long userId, boolean isApp) {
        // 1. 根据风格类型选择 key 前缀
        String key = isApp ? buildAppKey(userId) : buildStyleKey(userId);

        try {
            // 2. 优先读 Redis
            String cached = stringRedisTemplate.opsForValue().get(key);

            // 2.1 命中空值占位{} → 续短 TTL 防穿透 → 返回 null
            if (UserPersonalizationConstant.CACHE_NULL_PLACEHOLDER.equals(cached)) {
                refreshTtlWithJitter(key, UserPersonalizationConstant.CACHE_NULL_TTL_SECONDS);
                return null;
            }

            // 2.2 命中有效内容 → 滑动 TTL → 返回
            if (StrUtil.isNotBlank(cached)) {
                refreshTtlWithJitter(key, UserPersonalizationConstant.CACHE_TTL_SECONDS);
                return cached;
            }
        } catch (Exception e) {
            // 【Redis 降级】Redis 异常时 catch，降级到 MySQL 查询
            log.warn("Redis 读取失败，降级到 MySQL。userId={}, isApp={}", userId, isApp);
        }

        // ========== 以下为 Redis miss 或异常：回源 MySQL（含击穿防护） ==========

        // [缓存击穿防护] 同步锁 + double-check
        // 同一 userId 的字符串常量池引用作为锁，确保只允许一个线程回源
        // 拿到usrId的固定字符串常量池的地址作为锁对象
        synchronized (userId.toString().intern()) {
            try {
                // double-check：上一个线程可能已回填 Redis，避免二次 MySQL 查询
                String rechecked = stringRedisTemplate.opsForValue().get(key);
                if (UserPersonalizationConstant.CACHE_NULL_PLACEHOLDER.equals(rechecked)) {
                    return null;
                }
                if (StrUtil.isNotBlank(rechecked)) {
                    refreshTtlWithJitter(key, UserPersonalizationConstant.CACHE_TTL_SECONDS);
                    return rechecked;
                }
            } catch (Exception e) {
                // double-check Redis 失败，继续走 MySQL 查询
            }

            // 3. MySQL 查询
            UserPersonalization entity = selectByUserId(userId);
            String promptValue = null;

            if (entity != null) {
                // 3.1 有数据：取对应字段
                promptValue = isApp ? entity.getAppStyle() : entity.getAnswerStyle();
            }

            // 4. 回填 Redis
            try {
                if (StrUtil.isNotBlank(promptValue)) {
                    // [雪崩防护] TTL = 基础值 + 随机抖动
                    long ttl = UserPersonalizationConstant.CACHE_TTL_SECONDS
                            + RandomUtil.randomLong(0, UserPersonalizationConstant.CACHE_TTL_JITTER_SECONDS);
                    stringRedisTemplate.opsForValue().set(key, promptValue, ttl, TimeUnit.SECONDS);
                } else {
                    // [穿透防护] 空值占位 + 短 TTL
                    stringRedisTemplate.opsForValue().set(
                            key,
                            UserPersonalizationConstant.CACHE_NULL_PLACEHOLDER,
                            UserPersonalizationConstant.CACHE_NULL_TTL_SECONDS,
                            TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.warn("Redis 回填失败，userId={}, isApp={}", userId, isApp, e);
            }

            return promptValue;
        }
    }

    /** 构造应用风格 Redis key：user:favourite:app:{userId} */
    private String buildAppKey(Long userId) {
        return UserPersonalizationConstant.REDIS_KEY_APP + userId;
    }

    /** 构造回答风格 Redis key：user:favourite:style:{userId} */
    private String buildStyleKey(Long userId) {
        return UserPersonalizationConstant.REDIS_KEY_STYLE + userId;
    }

    /**
     * 滑动续期 TTL（叠加随机抖动）。
     * 每次命中缓存时调用，使热点 key 的过期时间持续推迟。
     *
     * @param key          Redis key
     * @param baseTtlSecs  基础 TTL（秒）
     */
    private void refreshTtlWithJitter(String key, long baseTtlSecs) {
        try {
            long ttl = baseTtlSecs
                    + RandomUtil.randomLong(0, UserPersonalizationConstant.CACHE_TTL_JITTER_SECONDS);
            stringRedisTemplate.expire(key, ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            // TTL 续期失败不影响业务，仅 debug 级别日志
            log.debug("Redis TTL 续期失败，key={}", key);
        }
    }

    /** 按 userId 查询未逻辑删除的记录 */
    private UserPersonalization selectByUserId(Long userId) {
        QueryWrapper qw = new QueryWrapper()
                .eq(UserPersonalization::getUserId, userId);
        return mapper.selectOneByQuery(qw);
    }
}
