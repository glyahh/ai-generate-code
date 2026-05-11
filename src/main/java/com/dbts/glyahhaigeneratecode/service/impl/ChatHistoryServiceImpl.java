package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.mapper.ChatHistoryMapper;
import com.dbts.glyahhaigeneratecode.model.DTO.ChatHistoryQueryRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.UserChatHistoryItemVO;
import com.dbts.glyahhaigeneratecode.model.VO.ChatHistoryVO;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.constant.ChatHistoryConstant;
import com.dbts.glyahhaigeneratecode.constant.UserConstant;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 对话历史 服务层实现。
 * <p>
 * 当对话轮数超过 {@link ChatHistoryConstant#MAX_ROUNDS_BEFORE_SUMMARY} 时，会通过
 * {@link #trySummarizeOldestRoundsIfNeeded} 将最早两轮用 AI 总结为一轮并同步到 Redis，
 * 既不超过 Redis 配置条数限制，又保留有效记忆、节省 Token。
 * </p>
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Service
@Slf4j
public class ChatHistoryServiceImpl extends ServiceImpl<ChatHistoryMapper, ChatHistory> implements ChatHistoryService {
    @Resource
    @Lazy
    private AppService appService;

    @Resource(name = "openAiChatModel")
    private ChatModel chatModel;

    /** 用于在 Redis 中管理对话记忆和轮数统计，仅通过压缩 Redis 降低上下文长度（不修改 DB） */
    @Resource
    private ChatMemoryStore chatMemoryStore;

    @Resource
    private JdbcTemplate jdbcTemplate;

    private static final int MEMORY_AI_MESSAGE_MAX_LENGTH = 2400;
    /**
     * 超过此长度后固定截断（不走额外 AI 总结调用）
     * */
    private static final int MEMORY_AI_CODE_BLOCK_KEEP_LENGTH = 2200;
    private static final String MESSAGE_TRUNCATED_SUFFIX = "\n...[message truncated]";
    private static final int DEFAULT_SAFE_MESSAGE_LENGTH = 4000;
    private static final Pattern HTML_BLOCK_PATTERN = Pattern.compile("```html\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_BLOCK_PATTERN = Pattern.compile("```css\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_BLOCK_PATTERN = Pattern.compile("```(?:js|javascript)\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final String WORKFLOW_STAGE_STATUS_PREFIX = "[workflow_stage_status]";
    private static final Pattern WORKFLOW_STAGE_STATUS_LINE_PATTERN = Pattern.compile("(?m)^\\[workflow_stage_status\\].*$");
    private static final List<String> WORKFLOW_FAILURE_KEYWORDS = List.of(
            "失败", "报错", "error", "异常", "中断", "超时", "failed", "exception", "timeout"
    );
    private static final List<String> WORKFLOW_SUCCESS_EVIDENCE_KEYWORDS = List.of(
            "代码已生成完毕", "写入文件", "项目已生成完毕", "代码生成完成", "工作流结束，生成完成"
    );
    private static final Map<String, Pattern> WORKFLOW_STAGE_FAILURE_PATTERNS = Map.of(
            "initializing", Pattern.compile("(初始化|提示词增强|开始准备).{0,40}(失败|报错|error|异常|中断|超时)|(失败|报错|error|异常|中断|超时).{0,40}(初始化|提示词增强|开始准备)", Pattern.CASE_INSENSITIVE),
            "image_collecting", Pattern.compile("(图片|图像|插画|logo|架构图|mermaid).{0,40}(失败|报错|error|异常|中断|超时)|(失败|报错|error|异常|中断|超时).{0,40}(图片|图像|插画|logo|架构图|mermaid)", Pattern.CASE_INSENSITIVE),
            "code_generating", Pattern.compile("(代码生成|项目构建|生成代码|写入文件|写文件|创建文件).{0,40}(失败|报错|error|异常|中断|超时)|(失败|报错|error|异常|中断|超时).{0,40}(代码生成|项目构建|生成代码|写入文件|写文件|创建文件)", Pattern.CASE_INSENSITIVE),
            "code_checking", Pattern.compile("(代码检查|质量检查|代码质检|lint|测试).{0,40}(失败|报错|error|异常|中断|超时)|(失败|报错|error|异常|中断|超时).{0,40}(代码检查|质量检查|代码质检|lint|测试)", Pattern.CASE_INSENSITIVE),
            "ready", Pattern.compile("(就绪|完成|结束).{0,40}(失败|报错|error|异常|中断|超时)|(失败|报错|error|异常|中断|超时).{0,40}(就绪|完成|结束)", Pattern.CASE_INSENSITIVE)
    );

    @Override
    public boolean addChatMessage(Long appId, String message, String messageType, Long userId) {
        return addChatMessage(appId, message, messageType, userId, "SKIP", "NONE");
    }

    @Override
    public boolean addChatMessage(Long appId, String message, String messageType, Long userId, String auditAction, String auditHitRule) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(messageType), ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(auditAction), ErrorCode.PARAMS_ERROR, "审查动作不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(auditHitRule), ErrorCode.PARAMS_ERROR, "命中规则不能为空");
        // 验证消息类型是否有效
        ChatHistoryMessageTypeEnum messageTypeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(messageType);
        ThrowUtils.throwIf(messageTypeEnum == null, ErrorCode.PARAMS_ERROR, "不支持的消息类型: " + messageType);
        ensureAuditColumnsIfMissing(appId);
        String persistedMessage = sanitizeMessageForPersistence(message);
        ChatHistory chatHistory = ChatHistory.builder()
                .appId(appId)
                .message(persistedMessage)
                .messageType(messageType)
                .userId(userId)
                .auditAction(auditAction)
                .auditHitRule(auditHitRule)
                .build();
        return this.save(chatHistory);
    }

    private void ensureAuditColumnsIfMissing(Long appId) {
        boolean actionExists = isColumnExists("auditAction");
        boolean hitRuleExists = isColumnExists("auditHitRule");
        boolean messageIsLongText = isMessageColumnLongText();
        if (!actionExists) {
            jdbcTemplate.execute("ALTER TABLE chat_history ADD COLUMN auditAction varchar(16) NOT NULL DEFAULT 'SKIP' COMMENT '审查动作：ALLOW/REJECT/SKIP' AFTER userId");
        }
        if (!hitRuleExists) {
            jdbcTemplate.execute("ALTER TABLE chat_history ADD COLUMN auditHitRule varchar(64) NOT NULL DEFAULT 'NONE' COMMENT '命中审查规则编码' AFTER auditAction");
        }
        if (!messageIsLongText) {
            jdbcTemplate.execute("ALTER TABLE chat_history MODIFY COLUMN message LONGTEXT NOT NULL COMMENT '消息'");
        }
    }

    private String sanitizeMessageForPersistence(String message) {
        if (message == null) {
            return null;
        }
        long columnMaxLength = getMessageColumnCharacterLimit();
        int safeLimit;
        if (columnMaxLength <= 0 || columnMaxLength > Integer.MAX_VALUE) {
            safeLimit = Integer.MAX_VALUE;
        } else {
            safeLimit = (int) columnMaxLength;
        }
        if (safeLimit <= MESSAGE_TRUNCATED_SUFFIX.length()) {
            safeLimit = DEFAULT_SAFE_MESSAGE_LENGTH;
        }
        if (message.length() <= safeLimit) {
            return message;
        }
        int keepLength = Math.max(1, safeLimit - MESSAGE_TRUNCATED_SUFFIX.length());
        return message.substring(0, keepLength) + MESSAGE_TRUNCATED_SUFFIX;
    }

    private boolean isColumnExists(String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_history' AND COLUMN_NAME = ?",
                Integer.class,
                columnName
        );
        return count != null && count > 0;
    }

    private boolean isMessageColumnLongText() {
        String dataType = jdbcTemplate.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_history' AND COLUMN_NAME = 'message'",
                String.class
        );
        return "longtext".equalsIgnoreCase(StrUtil.blankToDefault(dataType, ""));
    }

    private long getMessageColumnCharacterLimit() {
        Long maxLength = jdbcTemplate.queryForObject(
                "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_history' AND COLUMN_NAME = 'message'",
                Long.class
        );
        return maxLength == null ? DEFAULT_SAFE_MESSAGE_LENGTH : maxLength;
    }

    @Override
    public Page<ChatHistory> listAppChatHistoryByPage(Long appId, int pageSize,
                                                      LocalDateTime lastCreateTime,
                                                      User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > 50, ErrorCode.PARAMS_ERROR, "页面大小必须在1-50之间");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        // 验证权限：只有应用创建者和管理员可以查看
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        boolean isCreator = app.getUserId().equals(loginUser.getId());
        ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权查看该应用的对话历史");

        // 构建查询条件
        ChatHistoryQueryRequest queryRequest = new ChatHistoryQueryRequest();
        queryRequest.setAppId(appId);
        queryRequest.setLastCreateTime(lastCreateTime);
        QueryWrapper queryWrapper = this.buildQueryWrapper(queryRequest);

        // 查询数据
        Page<ChatHistory> page = this.page(Page.of(1, pageSize), queryWrapper);

        // 为 workflow AI 消息追加五阶段状态标记行
        appendWorkflowStageStatusForHistoryPage(page);
        return page;
    }


    @Override
    public Page<UserChatHistoryItemVO> listMyChatHistoryByPage(ChatHistoryQueryRequest queryRequest, User loginUser) {
        // 1. 校验查询参数和登录态，确保只在合法请求下继续执行分页查询。
        ThrowUtils.throwIf(queryRequest == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        // 2. 读取分页参数并校验分页大小，得到后续数据库分页查询所需的页码与容量。
        int pageNum = queryRequest.getPageNum();
        int pageSize = queryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize <= 0, ErrorCode.PARAMS_ERROR, "pageSize 必须大于 0");

        // 3. 基于原始请求构造安全查询对象，并强制写入当前登录用户ID，避免越权查询他人数据。
        ChatHistoryQueryRequest safeRequest = new ChatHistoryQueryRequest();
        safeRequest.setPageNum(pageNum);
        safeRequest.setPageSize(pageSize);
        safeRequest.setMessageType(queryRequest.getMessageType());
        safeRequest.setAppId(queryRequest.getAppId());
        safeRequest.setUserId(loginUser.getId());

        // 4. 生成查询条件并执行分页查询，得到当前页的对话历史实体数据。
        QueryWrapper queryWrapper = buildQueryWrapper(safeRequest);
        Page<ChatHistory> page = this.page(Page.of(pageNum, pageSize), queryWrapper);

        // 5. 提取分页记录并收集其中涉及的应用ID集合，用于批量补全应用名称。
        List<ChatHistory> records = page.getRecords() == null ? Collections.emptyList() : page.getRecords();
        Set<Long> appIds = records.stream()
                .map(ChatHistory::getAppId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 6. 批量查询应用信息并构建 appId -> appName 映射，减少逐条查库带来的性能损耗。
        Map<Long, String> appNameMap = new HashMap<>();
        if (!appIds.isEmpty()) {
            List<App> apps = appService.listByIds(appIds);
            if (apps != null && !apps.isEmpty()) {
                for (App app : apps) {
                    if (app == null || app.getId() == null) {
                        continue;
                    }
                    appNameMap.putIfAbsent(app.getId(), app.getAppName());
                }
            }
        }

        // 7. 将数据库实体转换为前端展示VO，补齐应用名称并设置默认占位值。
        List<UserChatHistoryItemVO> voList = records.stream().map(item -> {
            UserChatHistoryItemVO vo = new UserChatHistoryItemVO();
            vo.setMessage(item.getMessage());
            vo.setMessageType(item.getMessageType());
            vo.setAppId(item.getAppId());
            vo.setCreateTime(item.getCreateTime());
            vo.setAppName(appNameMap.getOrDefault(item.getAppId(), "-"));
            return vo;
        }).toList();

        // 8. 组装VO分页对象并返回，保持总条数与分页参数和原始查询结果一致。
        Page<UserChatHistoryItemVO> voPage = new Page<>(pageNum, pageSize, page.getTotalRow());
        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public boolean removeByAppId(Long appId) {
        if (appId == null || appId <= 0) {
            return false;
        }
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(ChatHistory::getAppId, appId);
        return this.remove(queryWrapper);
    }

    @Override
    public QueryWrapper buildQueryWrapper(ChatHistoryQueryRequest queryRequest) {
        if (queryRequest == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "查询请求参数为空");
        }

        QueryWrapper queryWrapper = new QueryWrapper();

        Long id = queryRequest.getId();
        String message = queryRequest.getMessage();
        String messageType = queryRequest.getMessageType();
        Long appId = queryRequest.getAppId();
        Long userId = queryRequest.getUserId();
        LocalDateTime lastCreateTime = queryRequest.getLastCreateTime();
        String sortField = queryRequest.getSortField();
        String sortOrder = queryRequest.getSortOrder();

        if (appId != null) {
            queryWrapper.eq(ChatHistory::getAppId, appId);
        }
        if (userId != null) {
            queryWrapper.eq(ChatHistory::getUserId, userId);
        }
        if (id != null) {
            queryWrapper.eq(ChatHistory::getId, id);
        }
        if (StrUtil.isNotBlank(message)) {
            queryWrapper.like(ChatHistory::getMessage, message);
        }
        if (StrUtil.isNotBlank(messageType)) {
            queryWrapper.eq(ChatHistory::getMessageType, messageType);
        }
        // 创建时间小于等于 lastCreateTime
        if (lastCreateTime != null) {
            queryWrapper.le(ChatHistory::getCreateTime, lastCreateTime);
        }

        // 如果有排序字段，则设置排序为对应的排序顺序
        if (StrUtil.isNotBlank(sortField) && StrUtil.isNotBlank(sortOrder)) {
            queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
        } else {
            // 否则按创建时间降序
            queryWrapper.orderBy("createTime", false);
        }

        return queryWrapper;
    }

    @Override
    public ChatHistoryVO getChatHistoryVO(ChatHistory chatHistory) {
        if (chatHistory == null) {
            return null;
        }
        ChatHistoryVO chatHistoryVO = new ChatHistoryVO();
        BeanUtil.copyProperties(chatHistory, chatHistoryVO);
        return chatHistoryVO;
    }

    /**
     * 在历史回显分页结果中为 workflow AI 消息追加五阶段状态标记行。
     * @param page 历史分页结果
     * @return 无
     */
    private void appendWorkflowStageStatusForHistoryPage(Page<ChatHistory> page) {
        if (page == null || page.getRecords() == null || page.getRecords().isEmpty()) {
            return;
        }
        for (ChatHistory history : page.getRecords()) {
            if (history == null) {
                continue;
            }
            if (!ChatHistoryMessageTypeEnum.AI.getValue().equalsIgnoreCase(StrUtil.blankToDefault(history.getMessageType(), ""))) {
                continue;
            }
            String message = history.getMessage();
            if (!isWorkflowRelatedHistoryMessage(message)) {
                continue;
            }
            // 1. 先清理可能存在的旧标记，避免分页重复请求导致多次拼接。
            String cleanedMessage = WORKFLOW_STAGE_STATUS_LINE_PATTERN.matcher(StrUtil.blankToDefault(message, "")).replaceAll("").trim();
            // 2. 再基于当前 message 解析阶段状态并拼接到尾部，仅影响历史回显，不改 DB 原文。
            String markerLine = buildWorkflowStageStatusMarkerLine(cleanedMessage);
            history.setMessage(cleanedMessage + "\n" + markerLine);
        }
    }

    /**
     * 判断历史消息是否属于 workflow 回显消息，避免普通对话误挂工作流卡片。
     * @param message 历史消息文本
     * @return true-属于 workflow；false-普通消息
     */
    private boolean isWorkflowRelatedHistoryMessage(String message) {
        if (StrUtil.isBlank(message)) {
            return false;
        }
        String lowerText = message.toLowerCase(Locale.ROOT);
        return lowerText.contains("[workflow]")
                || lowerText.contains("[workflow_notice]")
                || lowerText.contains("[workflow_stage_status]")
                || lowerText.contains("[选择工具]")
                || lowerText.contains("[工具调用]")
                || lowerText.contains("工作流");
    }

    /**
     * 将 workflow 历史消息映射为固定五阶段状态行，供前端直接渲染绿/红/灰字体。
     * @param message workflow 历史消息文本
     * @return 阶段状态标记行
     */
    private String buildWorkflowStageStatusMarkerLine(String message) {
        String safeMessage = StrUtil.blankToDefault(message, "");
        String lowerText = safeMessage.toLowerCase(Locale.ROOT);
        String[] statuses = {"success", "success", "success", "success", "success"};

        // 1. 先按“阶段关键词 + 失败关键词”定位失败阶段，命中则该阶段红色、后续灰色。
        int failedStageIndex = resolveFailedWorkflowStageIndex(safeMessage, lowerText);
        if (failedStageIndex >= 0) {
            for (int i = failedStageIndex; i < statuses.length; i++) {
                statuses[i] = (i == failedStageIndex) ? "failed" : "pending";
            }
        }
        // 2. 未命中失败时按需求保持全绿；成功证据关键词用于增强语义稳定性，避免误判。
        if (failedStageIndex < 0 && containsAnyKeyword(lowerText, WORKFLOW_SUCCESS_EVIDENCE_KEYWORDS)) {
            statuses = new String[]{"success", "success", "success", "success", "success"};
        }

        return String.format(Locale.ROOT,
                "%s initializing=%s;image_collecting=%s;code_generating=%s;code_checking=%s;ready=%s",
                WORKFLOW_STAGE_STATUS_PREFIX, statuses[0], statuses[1], statuses[2], statuses[3], statuses[4]);
    }

    /**
     * 根据消息文本判定 workflow 失败阶段索引，顺序固定为初始化/图片/代码生成/代码检查/就绪。
     * @param rawText 原始消息文本
     * @param lowerText 小写消息文本
     * @return 失败阶段索引；未命中返回 -1
     */
    private int resolveFailedWorkflowStageIndex(String rawText, String lowerText) {
        if (StrUtil.isBlank(rawText) || StrUtil.isBlank(lowerText) || !containsAnyKeyword(lowerText, WORKFLOW_FAILURE_KEYWORDS)) {
            return -1;
        }
        // 1. 优先用阶段映射规则定位失败点，保证“前绿、当前红、后灰”的可解释性。
        if (WORKFLOW_STAGE_FAILURE_PATTERNS.get("initializing").matcher(rawText).find()) {
            return 0;
        }
        if (WORKFLOW_STAGE_FAILURE_PATTERNS.get("image_collecting").matcher(rawText).find()) {
            return 1;
        }
        if (WORKFLOW_STAGE_FAILURE_PATTERNS.get("code_generating").matcher(rawText).find()) {
            return 2;
        }
        if (WORKFLOW_STAGE_FAILURE_PATTERNS.get("code_checking").matcher(rawText).find()) {
            return 3;
        }
        if (WORKFLOW_STAGE_FAILURE_PATTERNS.get("ready").matcher(rawText).find()) {
            return 4;
        }
        // 2. 兜底：出现失败词但未命中具体阶段时，按代码生成阶段失败处理。
        return 2;
    }

    /**
     * 判断文本是否命中任一关键词。
     * @param lowerText 已小写化文本
     * @param keywords 关键词列表
     * @return true-命中；false-未命中
     */
    private boolean containsAnyKeyword(String lowerText, List<String> keywords) {
        if (StrUtil.isBlank(lowerText) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (StrUtil.isBlank(keyword)) {
                continue;
            }
            if (lowerText.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<ChatHistoryVO> getChatHistoryVOList(List<ChatHistory> chatHistoryList) {
        if (chatHistoryList == null || chatHistoryList.isEmpty()) {
            return Collections.emptyList();
        }
        return chatHistoryList.stream()
                .map(this::getChatHistoryVO)
                .collect(Collectors.toList());
    }

    @Override
    public int turnHistoryToMemory(Long addId, MessageWindowChatMemory messageWindowChatMemory, int maxCount) {
        // 校验参数
        ThrowUtils.throwIf(addId == null || addId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(messageWindowChatMemory == null, ErrorCode.PARAMS_ERROR, "聊天内存不能为空");
        ThrowUtils.throwIf(maxCount <= 0, ErrorCode.PARAMS_ERROR, "最大数量必须大于0");

        // 获取应用的所有history,注意从1开始获取,抛开用户刚发送的那条
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(ChatHistory::getAppId, addId);
        // 按创建时间倒序，最新的消息在最前面
        queryWrapper.orderBy(ChatHistory::getCreateTime, false);
        queryWrapper.limit(1, maxCount);
        List<ChatHistory> historyList = this.list(queryWrapper);

        if (historyList == null || historyList.isEmpty()) {
            return 0;
        }

        // 反转历史消息（按时间从早到晚写入内存）
        Collections.reverse(historyList);

        // 清空Redis中的全部消息
        messageWindowChatMemory.clear();

        // 拿到改对话的系统提示词 systemPromptForFilter
        String systemPromptForFilter = null;
        CodeGenTypeEnum appCodeGenTypeEnum = null;
        try {
            // 优先按应用 codeGenType 取对应系统提示词（与 appendSystemPromptToMemory 一致）
            App app = appService.getById(addId);
            if (app != null) {
                CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
                appCodeGenTypeEnum = codeGenTypeEnum;
                String promptPath = null;
                if (codeGenTypeEnum != null) {
                    switch (codeGenTypeEnum) {
                        case HTML -> promptPath = "Prompt/Single_File_Prompt.txt";
                        case MULTI_FILE -> promptPath = "Prompt/Various_File_Prompt.txt";
                        case VUE -> promptPath = "Prompt/Vue_File_Prompt.txt";
                        default -> { }
                    }
                }
                if (StrUtil.isNotBlank(promptPath)) {
                    systemPromptForFilter = readPromptFromClasspath(promptPath);
                }
            }
        } catch (Exception e) {
            log.debug("读取系统提示词用于过滤失败，appId={}", addId, e);
        }

        // 一次添加进入缓存
        int restoredCount = 0;
        for (ChatHistory history : historyList) {
            ChatHistoryMessageTypeEnum typeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(history.getMessageType());
            if (typeEnum == null) {
                continue;
            }
            switch (typeEnum) {
                case USER:
                    messageWindowChatMemory.add(new UserMessage(history.getMessage()));
                    restoredCount++;
                    break;
                case AI:
                    // 当ai的消息内容和系统提示词 systemPromptForFilter 相同，则不再写入 Redis
                    if (StrUtil.isNotBlank(systemPromptForFilter)
                            && StrUtil.isNotBlank(history.getMessage())
                            && history.getMessage().trim().equals(systemPromptForFilter.trim())) {
                        log.debug("跳过写入 Redis：MySQL 中该条 AI 消息为系统提示词，appId={}", addId);
                        break;
                    }
                    // 仅对 HTML / MULTI_FILE 的超长历史 AI 代码做内存压缩，降低后续请求 token 消耗
                    String aiMessageForMemory = compactAiMessageForMemory(history.getMessage(), appCodeGenTypeEnum);
                    messageWindowChatMemory.add(new AiMessage(aiMessageForMemory));
                    restoredCount++;
                    break;
                default:
                    // ERROR 等类型不写入内存
                    log.error("在将history写入Redis管理的ChatMemory出错, 出现error枚举类");
                    break;
            }
        }
        log.info("已将对话历史加载到内存，appId={}, count={}", addId, restoredCount);

        // system prompt 由 aiCodeGeneratorService 层的 @SystemMessage 注入，此处不再追加，避免重复注入
        // appendSystemPromptToMemory(addId, messageWindowChatMemory);

        return restoredCount;
    }

    /**
     * 将历史 AI 长消息压缩后写入 ChatMemory（仅影响后续发给模型的上下文，不改 DB 原文）：
     * - 仅 HTML / MULTI_FILE 生效
     * - 优先保留 html/css/js 三个代码块的前缀片段
     * - 其余类型或短消息保持原样，避免影响现有行为
     */
    private String compactAiMessageForMemory(String rawMessage, CodeGenTypeEnum codeGenTypeEnum) {
        if (StrUtil.isBlank(rawMessage)) {
            return rawMessage;
        }
        if (codeGenTypeEnum != CodeGenTypeEnum.HTML && codeGenTypeEnum != CodeGenTypeEnum.MULTI_FILE) {
            return rawMessage;
        }
        if (rawMessage.length() <= MEMORY_AI_MESSAGE_MAX_LENGTH) {
            return rawMessage;
        }

        String html = extractAndTrimCodeBlock(rawMessage, HTML_BLOCK_PATTERN, MEMORY_AI_CODE_BLOCK_KEEP_LENGTH, "HTML");
        String css = extractAndTrimCodeBlock(rawMessage, CSS_BLOCK_PATTERN, MEMORY_AI_CODE_BLOCK_KEEP_LENGTH, "CSS");
        String js = extractAndTrimCodeBlock(rawMessage, JS_BLOCK_PATTERN, MEMORY_AI_CODE_BLOCK_KEEP_LENGTH, "JavaScript");

        StringBuilder sb = new StringBuilder(2800);
        sb.append("[历史AI代码已压缩，仅用于降低上下文 token]\n");
        sb.append("原消息较长，以下为关键代码片段摘要：\n");
        if (StrUtil.isNotBlank(html)) {
            sb.append("```html\n").append(html).append("\n```\n");
        }
        if (StrUtil.isNotBlank(css)) {
            sb.append("```css\n").append(css).append("\n```\n");
        }
        if (StrUtil.isNotBlank(js)) {
            sb.append("```javascript\n").append(js).append("\n```\n");
        }

        // 若未提取到代码块，退化为头部截断，保证仍有历史语义
        if (sb.length() < 80) {
            String head = rawMessage.substring(0, Math.min(MEMORY_AI_MESSAGE_MAX_LENGTH, rawMessage.length()));
            return head + "\n...（历史内容已截断）";
        }
        return sb.toString();
    }

    private String extractAndTrimCodeBlock(String message, Pattern pattern, int maxLen, String langLabel) {
        Matcher matcher = pattern.matcher(message);
        if (!matcher.find()) {
            return "";
        }
        String code = StrUtil.blankToDefault(matcher.group(1), "");
        if (code.length() <= maxLen) {
            return code;
        }
        String safePrefix = "/* 历史" + langLabel + "代码片段（已截断） */\n";
        return safePrefix + code.substring(0, maxLen) + "\n// ...历史代码片段已截断";
    }


    /**
     * 根据应用的代码生成类型，将对应的系统 Prompt 追加到 Redis 管理的对话内存中。
     * HTML / MULTI_FILE / VUE 分别只追加自身对应的系统提示词，避免不同模式之间相互干扰。
     */
    private void appendSystemPromptToMemory(Long appId, MessageWindowChatMemory messageWindowChatMemory) {
        App app = appService.getById(appId);
        if (app == null) {
            log.warn("追加系统提示词失败，应用不存在, appId={}", appId);
            return;
        }
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenTypeEnum == null) {
            log.warn("追加系统提示词失败，应用的 codeGenType 无效, appId={}, codeGenType={}", appId, app.getCodeGenType());
            return;
        }

        String promptPath;
        // 按代码生成类型选择不同的系统 Prompt
        switch (codeGenTypeEnum) {
            case HTML -> promptPath = "Prompt/Single_File_Prompt.txt";
            case MULTI_FILE -> promptPath = "Prompt/Various_File_Prompt.txt";
            case VUE -> promptPath = "Prompt/Vue_File_Prompt.txt";
            default -> {
                log.warn("追加系统提示词失败，不支持的 codeGenType, appId={}, codeGenType={}", appId, codeGenTypeEnum);
                return;
            }
        }

        String systemPrompt = readPromptFromClasspath(promptPath);
        if (StrUtil.isNotBlank(systemPrompt)) {
            messageWindowChatMemory.add(new AiMessage(systemPrompt));
        }
    }

    /**
     * 从 classpath 读取系统 Prompt，避免硬编码磁盘绝对路径，适配不同部署环境。
     */
    private String readPromptFromClasspath(String classpathLocation) {
        if (StrUtil.isBlank(classpathLocation)) {
            log.error("读取系统提示词失败，classpathLocation 为空");
            throw new MyException(ErrorCode.SYSTEM_ERROR, "读取系统提示词失败：路径为空");
        }

        // 拿到除了出车,空格等的字符串,更具有健壮性
        String normalized = StrUtil.trim(classpathLocation);
        if (StrUtil.startWithIgnoreCase(normalized, "classpath:")) {
            normalized = StrUtil.removePrefixIgnoreCase(normalized, "classpath:");
        }
        normalized = StrUtil.removePrefix(normalized, "/");

        try (InputStream inputStream = new ClassPathResource(normalized).getInputStream()) {
            return IoUtil.readUtf8(inputStream);
        } catch (Exception e) {
            log.error("读取系统提示词失败, path={}", normalized, e);
            throw new MyException(ErrorCode.SYSTEM_ERROR, "读取系统提示词失败");
        }
    }

    @Override
    public List<ChatHistoryVO> listAllByAppIdForExport(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        boolean isCreator = app.getUserId().equals(loginUser.getId());
        ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权导出该应用的对话历史");

        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(ChatHistory::getAppId, appId);
        queryWrapper.orderBy(ChatHistory::getCreateTime, true);
        List<ChatHistory> list = this.list(queryWrapper);
        log.info("导出对话历史，appId={}, count={}", appId, list != null ? list.size() : 0);
        return getChatHistoryVOList(list != null ? list : Collections.emptyList());
    }

    @Override
    public int countRoundsByAppId(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        boolean isCreator = app.getUserId().equals(loginUser.getId());
        ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权查看该应用的对话轮数");

        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(ChatHistory::getAppId, appId);
        queryWrapper.eq(ChatHistory::getMessageType, ChatHistoryMessageTypeEnum.USER.getValue());
        long count = this.count(queryWrapper);
        int redisRounds = countRoundsByAppIdInternal(appId);
        if (redisRounds != (int) count) {
            log.warn("对话轮数存在 Redis/DB 偏差，appId={}, dbRounds={}, redisRounds={}", appId, count, redisRounds);
        } else {
            log.info("统计对话轮数，appId={}, rounds={}, redisAligned=true", appId, count);
        }
        return (int) count;
    }

    @Override
    public void trySummarizeOldestRoundsIfNeeded(Long appId, Long userId, String triggerReason) {
        if (appId == null || appId <= 0 || userId == null) {
            return;
        }
        String reason = StrUtil.blankToDefault(triggerReason, "unknown");
        // 仅统计该应用下用户消息条数作为「轮数」，不校验登录态（内部在对话完成后调用）
        int roundCount = countRoundsByAppIdInternal(appId);
        int beforeRoundCount = roundCount;
        if (roundCount <= ChatHistoryConstant.MAX_ROUNDS_BEFORE_SUMMARY) {
            log.info("会话级总结跳过，compressType=conversation_summary, triggerReason={}, appId={}, roundCount={}, threshold={}",
                    reason, appId, roundCount, ChatHistoryConstant.MAX_ROUNDS_BEFORE_SUMMARY);
            return;
        }
        // 关键：每多 2 轮就合并最早 2 轮为 1 轮，循环直到不超过 20 轮（例如 26 轮 → 合并 6 次 → 20 轮）
        int mergedCount = 0;
        while (roundCount > ChatHistoryConstant.MAX_ROUNDS_BEFORE_SUMMARY) {
            // 获取最早 4 条消息
            List<ChatHistory> oldestFour = listOldestMessagesForMerge(appId, ChatHistoryConstant.MESSAGES_PER_MERGE);
            if (oldestFour == null || oldestFour.size() < ChatHistoryConstant.MESSAGES_PER_MERGE) {
                log.warn("对话合并：不足 {} 条最早消息，跳过本次合并，appId={}", ChatHistoryConstant.MESSAGES_PER_MERGE, appId);
                break;
            }
            String userSummary;
            String aiSummary;
            try {
                String summaryText = summarizeTwoRoundsWithAi(oldestFour);
                String[] parsed = parseSummaryResponse(summaryText);
                userSummary = parsed[0];
                aiSummary = parsed[1];
            } catch (Exception e) {
                log.error("AI 总结前两轮对话失败，跳过本次合并，appId={}", appId, e);
                break;
            }
            LocalDateTime oldestCreateTime = oldestFour.getFirst().getCreateTime();
//            List<Long> toRemoveIds = oldestFour.stream().map(ChatHistory::getId).toList();
//            this.removeByIds(toRemoveIds);
//            ChatHistory userSumEntity = ChatHistory.builder()
//                    .appId(appId)
//                    .userId(userId)
//                    .message(userSummary)
//                    .messageType(ChatHistoryMessageTypeEnum.USER.getValue())
//                    .createTime(oldestCreateTime)
//                    .build();
//            ChatHistory aiSumEntity = ChatHistory.builder()
//                    .appId(appId)
//                    .userId(userId)
//                    .message(aiSummary)
//                    .messageType(ChatHistoryMessageTypeEnum.AI.getValue())
//                    .createTime(oldestCreateTime)
//                    .build();
//            this.save(userSumEntity);
//            this.save(aiSumEntity);
            syncRedisAfterMerge(appId, userSummary, aiSummary);
            roundCount--;
            mergedCount++;
            log.info("已将最早两轮合并为一轮并同步 Redis，appId={}, 当前约轮数={}", appId, roundCount);
        }
        log.info("会话级总结完成，compressType=conversation_summary, triggerReason={}, appId={}, beforeRoundCount={}, afterRoundCount={}, mergedCount={}",
                reason, appId, beforeRoundCount, roundCount, mergedCount);
    }

    /**
     * 仅按 appId 统计 Redis 中用户消息条数（即「轮数」），不做权限校验，供内部压缩逻辑使用。
     * 若 Redis 统计失败，则回退到基于 DB 的统计，避免影响整体流程。
     */
    private int countRoundsByAppIdInternal(Long appId) {
        try {
            List<ChatMessage> messages = chatMemoryStore.getMessages(appId);
            if (messages == null || messages.isEmpty()) {
                log.info("从 Redis 统计轮数，appId={}, rounds=0, reason=empty", appId);
                return 0;
            }
            int userCount = 0;
            for (ChatMessage message : messages) {
                if (message instanceof UserMessage) {
                    userCount++;
                }
            }
            log.info("从 Redis 统计轮数，appId={}, rounds={}", appId, userCount);
            return userCount;
        } catch (Exception e) {
            log.warn("从 Redis 统计对话轮数失败，回退到数据库统计, appId={}", appId, e);
            QueryWrapper q = new QueryWrapper();
            q.eq(ChatHistory::getAppId, appId);
            q.eq(ChatHistory::getMessageType, ChatHistoryMessageTypeEnum.USER.getValue());
            int dbRounds = (int) this.count(q);
            log.info("Redis 统计失败后回退 DB 轮数，appId={}, dbRounds={}", appId, dbRounds);
            return dbRounds;
        }
    }

    /**
     * 按创建时间正序取该应用下最早的若干条消息（用于合并为「一轮」）。
     */
    private List<ChatHistory> listOldestMessagesForMerge(Long appId, int limit) {
        QueryWrapper q = new QueryWrapper();
        q.eq(ChatHistory::getAppId, appId);
        q.orderBy(ChatHistory::getCreateTime, true);
        q.limit(limit);
        return this.list(q);
    }

    /**
     * 调用大模型将两轮对话（4 条消息）总结为「用户总结 + AI 总结」的简短文本，便于解析。
     * 使用简单字符串拼接构造 prompt，避免引入额外依赖。
     */
    private String summarizeTwoRoundsWithAi(List<ChatHistory> fourMessages) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < fourMessages.size(); i++) {
            ChatHistory m = fourMessages.get(i);
            String role = ChatHistoryMessageTypeEnum.USER.getValue().equals(m.getMessageType()) ? "用户" : "AI";
            content.append("第").append((i / 2) + 1).append("轮-").append(role).append("：").append(m.getMessage()).append("\n");
        }
        String prompt = "你是一个对话总结助手。请将以下两轮对话压缩为一段简要总结，严格按以下格式输出，不要其他内容：\n"
                + "【用户总结】用户的主要问题和诉求摘要\n"
                + "【AI总结】AI的回复要点摘要\n\n"
                + "对话内容：\n" + content;
        return chatModel.chat(prompt);
    }

    /**
     * 从 AI 返回文本中解析出「用户总结」和「AI总结」两段。若格式不符则退回简短占位，避免写入脏数据。
     */
    private String[] parseSummaryResponse(String summaryText) {
        String userSummary = "（历史对话摘要）";
        String aiSummary = "（历史回复摘要）";
        if (StrUtil.isBlank(summaryText)) {
            return new String[]{userSummary, aiSummary};
        }
        String markerUser = "【用户总结】";
        String markerAi = "【AI总结】";
        int idxUser = summaryText.indexOf(markerUser);
        int idxAi = summaryText.indexOf(markerAi);
        if (idxUser >= 0 && idxAi > idxUser) {
            userSummary = summaryText.substring(idxUser + markerUser.length(), idxAi).trim();
            if (userSummary.length() > 2000) {
                userSummary = userSummary.substring(0, 2000);
            }
            aiSummary = summaryText.substring(idxAi + markerAi.length()).trim();
            if (aiSummary.length() > 2000) {
                aiSummary = aiSummary.substring(0, 2000);
            }
        }
        return new String[]{userSummary, aiSummary};
    }

    /**
     * 将 Redis 中该应用的前 4 条消息替换为 2 条（用户总结、AI 总结），仅在 Redis 中压缩上下文，不修改 DB。
     * 使用 ChatMemoryStore 的 getMessages + updateMessages，保证与 MessageWindowChatMemory 共用同一存储。
     */
    private void syncRedisAfterMerge(Long appId, String userSummary, String aiSummary) {
        try {
            List<ChatMessage> messages = chatMemoryStore.getMessages(appId);
            if (messages == null || messages.size() < ChatHistoryConstant.MESSAGES_PER_MERGE) {
                return;
            }
            List<ChatMessage> newList = new ArrayList<>(messages);
            for (int i = 0; i < ChatHistoryConstant.MESSAGES_PER_MERGE; i++) {
                newList.remove(0);
            }
            // 注意：先插 AI 再插 User，这样 insert(0, User) 后顺序为 [User, AI, ...]，与「一轮=用户+AI」约定顺序保持一致
            newList.add(0, new AiMessage(aiSummary));
            newList.add(0, new UserMessage(userSummary));
            chatMemoryStore.updateMessages(appId, newList);
            log.info("Redis memory 已按摘要重建，appId={}, beforeCount={}, afterCount={}",
                    appId, messages.size(), newList.size());
        } catch (Exception e) {
            log.error("同步 Redis 对话记忆失败，appId={}", appId, e);
        }
    }

    @Override
    public void compactMemoryMessagesIfNeeded(Long appId, CodeGenTypeEnum codeGenTypeEnum, String triggerReason) {
        if (appId == null || appId <= 0) {
            return;
        }
        if (codeGenTypeEnum != CodeGenTypeEnum.HTML && codeGenTypeEnum != CodeGenTypeEnum.MULTI_FILE) {
            log.info("消息级截断跳过，compressType=message_truncate, triggerReason={}, appId={}, codeGenType={}",
                    StrUtil.blankToDefault(triggerReason, "unknown"), appId,
                    codeGenTypeEnum == null ? "null" : codeGenTypeEnum.getValue());
            return;
        }
        try {
            List<ChatMessage> messages = chatMemoryStore.getMessages(appId);
            if (messages == null || messages.isEmpty()) {
                log.info("消息级截断跳过，compressType=message_truncate, triggerReason={}, appId={}, codeGenType={}, beforeCount=0",
                        StrUtil.blankToDefault(triggerReason, "unknown"), appId, codeGenTypeEnum.getValue());
                return;
            }
            boolean changed = false;
            List<ChatMessage> newList = new ArrayList<>(messages.size());
            int beforeCount = messages.size();
            for (ChatMessage message : messages) {
                if (message instanceof AiMessage aiMessage) {
                    String raw = aiMessage.text();
                    String compacted = compactAiMessageForMemory(raw, codeGenTypeEnum);
                    if (!Objects.equals(raw, compacted)) {
                        changed = true;
                    }
                    newList.add(new AiMessage(compacted));
                } else {
                    newList.add(message);
                }
            }
            if (changed) {
                chatMemoryStore.updateMessages(appId, newList);
                log.info("消息级截断完成，compressType=message_truncate, triggerReason={}, appId={}, codeGenType={}, beforeCount={}, afterCount={}, changed=true",
                        StrUtil.blankToDefault(triggerReason, "unknown"), appId, codeGenTypeEnum.getValue(), beforeCount, newList.size());
            } else {
                log.info("消息级截断跳过，compressType=message_truncate, triggerReason={}, appId={}, codeGenType={}, beforeCount={}, afterCount={}, changed=false",
                        StrUtil.blankToDefault(triggerReason, "unknown"), appId, codeGenTypeEnum.getValue(), beforeCount, newList.size());
            }
        } catch (Exception e) {
            log.warn("在线压缩 Redis 历史上下文失败，appId={}", appId, e);
        }
    }

    @Override
    public boolean removeLatestFailedAiMessageForRetry(Long appId, CodeGenTypeEnum codeGenTypeEnum, String triggerReason) {
        if (appId == null || appId <= 0) {
            return false;
        }
        if (codeGenTypeEnum != CodeGenTypeEnum.HTML && codeGenTypeEnum != CodeGenTypeEnum.MULTI_FILE) {
            log.info("失败轮清理跳过，cleanupType=failed_round_cleanup, triggerReason={}, appId={}, codeGenType={}",
                    StrUtil.blankToDefault(triggerReason, "unknown"), appId,
                    codeGenTypeEnum == null ? "null" : codeGenTypeEnum.getValue());
            return false;
        }
        try {
            List<ChatMessage> messages = chatMemoryStore.getMessages(appId);
            if (messages == null || messages.isEmpty()) {
                return false;
            }
            int removeIndex = -1;
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage msg = messages.get(i);
                if (msg instanceof AiMessage aiMessage) {
                    String text = aiMessage.text();
                    if (StrUtil.isNotBlank(text) && text.length() > MEMORY_AI_MESSAGE_MAX_LENGTH) {
                        removeIndex = i;
                        break;
                    }
                }
            }
            if (removeIndex < 0) {
                log.info("失败轮清理未命中，cleanupType=failed_round_cleanup, triggerReason={}, appId={}, codeGenType={}",
                        StrUtil.blankToDefault(triggerReason, "unknown"), appId, codeGenTypeEnum.getValue());
                return false;
            }
            List<ChatMessage> newList = new ArrayList<>(messages);
            newList.remove(removeIndex);
            chatMemoryStore.updateMessages(appId, newList);
            log.info("失败轮清理完成，cleanupType=failed_round_cleanup, triggerReason={}, appId={}, codeGenType={}, removedIndex={}, beforeCount={}, afterCount={}",
                    StrUtil.blankToDefault(triggerReason, "unknown"), appId, codeGenTypeEnum.getValue(), removeIndex, messages.size(), newList.size());
            return true;
        } catch (Exception e) {
            log.warn("定向清理失败轮 AI 长消息失败，appId={}", appId, e);
            return false;
        }
    }

    @Override
    public boolean removeUserMessageByContent(Long appId, Long userId, String message) {
        if (appId == null || appId <= 0 || userId == null || userId <= 0 || StrUtil.isBlank(message)) {
            return false;
        }
        try {
            QueryWrapper queryWrapper = new QueryWrapper();
            queryWrapper.eq(ChatHistory::getAppId, appId);
            queryWrapper.eq(ChatHistory::getUserId, userId);
            queryWrapper.eq(ChatHistory::getMessageType, ChatHistoryMessageTypeEnum.USER.getValue());
            queryWrapper.eq(ChatHistory::getMessage, message);
            queryWrapper.orderBy(ChatHistory::getCreateTime, false);
            queryWrapper.limit(1);
            List<ChatHistory> latestMatches = this.list(queryWrapper);
            if (latestMatches == null || latestMatches.isEmpty()) {
                return false;
            }
            return this.removeById(latestMatches.getFirst().getId());
        } catch (Exception e) {
            log.warn("按内容回滚用户消息失败, appId={}, userId={}", appId, userId, e);
            return false;
        }
    }

    @Override
    public boolean shouldSummarizeBeforeWorkflowGeneration(Long appId) {
        if (appId == null || appId <= 0) {
            return false;
        }
        try {
            QueryWrapper queryWrapper = new QueryWrapper();
            queryWrapper.eq(ChatHistory::getAppId, appId);
            queryWrapper.eq(ChatHistory::getMessageType, ChatHistoryMessageTypeEnum.AI.getValue());
            queryWrapper.orderBy(ChatHistory::getCreateTime, false);
            queryWrapper.limit(1);
            List<ChatHistory> latestAiMessages = this.list(queryWrapper);
            if (latestAiMessages == null || latestAiMessages.isEmpty()) {
                return false;
            }
            String latest = StrUtil.blankToDefault(latestAiMessages.getFirst().getMessage(), "");
            String latestLower = latest.toLowerCase(Locale.ROOT);
            if (latestLower.contains("[workflow] 生成失败")) {
                return false;
            }
            return latestLower.contains("[workflow] 代码生成完成");
        } catch (Exception e) {
            log.warn("判断 workflow 入口是否触发会话总结失败，appId={}", appId, e);
            return false;
        }
    }
}
