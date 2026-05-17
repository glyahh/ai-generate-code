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
import com.dbts.glyahhaigeneratecode.model.VO.AppChatHistoryPageVO;
import com.dbts.glyahhaigeneratecode.model.VO.UserChatHistoryItemVO;
import com.dbts.glyahhaigeneratecode.model.VO.ChatHistoryVO;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.constant.ChatHistoryConstant;
import com.dbts.glyahhaigeneratecode.constant.UserConstant;

import static com.dbts.glyahhaigeneratecode.constant.ChatHistoryMemoryCompactionConstant.*;

import com.dbts.glyahhaigeneratecode.core.util.ChatHistorySchemaMigrationSupport;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.dbts.glyahhaigeneratecode.service.ConversationMemoryStateService;
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
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;

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
 * 当 Redis 中用户轮数超过 {@link ChatHistoryConstant#MAX_ROUNDS_BEFORE_SUMMARY} 时，会通过
 * {@link #trySummarizeOldestRoundsIfNeeded} 将最早两轮合并为摘要并写回 DB（逻辑删原文）、再按 DB 重建 Redis；
 * HTML/MULTI_FILE 下更早的 AI 长文做片段压缩，但保留「最后一轮中主 AI 长文」以降低整站漂移。
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
    private ConversationMemoryStateService conversationMemoryStateService;

    @Override
    public boolean addChatMessage(Long appId, String message, String messageType, Long userId) {
        // 委托可返回主键的写库方法,根据雪花 id 是否生成得到本次写入是否成功
        return addChatMessageAndReturnId(appId, message, messageType, userId) != null;
    }

    /**
     * 添加一条对话消息并返回主键 id。
     *
     * @param appId 应用 id
     * @param message 消息内容
     * @param messageType 消息类型
     * @param userId 用户 id
     * @return 保存后的主键 id；失败返回 null
     */
    @Override
    public Long addChatMessageAndReturnId(Long appId, String message, String messageType, Long userId) {
        // 使用默认审计占位 SKIP/NONE,将实际写库委托给带审计参数的重载方法
        return addChatMessageAndReturnId(appId, message, messageType, userId, "SKIP", "NONE");
    }

    @Override
    public boolean addChatMessage(Long appId, String message, String messageType, Long userId, String auditAction, String auditHitRule) {
        // 委托带审查字段的写库方法,根据主键是否生成得到布尔成功标志
        return addChatMessageAndReturnId(appId, message, messageType, userId, auditAction, auditHitRule) != null;
    }


    /**
     * 添加一条带审查字段的对话消息并返回主键 id。
     *
     * @param appId 应用 id
     * @param message 消息内容
     * @param messageType 消息类型
     * @param userId 用户 id
     * @param auditAction 审查动作
     * @param auditHitRule 命中规则
     * @return 保存后的主键 id；失败返回 null
     */
    @Override
    public Long addChatMessageAndReturnId(Long appId, String message, String messageType, Long userId, String auditAction, String auditHitRule) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(messageType), ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(auditAction), ErrorCode.PARAMS_ERROR, "审查动作不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(auditHitRule), ErrorCode.PARAMS_ERROR, "命中规则不能为空");
        // 验证消息类型是否有效
        ChatHistoryMessageTypeEnum messageTypeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(messageType);
        ThrowUtils.throwIf(messageTypeEnum == null, ErrorCode.PARAMS_ERROR, "不支持的消息类型: " + messageType);
        // 写库前检查 audit 列，并将 message 升级为 longtext（老库兼容）
        ChatHistorySchemaMigrationSupport.ensureAuditColumnsIfMissing(appId, this.getMapper());
        ChatHistory chatHistory = ChatHistory.builder()
                .appId(appId)
                .message(message)
                .messageType(messageType)
                .userId(userId)
                .auditAction(auditAction)
                .auditHitRule(auditHitRule)
                .build();
        // 1. 先落库，再从实体回填的雪花 id 作为 roundId 来源。
        boolean saved = this.save(chatHistory);
        if (!saved || chatHistory.getId() == null || chatHistory.getId() <= 0) {
            return null;
        }
        return chatHistory.getId();
    }



    @Override
    public AppChatHistoryPageVO listAppChatHistoryByPage(Long appId, int pageSize,
                                                      LocalDateTime lastCreateTime,
                                                      User loginUser) {
        // 1. 校验应用 id、分页大小与登录态,得到可继续鉴权与查库的前置条件
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > 50, ErrorCode.PARAMS_ERROR, "页面大小必须在1-50之间");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        // 验证权限：只有应用创建者和管理员可以查看
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        boolean isCreator = app.getUserId().equals(loginUser.getId());
        ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权查看该应用的对话历史");

        // 2. 组装按应用与时间游标过滤的查询请求对象,得到与分页接口一致的 QueryWrapper 入参
        // 构建查询条件
        ChatHistoryQueryRequest queryRequest = new ChatHistoryQueryRequest();
        queryRequest.setAppId(appId);
        queryRequest.setLastCreateTime(lastCreateTime);
        QueryWrapper queryWrapper = this.buildQueryWrapper(queryRequest);

        // 3. 执行首页固定容量分页查询,得到当前页的 ChatHistory 实体列表
        // 查询数据
        Page<ChatHistory> page = this.page(Page.of(1, pageSize), queryWrapper);

        // 4. 为 workflow AI 行追加阶段状态标记,得到前端可直接渲染绿红灰态的回显文本
        // 从数据库中查到workflow生成的AI message,删掉给后面加上状态
        ChatHistorySchemaMigrationSupport.appendWorkflowStageStatusForHistoryPage(page);
        return AppChatHistoryPageVO.from(page, app.getIsBeta());
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
        // 1. 非法 appId 直接视为无需删除,得到幂等 false 结果
        if (appId == null || appId <= 0) {
            return false;
        }
        // 2. 构造按 appId 精确匹配的删除条件,得到一次性清理该应用全部历史的语义
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(ChatHistory::getAppId, appId);
        // 3. 调用父类 remove,得到物理/逻辑删除是否成功的布尔值
        return this.remove(queryWrapper);
    }

    @Override
    public QueryWrapper buildQueryWrapper(ChatHistoryQueryRequest queryRequest) {
        // 1. 请求体为空时直接抛业务异常,得到明确的参数错误反馈而非空指针
        if (queryRequest == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "查询请求参数为空");
        }

        // 2. 创建空 QueryWrapper 并逐步叠加 eq/like/le/orderBy 条件,得到可交给 MyBatis-Flex 执行的查询对象
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

        // 3. 返回组装完毕的 QueryWrapper,供分页或列表查询复用
        return queryWrapper;
    }

    @Override
    public ChatHistoryVO getChatHistoryVO(ChatHistory chatHistory) {
        // 1. 实体入参为空时无 VO 可组装,直接返回 null 表示无数据
        if (chatHistory == null) {
            return null;
        }
        // 2. 创建 VO 并复制同名字段,得到前端展示用的 ChatHistoryVO 实例
        ChatHistoryVO chatHistoryVO = new ChatHistoryVO();
        BeanUtil.copyProperties(chatHistory, chatHistoryVO);
        // 3. 返回填充后的 VO,供接口层序列化给前端
        return chatHistoryVO;
    }


    @Override
    public List<ChatHistoryVO> getChatHistoryVOList(List<ChatHistory> chatHistoryList) {
        // 1. 空或空列表直接返回不可变空集合,得到调用方无需再判空的流式安全结果
        if (chatHistoryList == null || chatHistoryList.isEmpty()) {
            return Collections.emptyList();
        }
        // 2. 逐条映射为 VO 并收集为 List,得到与输入同序的展示对象列表
        return chatHistoryList.stream()
                .map(this::getChatHistoryVO)
                .collect(Collectors.toList());
    }


    @Override
    public int turnHistoryToMemory(Long addId, MessageWindowChatMemory messageWindowChatMemory, int maxCount) {
        // 1. 校验应用 id、内存实例与拉取条数,得到可安全访问 DB 与 Redis 的前置条件
        // 校验参数
        ThrowUtils.throwIf(addId == null || addId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(messageWindowChatMemory == null, ErrorCode.PARAMS_ERROR, "聊天内存不能为空");
        ThrowUtils.throwIf(maxCount <= 0, ErrorCode.PARAMS_ERROR, "最大数量必须大于0");

        // 2. 组装 DB 查询（倒序+第二页 limit）以拉取最近 maxCount 条历史,得到需写入 Redis 的原始集合
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

        // 3. 将 DB 倒序结果反转为时间正序,得到从早到晚写入 ChatMemory 所需的行顺序
        // 反转历史消息（按时间从早到晚写入内存）
        Collections.reverse(historyList);

        // 4. 清空旧 Redis 记忆,得到避免与本次重建消息重复叠加的干净窗口
        // 清空Redis中的全部消息
        messageWindowChatMemory.clear();

        // 5. 读取应用 codeGenType 并尝试加载对应系统提示词文本,得到后续过滤「纯系统提示词 AI 行」的参照串
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
                    // 读 classpath 里的系统提示词，后面用来跳过与提示词相同的 AI 行
                    systemPromptForFilter = ChatHistorySchemaMigrationSupport.readPromptFromClasspath(promptPath);
                }
            }
        } catch (Exception e) {
            log.debug("读取系统提示词用于过滤失败，appId={}", addId, e);
        }

        // 6. 计算本轮豁免压缩的 AI 行下标,得到「主 AI 长文」在后续分支是否保留全文的依据
        // 拿到最后一轮user+ai 的 message 不动,压缩总结前面的
        int exemptAiRowIndex = ChatHistorySchemaMigrationSupport.indexOfExemptAiCompactionChatRows(historyList, appCodeGenTypeEnum);
        // 一次添加进入缓存
        // 7. 按时间正序遍历历史行并写入 LangChain4j ChatMemory,得到成功恢复的条数计数
        int restoredCount = 0;
        for (int row = 0; row < historyList.size(); row++) {
            ChatHistory history = historyList.get(row);
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
                    // HTML/MULTI_FILE：仅压缩「非本轮主 AI」；本轮主 AI 为「最后一个 User 之后子序列中文本最长的 Ai」
                    boolean keepFull = row == exemptAiRowIndex;
                    // 非主 AI 行：把过长正文压成摘要再写入 Redis（MySQL 仍保留原文）
                    String aiMessageForMemory = keepFull
                            ? history.getMessage()
                            : ChatHistorySchemaMigrationSupport.compactAiMessageForMemory(history.getMessage(), appCodeGenTypeEnum);
                    messageWindowChatMemory.add(new AiMessage(
                            StrUtil.blankToDefault(aiMessageForMemory, EMPTY_AI_MEMORY_PLACEHOLDER)));
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
     * 加载 memory_state 并按需注入文件内容到 Redis ChatMemory。
     *
     * @param appId 应用 id
     * @param messageWindowChatMemory 聊天内存
     * @param maxCount 最大历史条数
     * @param codeGenTypeEnum 代码生成类型
     * @return 注入后的内存消息条数,用户+ai对话的总轮数
     */
    @Override
    public int loadConversationMemoryStateAndInject(Long appId, MessageWindowChatMemory messageWindowChatMemory, int maxCount, CodeGenTypeEnum codeGenTypeEnum) {
        // 1. 先按既有链路把 DB 历史恢复到 Redis，保持与旧行为一致。
        int restored = turnHistoryToMemory(appId, messageWindowChatMemory, maxCount);
        // 注入数量
        int injectedCount = 0;

        // 2. 再执行新链路 memory_state 注入；该步骤失败不影响主生成链路。
        try {
            injectedCount = conversationMemoryStateService
                    // 读取 memory_state（Redis 未命中则 DB），按字符/token 预算将候选文件分页内容以 SystemMessage 注入同一 ChatMemory
                    .loadConversationMemoryStateAndInject(appId, messageWindowChatMemory, codeGenTypeEnum, maxCount)
                    // 取本次注入条数，与上方 restored 相加得到 finalCount
                    .getInjectedMessageCount();
        } catch (Exception e) {
            log.warn("loadConversationMemoryStateAndInject 执行失败，已降级为仅历史恢复，appId={}", appId, e);
        }

        // 3. 返回“注入后总条数”，避免和注释语义不一致。
        int finalCount = restored + Math.max(0, injectedCount);
        log.info("会话记忆预加载完成，appId={}, restoredCount={}, injectedCount={}, finalCount={}",
                appId, restored, injectedCount, finalCount);
        return finalCount;
    }












    @Override
    public List<ChatHistoryVO> listAllByAppIdForExport(Long appId, User loginUser) {
        // 1. 校验 appId 与登录态,得到可继续鉴权的前提
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        boolean isCreator = app.getUserId().equals(loginUser.getId());
        ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权导出该应用的对话历史");

        // 2. 构造按 appId 过滤且按时间升序的查询,得到导出用的全量历史行
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(ChatHistory::getAppId, appId);
        queryWrapper.orderBy(ChatHistory::getCreateTime, true);
        List<ChatHistory> list = this.list(queryWrapper);
        log.info("导出对话历史，appId={}, count={}", appId, list != null ? list.size() : 0);
        // 3. 将实体列表批量转为 VO 列表,得到导出接口可直接序列化的结果
        return getChatHistoryVOList(list != null ? list : Collections.emptyList());
    }

    @Override
    public int countRoundsByAppId(Long appId, User loginUser) {
        // 1. 校验 appId 与登录态,得到可统计轮数的安全上下文
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        boolean isCreator = app.getUserId().equals(loginUser.getId());
        ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权查看该应用的对话轮数");

        // 2. 统计 DB 中 USER 条数作为权威轮数,并与 Redis 视角对比打日志
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(ChatHistory::getAppId, appId);
        queryWrapper.eq(ChatHistory::getMessageType, ChatHistoryMessageTypeEnum.USER.getValue());
        long count = this.count(queryWrapper);
        // 3. 读取 Redis 侧轮数做一致性校验,得到偏差告警或对齐日志
        // 优先数 Redis 里的 user 条数，失败则回退数 DB
        int redisRounds = ChatHistorySchemaMigrationSupport.countRoundsByAppIdInternal(appId, chatMemoryStore, this.getMapper());
        if (redisRounds != (int) count) {
            log.warn("对话轮数存在 Redis/DB 偏差，appId={}, dbRounds={}, redisRounds={}", appId, count, redisRounds);
        } else {
            log.info("统计对话轮数，appId={}, rounds={}, redisAligned=true", appId, count);
        }
        // 4. 对外仍以 DB 统计为准返回 int,得到与持久化一致的业务轮数
        return (int) count;
    }

    @Override
    // 只要抛出 Exception（及子类），就回滚数据库操作
    @Transactional(rollbackFor = Exception.class)
    public void trySummarizeOldestRoundsIfNeeded(Long appId, Long userId, String triggerReason) {
        // 1. 非法入参直接返回,得到幂等无操作的收口
        if (appId == null || appId <= 0 || userId == null) {
            return;
        }
        // 2. 读取当前 DB 用户轮数并与阈值比较,未超标则记录日志后结束
        String reason = StrUtil.blankToDefault(triggerReason, "unknown");
        // 统计库里 USER 消息条数，当作当前对话轮数
        int dbRoundsBefore = ChatHistorySchemaMigrationSupport.countUserRoundsFromDatabase(appId, this.getMapper());
        if (dbRoundsBefore <= ChatHistoryConstant.MAX_ROUNDS_BEFORE_SUMMARY) {
            log.info("会话级总结跳过，compressType=conversation_summary, triggerReason={}, appId={}, dbRounds={}, threshold={}",
                    reason, appId, dbRoundsBefore, ChatHistoryConstant.MAX_ROUNDS_BEFORE_SUMMARY);
            return;
        }
        // 3. 确保审计列存在后进入 while 循环,反复合并最早两轮直至轮数达标
        // 合并写库前再检查表结构是否满足 audit / longtext
        ChatHistorySchemaMigrationSupport.ensureAuditColumnsIfMissing(appId, this.getMapper());
        int mergedCount = 0;
        // 轮数仍超阈值时，反复合并最早两轮
        while (ChatHistorySchemaMigrationSupport.countUserRoundsFromDatabase(appId, this.getMapper()) > ChatHistoryConstant.MAX_ROUNDS_BEFORE_SUMMARY) {
            // 按创建时间取最早的 4 条消息（两轮 user+ai）
            List<ChatHistory> oldestFour = ChatHistorySchemaMigrationSupport.listOldestMessagesForMerge(appId, ChatHistoryConstant.MESSAGES_PER_MERGE, this.getMapper());
            if (oldestFour == null || oldestFour.size() < ChatHistoryConstant.MESSAGES_PER_MERGE) {
                log.warn("对话合并：不足 {} 条最早消息，跳过本次合并，appId={}", ChatHistoryConstant.MESSAGES_PER_MERGE, appId);
                break;
            }
            String userSummary;
            String aiSummary;
            try {
                // 调用模型把两轮对话压成带【用户总结】【AI总结】标记的文本
                String summaryText = ChatHistorySchemaMigrationSupport.summarizeTwoRoundsWithAi(oldestFour, chatModel);
                // 从模型输出里拆出 user/ai 两段摘要
                String[] parsed = ChatHistorySchemaMigrationSupport.parseSummaryResponse(summaryText);
                userSummary = parsed[0];
                aiSummary = parsed[1];
            } catch (Exception e) {
                log.error("AI 总结前两轮对话失败，跳过本次合并，appId={}", appId, e);
                break;
            }
            LocalDateTime anchorCreateTime = oldestFour.getFirst().getCreateTime();
            for (ChatHistory row : oldestFour) {
                this.removeById(row.getId());
            }
            // 插入合并后的一轮 USER+AI 摘要行
            ChatHistorySchemaMigrationSupport.saveMergedRoundSummaryRows(appId, userId, anchorCreateTime, userSummary, aiSummary, this.getMapper());
            mergedCount++;
            log.info("已将最早两轮合并为一轮并写入 DB（逻辑删原文），appId={}, mergedCount={}", appId, mergedCount);
        }
        // 合并结束后重新统计 DB 轮数，供完成日志使用
        int dbRoundsAfter = ChatHistorySchemaMigrationSupport.countUserRoundsFromDatabase(appId, this.getMapper());
        log.info("会话级总结完成，compressType=conversation_summary, triggerReason={}, appId={}, beforeDbRounds={}, afterDbRounds={}, mergedIterations={}",
                reason, appId, dbRoundsBefore, dbRoundsAfter, mergedCount);
        // 4. 若发生过合并则重建 MessageWindowChatMemory 并重新灌库,得到与 DB 一致的 Redis 视图
        if (mergedCount > 0) {
            MessageWindowChatMemory rebuildMemory = MessageWindowChatMemory.builder()
                    .id(appId)
                    .chatMemoryStore(chatMemoryStore)
                    .maxMessages(ChatHistoryConstant.CHAT_MEMORY_MAX_MESSAGES)
                    .build();
            turnHistoryToMemory(appId, rebuildMemory, ChatHistoryConstant.MEMORY_PRELOAD_MESSAGE_ROWS);
        }
    }





    private String compactAiMessageForMemory(String rawMessage, CodeGenTypeEnum codeGenTypeEnum) {
        // 单测反射入口：转发到 support 做 AI 长文压缩
        return ChatHistorySchemaMigrationSupport.compactAiMessageForMemory(rawMessage, codeGenTypeEnum);
    }

    @Override
    public void compactMemoryMessagesIfNeeded(Long appId, CodeGenTypeEnum codeGenTypeEnum, String triggerReason) {
        // 1. 非法 appId 直接返回,得到幂等无操作
        if (appId == null || appId <= 0) {
            return;
        }
        // 2. 仅 HTML/MULTI_FILE 才做在线截断,其他类型记录日志后返回
        if (codeGenTypeEnum != CodeGenTypeEnum.HTML && codeGenTypeEnum != CodeGenTypeEnum.MULTI_FILE) {
            log.info("消息级截断跳过，compressType=message_truncate, triggerReason={}, appId={}, codeGenType={}",
                    StrUtil.blankToDefault(triggerReason, "unknown"), appId,
                    codeGenTypeEnum == null ? "null" : codeGenTypeEnum.getValue());
            return;
        }
        try {
            // 3. 从 Redis 读取当前消息序列,得到待扫描与可能回写的 ChatMessage 列表
            List<ChatMessage> messages = chatMemoryStore.getMessages(appId);
            if (messages == null || messages.isEmpty()) {
                log.info("消息级截断跳过，compressType=message_truncate, triggerReason={}, appId={}, codeGenType={}, beforeCount=0",
                        StrUtil.blankToDefault(triggerReason, "unknown"), appId, codeGenTypeEnum.getValue());
                return;
            }
            boolean changed = false;
            List<ChatMessage> newList = new ArrayList<>(messages.size());
            int beforeCount = messages.size();
            // 在 Redis 消息序列里找主 AI 下标，在线截断时跳过该行
            int exemptAiIdx = ChatHistorySchemaMigrationSupport.indexOfExemptAiCompactionRedisMessages(messages, codeGenTypeEnum);
            // 4. 逐条遍历并在非豁免 AI 上应用 compactAiMessageForMemory,得到 newList 与 changed 标记
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage message = messages.get(i);
                if (message instanceof AiMessage aiMessage) {
                    String raw = aiMessage.text();
                    boolean keepFull = exemptAiIdx >= 0 && i == exemptAiIdx;
                    // 非豁免 AI：同样走长文压缩，只改 Redis 上下文
                    String compacted = keepFull ? raw : ChatHistorySchemaMigrationSupport.compactAiMessageForMemory(raw, codeGenTypeEnum);
                    String safeAiText = StrUtil.blankToDefault(compacted, EMPTY_AI_MEMORY_PLACEHOLDER);
                    if (!Objects.equals(raw, safeAiText)) {
                        changed = true;
                    }
                    newList.add(new AiMessage(safeAiText));
                } else {
                    newList.add(message);
                }
            }
            // 5. 若存在变更则写回 Redis,并打完成或跳过日志,得到在线压缩闭环
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
        // 1. 非法 appId 直接返回 false,表示未执行任何清理
        if (appId == null || appId <= 0) {
            return false;
        }
        // 2. 仅 HTML/MULTI_FILE 支持该清理策略,其他类型打日志后返回 false
        if (codeGenTypeEnum != CodeGenTypeEnum.HTML && codeGenTypeEnum != CodeGenTypeEnum.MULTI_FILE) {
            log.info("失败轮清理跳过，cleanupType=failed_round_cleanup, triggerReason={}, appId={}, codeGenType={}",
                    StrUtil.blankToDefault(triggerReason, "unknown"), appId,
                    codeGenTypeEnum == null ? "null" : codeGenTypeEnum.getValue());
            return false;
        }
        try {
            // 3. 从 Redis 取消息并从尾部寻找超长 AiMessage,得到待删除下标或保持 -1
            List<ChatMessage> messages = chatMemoryStore.getMessages(appId);
            if (messages == null || messages.isEmpty()) {
                return false;
            }
            // 4. 从尾部向前扫描 AiMessage,找到首个超长文本所在下标作为失败轮候选
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
            // 5. 复制原列表移除目标消息后写回 Redis,得到允许用户立即重试的内存状态
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
        // 1. 入参非法时直接 false,避免构造无意义查询
        if (appId == null || appId <= 0 || userId == null || userId <= 0 || StrUtil.isBlank(message)) {
            return false;
        }
        try {
            // 2. 构造精确定位「最新一条同内容用户消息」的查询条件,得到待删除行候选
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
            // 3. 命中则按主键删除该条用户消息,得到回滚发送动作后的持久化结果
            return this.removeById(latestMatches.getFirst().getId());
        } catch (Exception e) {
            log.warn("按内容回滚用户消息失败, appId={}, userId={}", appId, userId, e);
            return false;
        }
    }

    /**
     * 对话轮次完成后的统一收口。
     *
     * @param appId 应用 id
     * @param roundId 本轮 roundId（chat_history.id）
     * @param userId 用户 id
     * @param codeGenTypeEnum 代码生成类型
     * @param workflowMode 是否 workflow 模式
     * @return 无
     */
    @Override
    public void onRoundCompleted(Long appId, Long roundId, Long userId, CodeGenTypeEnum codeGenTypeEnum, boolean workflowMode) {
        // 1. 无 buffer 与耗时的默认收口,将指标置零后委托重载实现
        onRoundCompleted(appId, roundId, userId, codeGenTypeEnum, workflowMode, 0, 0L);
    }

    /**
     * 对话轮次完成后的统一收口（带可观测指标参数）如果没有抛异常就说明成功了
     *
     * @param appId 应用 id
     * @param roundId 本轮 roundId（chat_history.id）
     * @param userId 用户 id
     * @param codeGenTypeEnum 代码生成类型
     * @param workflowMode 是否 workflow 模式
     * @param bufferChars 本轮输出字符数
     * @param elapsedMs 本轮耗时
     * @return 无
     */
    public void onRoundCompleted(Long appId, Long roundId, Long userId, CodeGenTypeEnum codeGenTypeEnum, boolean workflowMode, int bufferChars, long elapsedMs) {
        // 1. 关键主键缺失时直接返回,得到幂等无操作的收口
        if (appId == null || appId <= 0 || roundId == null || roundId <= 0 || userId == null || userId <= 0) {
            return;
        }
        try {
            // 1. 收口逻辑委托给独立 memory_state 服务，隔离主会话入库与 SSE 输出。
            // 2. 透传执行器采集的真实 bufferChars/elapsedMs，满足 E 指标口径。
            conversationMemoryStateService.onRoundCompleted(appId, roundId, userId, codeGenTypeEnum, workflowMode, bufferChars, elapsedMs);
        } catch (Exception e) {
            // 3. 失败自吞：严格遵循主链路隔离原则。
            log.warn("onRoundCompleted 执行失败已忽略，appId={}, roundId={}", appId, roundId, e);
        }
    }

    @Override
    public boolean shouldSummarizeBeforeWorkflowGeneration(Long appId) {
        // 1. 非法 appId 不做判断,直接 false 表示不触发前置总结
        if (appId == null || appId <= 0) {
            return false;
        }
        try {
            // 2. 查询该应用最新一条 AI 历史消息,得到 workflow 状态判断的文本依据
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
            // 3. 最新 AI 明确标记生成失败时不触发总结,得到避免在失败态继续压历史的语义
            if (latestLower.contains("[workflow] 生成失败")) {
                return false;
            }
            // 4. 仅当最新 AI 含成功完成标记时返回 true,得到允许在下一轮 workflow 前做会话压缩的信号
            return latestLower.contains("[workflow] 代码生成完成");
        } catch (Exception e) {
            log.warn("判断 workflow 入口是否触发会话总结失败，appId={}", appId, e);
            return false;
        }
    }
}
