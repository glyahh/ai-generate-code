package com.dbts.glyahhaigeneratecode.service.support;

import com.dbts.glyahhaigeneratecode.mapper.LoopMapper;
import com.dbts.glyahhaigeneratecode.mapper.AppLoopMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.Loop;
import com.dbts.glyahhaigeneratecode.core.memory.MemoryMessageXmlSupport;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Loop 注入服务。
 *
 * <p>在 SSE 生成入口 ChatToGenCodeImpl 的后半段被调用，向 userMessage 后缀拼接
 * {@code [loop_skill name="..."]} tagged 块。</p>
 *
 * <p>注入顺序：personalization → 用户消息 → loop_skill（Loop 离用户最近，覆盖最外层）。</p>
 *
 * <p>缓存策略（Cache-Aside）：
 * <ul>
 *   <li>key: {@code loop:compiled:{loopId}}，TTL 30+random(5)min
 *       随机抖动防缓存雪崩，参考 UserPersonalizationServiceImpl</li>
 *   <li>未命中时写空值占位 {@code {}} TTL 60s，防缓存穿透</li>
 * </ul></p>
 *
 * <p>无 Loop / loopId 无效 / 校验不通过 → 跳过注入（不抛异常）。
 * 这是有意的优雅降级：不应因 Loop 异常阻塞正常生成流程。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoopInjectService {

    private final LoopMapper loopMapper;
    private final AppLoopMapper appLoopMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 如果 loopId 合法且属于应用库，则后缀注入 tagged 块。
     * loopId 为空/无效/不属于本应用 → 跳过注入，不报错。
     *
     * @param message 原始用户消息
     * @param userId  当前用户 ID
     * @param appId   当前应用 ID
     * @param loopId  要注入的 Loop ID（可选）
     * @return 注入后的消息
     */
    public String injectIfPresent(String message, Long userId, Long appId, Long loopId) {
        if (loopId == null) {
            return message;
        }
        // 校验Loop是否合法
        if (!validateLoop(userId, appId, loopId)) {
            return message;
        }
        // 从redis中获取Loop
        String compiled = getCompiledPrompt(loopId);
        if (compiled == null || compiled.isBlank()) {
            return message;
        }

        String loopName = getLoopName(loopId);
        String block = "\n" + MemoryMessageXmlSupport.wrapLoopSkill(loopId, compiled, loopName);
        return message + block;
    }

    /**
     * 校验 loopId 是否属于 app 的 app_loop 库，或是用户自有（未删除）。
     * <p>两步校验：先查 app_loop 表 + Loop 有效态，再查用户自有 Loop。</p>
     */
    private boolean validateLoop(Long userId, Long appId, Long loopId) {
        // 先查 app_loop，找到后再验 Loop 未被逻辑删除
        long count = appLoopMapper.selectCountByQuery(
                new QueryWrapper().eq("app_id", appId).eq("loop_id", loopId)
        );
        if (count > 0) {
            Loop loop = loopMapper.selectOneById(loopId);
            return loop != null && loop.getIsDelete() != 1;
        }
        // 再查用户的 Loop（自己创建的 Loop 即使未加入应用库也可注入）
        count = loopMapper.selectCountByQuery(
                new QueryWrapper().eq("id", loopId).eq("user_id", userId).eq("is_delete", 0)
        );
        return count > 0;
    }

    /**
     * 读 Redis → miss 读 MySQL → 回填 Redis。
     */
    private String getCompiledPrompt(Long loopId) {
        String cacheKey = "loop:compiled:" + loopId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if ("{}".equals(cached)) {
                return null; // 直接返回空值占位, 不用再查一遍数据库了
            }
            return cached;
        }
        Loop loop = loopMapper.selectOneById(loopId);
        if (loop == null || loop.getIsDelete() == 1 || loop.getCompiledPrompt() == null) {
            // 空值占位防穿透
            redisTemplate.opsForValue().set(cacheKey, "{}", 60, TimeUnit.SECONDS);
            return null;
        }
        String compiled = loop.getCompiledPrompt();
        redisTemplate.opsForValue().set(
                cacheKey, compiled,
                30 + (long) (Math.random() * 5), TimeUnit.MINUTES
        );
        return compiled;
    }

    /**
     * 获取 Loop 名称，仅用于 [loop_skill name="..."] 标签。
     */
    private String getLoopName(Long loopId) {
        Loop loop = loopMapper.selectOneById(loopId);
        return loop != null ? loop.getLoopName() : "";
    }

    /**
     * 对 name 属性做基本转义，防止注入标签断裂。
     */
    private String escape(String s) {
        return s != null ? s.replace("\"", "&quot;").replace("\n", " ") : "";
    }
}
