package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.mapper.AppLoopMapper;
import com.dbts.glyahhaigeneratecode.mapper.LoopMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.AppLoop;
import com.dbts.glyahhaigeneratecode.model.Entity.Loop;
import com.dbts.glyahhaigeneratecode.service.AppLoopService;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * AppLoop 库服务实现。
 * <p>维护 MySQL app_loop 表 + Redis 反向索引 (loop:app_ids:{loopId}) + 缓存 (app:loop:ids:{appId})。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppLoopServiceImpl implements AppLoopService {

    private final AppLoopMapper appLoopMapper;
    private final LoopMapper loopMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional
    public void bindLoops(Long appId, List<Long> loopIds, String addedFrom) {
        if (loopIds == null || loopIds.isEmpty()) {
            return;
        }
        for (Long loopId : loopIds) {
            AppLoop al = new AppLoop();
            al.setAppId(appId);
            al.setLoopId(loopId);
            al.setAddedFrom(addedFrom);
            al.setCreateTime(java.time.LocalDateTime.now());
            appLoopMapper.insert(al);
            // 写反向索引：loop → app_ids
            redisTemplate.opsForSet().add("loop:app_ids:" + loopId, String.valueOf(appId));
        }
        // 删除应用 Loop 缓存，下次读取时重建
        redisTemplate.delete("app:loop:ids:" + appId);
    }

    @Override
    @Transactional
    public void addLoop(Long appId, Long loopId, String addedFrom) {
        // 先校验 Loop 是否存在且未删除，防止绑定无效 Loop
        Loop loop = loopMapper.selectOneById(loopId);
        ThrowUtils.throwIf(loop == null || loop.getIsDelete() == 1,
                ErrorCode.NOT_FOUND_ERROR, "Loop 不存在或已删除");
        AppLoop al = new AppLoop();
        al.setAppId(appId);
        al.setLoopId(loopId);
        al.setAddedFrom(addedFrom);
        al.setCreateTime(java.time.LocalDateTime.now());
        try {
            appLoopMapper.insert(al);
            redisTemplate.opsForSet().add("loop:app_ids:" + loopId, String.valueOf(appId));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.warn("addLoop duplicate ignored: appId={} loopId={}", appId, loopId);
        } catch (Exception e) {
            log.error("addLoop failed: appId={} loopId={}", appId, loopId, e);
            ThrowUtils.throwIf(true, ErrorCode.SYSTEM_ERROR, "绑定 Loop 失败");
        }
        redisTemplate.delete("app:loop:ids:" + appId);
    }

    @Override
    public List<Map<String, Object>> listLoopVOs(Long appId) {
        // 1. 查 MySQL app_loop 获取关联 loopId 列表
        List<AppLoop> als = appLoopMapper.selectListByQuery(
                new QueryWrapper().eq("app_id", appId)
        );
        if (als.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> loopIds = als.stream()
                .map(AppLoop::getLoopId)
                .collect(Collectors.toList());

        // 2. 批量查 loop 表获取名称/描述
        List<Loop> loops = loopMapper.selectListByQuery(
                new QueryWrapper().in("id", loopIds).eq("is_delete", 0)
        );

        List<Map<String, Object>> result = new ArrayList<>();
        for (Loop l : loops) {
            Map<String, Object> item = new HashMap<>();
            item.put("loopId", l.getId());
            item.put("loopName", l.getLoopName());
            item.put("description", l.getDescription() != null ? l.getDescription() : "");
            result.add(item);
        }

        // 3. 写缓存 (仅存 loopIds 列表，1h TTL)
        String cacheKey = "app:loop:ids:" + appId;
        String idsJson = loopIds.stream().map(String::valueOf)
                .collect(Collectors.joining(","));
        if (!idsJson.isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, "[" + idsJson + "]", 1, TimeUnit.HOURS);
        }

        return result;
    }

    @Override
    @Transactional
    public void removeLoop(Long appId, Long loopId) {
        // 删除关联记录
        appLoopMapper.deleteByQuery(
                new QueryWrapper().eq("app_id", appId).eq("loop_id", loopId)
        );
        // 维护反向索引
        redisTemplate.opsForSet().remove("loop:app_ids:" + loopId, String.valueOf(appId));
        // 删除应用 Loop 缓存
        redisTemplate.delete("app:loop:ids:" + appId);
    }
}
