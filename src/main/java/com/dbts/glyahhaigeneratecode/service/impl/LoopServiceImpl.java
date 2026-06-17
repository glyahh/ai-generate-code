package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.mapper.LoopMapper;
import com.dbts.glyahhaigeneratecode.mapper.AppLoopMapper;
import com.dbts.glyahhaigeneratecode.mapper.UserLoopApplyMapper;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopAddRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopQueryRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.Loop;
import com.dbts.glyahhaigeneratecode.model.Entity.AppLoop;
import com.dbts.glyahhaigeneratecode.model.Entity.UserLoopApply;
import com.dbts.glyahhaigeneratecode.model.VO.LoopVO;
import com.dbts.glyahhaigeneratecode.service.LoopService;
import com.dbts.glyahhaigeneratecode.service.support.LoopWorkflowCompiler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.query.RawQueryCondition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Loop 服务层实现。
 *
 * <p>核心职责：Loop 技能实体的 CRUD + 精选/审批 + 导入编译。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>workflowJson 存原始步骤 JSON，compiledPrompt 为编译后的注入文本——两者分离避免每次注入时重算 JSON 解析</li>
 *   <li>删除采用逻辑删除（isDelete=1），但 app_loop 关联记录物理删除——解耦聚合根与关联表</li>
 *   <li>Redis 反向索引 loop:app_ids:{loopId} 用于删除时批量清理应用缓存，避免全表扫描</li>
 * </ul></p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoopServiceImpl implements LoopService {

    private final LoopMapper loopMapper;
    private final AppLoopMapper appLoopMapper;
    private final UserLoopApplyMapper userLoopApplyMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== CRUD ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addLoop(LoopAddRequest req, Long userId) {
        validateWorkflowJson(req.getWorkflowJson());

        Loop loop = new Loop();
        loop.setLoopName(req.getLoopName().trim());
        loop.setDescription(req.getDescription() != null ? req.getDescription().trim() : "");
        loop.setCover(req.getCover() != null ? req.getCover() : "");
        loop.setUserId(userId);
        loop.setPriority(0);
        loop.setWorkflowJson(req.getWorkflowJson());
        loop.setCompiledPrompt(LoopWorkflowCompiler.compile(req.getWorkflowJson()));
        loop.setSourceType(req.getSourceType() != null ? req.getSourceType() : "created");
        loop.setVisibility(req.getVisibility() != null ? req.getVisibility() : "private");
        loop.setIsDelete(0);
        loopMapper.insert(loop);

        // 写 Redis 缓存（compiledPrompt 注入用）
        String cacheKey = "loop:compiled:" + loop.getId();
        stringRedisTemplate.opsForValue().set(
                cacheKey,
                loop.getCompiledPrompt(),
                30L + (long) (Math.random() * 5),
                TimeUnit.MINUTES
        );

        return loop.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "good_loop_page", allEntries = true)
    public void updateLoop(LoopUpdateRequest req, Long userId) {
        Loop loop = loopMapper.selectOneById(req.getId());
        ThrowUtils.throwIf(loop == null || loop.getIsDelete() == 1,
                ErrorCode.NOT_FOUND_ERROR, "Loop不存在");
        ThrowUtils.throwIf(userId == null || !userId.equals(loop.getUserId()),
                ErrorCode.FORBIDDEN_ERROR, "无权修改他人Loop");

        if (StrUtil.isNotBlank(req.getLoopName())) {
            loop.setLoopName(req.getLoopName().trim());
        }
        if (req.getDescription() != null) {
            loop.setDescription(req.getDescription().trim());
        }
        if (req.getCover() != null) {
            loop.setCover(req.getCover());
        }
        if (req.getVisibility() != null) {
            loop.setVisibility(req.getVisibility());
        }
        if (StrUtil.isNotBlank(req.getWorkflowJson())) {
            validateWorkflowJson(req.getWorkflowJson());
            loop.setWorkflowJson(req.getWorkflowJson());
            loop.setCompiledPrompt(LoopWorkflowCompiler.compile(req.getWorkflowJson()));
        }
        loopMapper.update(loop);

        // 删除 Redis 缓存
        stringRedisTemplate.delete("loop:compiled:" + loop.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteLoop(Long id, Long userId) {
        Loop loop = loopMapper.selectOneById(id);
        ThrowUtils.throwIf(loop == null || loop.getIsDelete() == 1,
                ErrorCode.NOT_FOUND_ERROR, "Loop不存在");
        // 校验 userId 非空 + 归属（防止数据库脏数据触发 NPE）
        ThrowUtils.throwIf(userId == null || !userId.equals(loop.getUserId()),
                ErrorCode.FORBIDDEN_ERROR, "无权删除他人Loop");

        // 1. 清理 MySQL 关联表（app_loop 无逻辑删除，直接 delete）
        appLoopMapper.deleteByQuery(
                new QueryWrapper().eq("loop_id", id)
        );

        loop.setIsDelete(1);
        loopMapper.update(loop);

        // 2. 清理 Redis 缓存
        stringRedisTemplate.delete("loop:compiled:" + id);

        // 3. 清理反向索引：读取 loop:app_ids:{loopId} Set，逐一删除 app:loop:ids:{appId}
        Set<String> appIds = stringRedisTemplate.opsForSet().members("loop:app_ids:" + id);
        if (appIds != null && !appIds.isEmpty()) {
            List<String> keysToDelete = appIds.stream()
                    .map(aid -> "app:loop:ids:" + aid)
                    .collect(Collectors.toList());
            if (!keysToDelete.isEmpty()) {
                stringRedisTemplate.delete(keysToDelete);
            }
        }
        stringRedisTemplate.delete("loop:app_ids:" + id);
    }

    @Override
    public LoopVO getLoopVO(Long id) {
        Loop loop = loopMapper.selectOneById(id);
        ThrowUtils.throwIf(loop == null || loop.getIsDelete() == 1,
                ErrorCode.NOT_FOUND_ERROR, "Loop不存在");
        return toVO(loop);
    }

    // ==================== 分页查询 ====================

    @Override
    public List<LoopVO> myListPage(LoopQueryRequest req, Long userId) {
        QueryWrapper qw = new QueryWrapper()
                .eq("user_id", userId)
                .eq("is_delete", 0)
                .orderBy("create_time", false);

        if (StrUtil.isNotBlank(req.getSearchText())) {
            String searchText = "%" + req.getSearchText() + "%";
            qw.and(new RawQueryCondition("(loop_name LIKE ? OR description LIKE ?)", searchText, searchText));
        }

        Page<Loop> page = loopMapper.paginate(Page.of(req.getPageNum(), req.getPageSize()), qw);
        return page.getRecords().stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = "good_loop_page", key = "T(com.dbts.glyahhaigeneratecode.utils.CacheKeyUtils).generateKey(#req)")
    public List<LoopVO> goodListPage(LoopQueryRequest req) {
        QueryWrapper qw = new QueryWrapper()
                .ge("priority", 99)
                .eq("is_delete", 0)
                .eq("visibility", "public")
                .orderBy("priority", false)
                .orderBy("create_time", false);

        if (StrUtil.isNotBlank(req.getSearchText())) {
            String searchText = "%" + req.getSearchText() + "%";
            qw.and(new RawQueryCondition("(loop_name LIKE ? OR description LIKE ?)", searchText, searchText));
        }

        Page<Loop> page = loopMapper.paginate(Page.of(req.getPageNum(), req.getPageSize()), qw);
        return page.getRecords().stream().map(this::toVO).collect(Collectors.toList());
    }

    // ==================== 导入 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long importLoop(String rawContent, Long userId) {
        // 解析 frontmatter + body
        // 格式: ---\nkey: value\n---\n## 标题\nbody
        ThrowUtils.throwIf(StrUtil.isBlank(rawContent),
                ErrorCode.PARAMS_ERROR, "导入内容不能为空");
        String loopName = "未命名技能";
        String description = "";
        String visibility = "public";
        StringBuilder bodyBuilder = new StringBuilder();

        try {
            String trimmed = rawContent.trim();
            if (trimmed.startsWith("---")) {
                int endFront = trimmed.indexOf("---", 3);
                if (endFront > 0) {
                    String front = trimmed.substring(3, endFront).trim();
                    for (String line : front.split("\n")) {
                        line = line.trim();
                        if (line.startsWith("name:")) {
                            loopName = line.substring(5).trim();
                        } else if (line.startsWith("description:")) {
                            description = line.substring(12).trim();
                        } else if (line.startsWith("visibility:")) {
                            visibility = line.substring(11).trim();
                        }
                    }
                    int bodyStart = endFront + 3;
                    if (bodyStart < trimmed.length()) {
                        bodyBuilder.append(trimmed.substring(bodyStart).trim());
                    }
                }
            } else {
                bodyBuilder.append(trimmed);
            }
        } catch (Exception e) {
            log.error("importLoop 内容解析异常", e);
            throw new RuntimeException("导入内容解析失败: " + e.getMessage(), e);
        }

        // 构建 workflowJson
        String body = bodyBuilder.toString().trim();
        String workflowJson = buildWorkflowJsonFromBody(body);

        LoopAddRequest req = new LoopAddRequest();
        req.setLoopName(loopName);
        req.setDescription(description);
        req.setWorkflowJson(workflowJson);
        req.setSourceType("imported");
        req.setVisibility(visibility);
        return addLoop(req, userId);
    }

    // ==================== 申请精选 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyGood(Long loopId, String reason, Long userId) {
        Loop loop = loopMapper.selectOneById(loopId);
        ThrowUtils.throwIf(loop == null || loop.getIsDelete() == 1,
                ErrorCode.NOT_FOUND_ERROR, "Loop不存在");
        // 校验 userId 非空 + 归属（防止脏数据触发 NPE）
        ThrowUtils.throwIf(userId == null || !userId.equals(loop.getUserId()),
                ErrorCode.FORBIDDEN_ERROR, "只能申请自己创建的Loop");

        // 检查是否已有待审申请，防止重复提交
        long pendingCount = userLoopApplyMapper.selectCountByQuery(
                new QueryWrapper().eq("loop_id", loopId)
                        .eq("user_id", userId)
                        .eq("status", 0)
        );
        ThrowUtils.throwIf(pendingCount > 0,
                ErrorCode.OPERATION_ERROR, "已存在待审申请，请勿重复提交");

        UserLoopApply apply = new UserLoopApply();
        apply.setLoopId(loopId);
        apply.setUserId(userId);
        apply.setOperate(1);
        apply.setStatus(0);
        apply.setApplyReason(reason != null ? reason : "");
        userLoopApplyMapper.insert(apply);
    }

    // ==================== 管理员 ====================

    @Override
    public List<LoopVO> adminListPage(LoopQueryRequest req) {
        QueryWrapper qw = new QueryWrapper()
                .orderBy("create_time", false);

        if (StrUtil.isNotBlank(req.getSearchText())) {
            String searchText = "%" + req.getSearchText() + "%";
            qw.and(new RawQueryCondition("(loop_name LIKE ? OR description LIKE ?)", searchText, searchText));
        }

        Page<Loop> page = loopMapper.paginate(Page.of(req.getPageNum(), req.getPageSize()), qw);
        return page.getRecords().stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "good_loop_page", allEntries = true)
    public void adminUpdate(LoopUpdateRequest req) {
        ThrowUtils.throwIf(req.getId() == null, ErrorCode.PARAMS_ERROR, "ID不能为空");
        Loop loop = loopMapper.selectOneById(req.getId());
        ThrowUtils.throwIf(loop == null, ErrorCode.NOT_FOUND_ERROR, "Loop不存在");

        if (StrUtil.isNotBlank(req.getLoopName())) {
            loop.setLoopName(req.getLoopName().trim());
        }
        if (req.getDescription() != null) {
            loop.setDescription(req.getDescription().trim());
        }
        if (req.getVisibility() != null) {
            loop.setVisibility(req.getVisibility());
        }
        if (req.getPriority() != null) {
            loop.setPriority(req.getPriority());
        }
        if (StrUtil.isNotBlank(req.getWorkflowJson())) {
            validateWorkflowJson(req.getWorkflowJson());
            loop.setWorkflowJson(req.getWorkflowJson());
            loop.setCompiledPrompt(LoopWorkflowCompiler.compile(req.getWorkflowJson()));
        }
        loopMapper.update(loop);

        stringRedisTemplate.delete("loop:compiled:" + loop.getId());
    }

    // ==================== 工具方法 ====================

    /**
     * Loop 实体转 VO。
     */
    private LoopVO toVO(Loop loop) {
        LoopVO vo = new LoopVO();
        vo.setId(loop.getId());
        vo.setLoopName(loop.getLoopName());
        vo.setDescription(loop.getDescription());
        vo.setCover(loop.getCover());
        vo.setUserId(loop.getUserId());
        vo.setPriority(loop.getPriority());
        vo.setWorkflowJson(loop.getWorkflowJson());
        vo.setCompiledPrompt(loop.getCompiledPrompt());
        vo.setSourceType(loop.getSourceType());
        vo.setVisibility(loop.getVisibility());
        vo.setCreateTime(loop.getCreateTime());
        vo.setUpdateTime(loop.getUpdateTime());
        return vo;
    }

    /**
     * 校验 workflowJson 合法性。
     */
    private void validateWorkflowJson(String json) {
        ThrowUtils.throwIf(StrUtil.isBlank(json),
                ErrorCode.PARAMS_ERROR, "workflowJson不能为空");
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode steps = root.get("steps");
            ThrowUtils.throwIf(steps == null || !steps.isArray() || steps.isEmpty(),
                    ErrorCode.PARAMS_ERROR, "workflowJson.steps 至少需要 1 项");
            for (JsonNode step : steps) {
                JsonNode keyNode = step.get("key");
                ThrowUtils.throwIf(keyNode == null || keyNode.asText("").isBlank(),
                        ErrorCode.PARAMS_ERROR, "steps 每项的 key 不能为空");
            }
        } catch (Exception e) {
            ThrowUtils.throwIf(true, ErrorCode.PARAMS_ERROR, "workflowJson 格式不合法");
        }
    }

    /**
     * 根据 Markdown body 构建 workflowJson。
     */
    private String buildWorkflowJsonFromBody(String body) {
        if (StrUtil.isBlank(body)) {
            return "{\"templateId\":\"standard_v1\",\"steps\":[{\"key\":\"role\",\"label\":\"角色设定\",\"content\":\"\"}]}";
        }

        // 按 Markdown 标题拆分
        List<Map<String, String>> stepsList = new ArrayList<>();
        String[] lines = body.split("\n");
        String currentLabel = null;
        StringBuilder currentContent = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (currentLabel != null) {
                    Map<String, String> step = new LinkedHashMap<>();
                    step.put("key", mapLabelToKey(currentLabel));
                    step.put("label", currentLabel);
                    step.put("content", currentContent.toString().trim());
                    stepsList.add(step);
                }
                currentLabel = line.substring(3).trim();
                currentContent = new StringBuilder();
            } else {
                if (!currentContent.isEmpty()) {
                    currentContent.append("\n");
                }
                currentContent.append(line);
            }
        }
        if (currentLabel != null) {
            Map<String, String> step = new LinkedHashMap<>();
            step.put("key", mapLabelToKey(currentLabel));
            step.put("label", currentLabel);
            step.put("content", currentContent.toString().trim());
            stepsList.add(step);
        }

        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("templateId", "standard_v1");
            root.put("steps", stepsList);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("buildWorkflowJsonFromBody 序列化失败", e);
            return "{\"templateId\":\"standard_v1\",\"steps\":[{\"key\":\"role\",\"label\":\"角色设定\",\"content\":\"\"}]}";
        }
    }

    /**
     * Markdown 标题映射到步骤 key。
     */
    private String mapLabelToKey(String label) {
        if (label.contains("角色")) return "role";
        if (label.contains("背景") || label.contains("上下文")) return "context";
        if (label.contains("约束") || label.contains("边界")) return "constraints";
        if (label.contains("步骤") || label.contains("工作流") || label.contains("执行")) return "workflow";
        if (label.contains("输出") || label.contains("格式")) return "output";
        return "role";
    }
}
