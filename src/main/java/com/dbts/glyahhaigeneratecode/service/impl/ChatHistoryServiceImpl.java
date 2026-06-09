package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.bean.BeanUtil;
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

import com.dbts.glyahhaigeneratecode.core.memory.ChatAiMemoryRedisSupport;
import com.dbts.glyahhaigeneratecode.core.memory.ChatHistoryAiMemoryRebuildSupport;
import com.dbts.glyahhaigeneratecode.core.memory.ChatHistoryEchoRedisSupport;
import com.dbts.glyahhaigeneratecode.core.util.ChatHistorySchemaMigrationSupport;
import com.dbts.glyahhaigeneratecode.service.MemoryShrinkService;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 对话历史 服务层实现。
 * <p>
 * 当 Redis 中用户轮数超过 {@link ChatHistoryConstant#MAX_ROUNDS_BEFORE_SUMMARY} 时，会通过
 * {@link #trySummarizeOldestRoundsIfNeeded} 将最早两轮合并为摘要写入 memory_shrink（chat_history 保持全文）并重建 AI Redis；
 * HTML/MULTI_FILE 下更早的 AI 长文做片段压缩，但保留「最后一轮中主 AI 长文」以降低整站漂移。
 * </p>
 * <p>
 * 主链路：用户/AI 消息落库 chat_history -> 从 DB 与 memory_shrink 重建 Redis ChatMemory 并注入 memory_state -> 分页查询/导出/轮次统计 -> 超轮 AI 摘要合并与在线截断 -> 轮次完成收口同步快照。
 * </p>
 * <p>
 * 流程串联：USER/AI 落库 chat_history -> echo_memory 全文供前端回显 -> rebuildAiChatMemoryFromShrink 写 AI Redis -> trySummarize 写 memory_shrink 摘要 -> onRoundCompleted 收口 conversation_memory_state。
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

    @Resource
    private MemoryShrinkService memoryShrinkService;

    @Resource
    private ChatHistoryEchoRedisSupport chatHistoryEchoRedisSupport;

    @Resource
    private ChatHistoryAiMemoryRebuildSupport chatHistoryAiMemoryRebuildSupport;

    @Resource
    private ChatAiMemoryRedisSupport chatAiMemoryRedisSupport;

    /**
     * 添加一条对话消息并返回是否写入成功（默认审查占位 SKIP/NONE）
     *
     * @param appId       应用 id
     * @param message     消息内容
     * @param messageType 消息类型
     * @param userId      用户 id
     * @return 落库成功为 true，失败为 false
     */
    @Override
    public boolean addChatMessage(Long appId, String message, String messageType, Long userId) {
        // 方法大纲：
        // 1. 委托带主键返回的写库重载，以雪花 id 是否生成为成功标志    L113-L114

        // 1. 委托可返回主键的写库方法,根据雪花 id 是否生成得到本次写入是否成功
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
        // 方法大纲：
        // 1. 使用默认审查占位 SKIP/NONE，委托带审计字段的完整写库重载    L131-L132

        // 使用默认审计占位 SKIP/NONE,将实际写库委托给带审计参数的重载方法
        return addChatMessageAndReturnId(appId, message, messageType, userId, "SKIP", "NONE");
    }

    /**
     * 添加一条带审查字段的对话消息并返回是否写入成功
     *
     * @param appId        应用 id
     * @param message      消息内容
     * @param messageType  消息类型
     * @param userId       用户 id
     * @param auditAction  审查动作
     * @param auditHitRule 命中规则
     * @return 落库成功为 true，失败为 false
     */
    @Override
    public boolean addChatMessage(Long appId, String message, String messageType, Long userId, String auditAction, String auditHitRule) {
        // 方法大纲：
        // 1. 委托带主键返回的写库重载，以雪花 id 是否生成为成功标志    L151-L152

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
        // 方法大纲：
        // 1. 校验 appId、消息内容、类型、用户与审查字段合法性    L174-L183
        // 2. 确保 audit 列与 longtext 兼容后组装实体并落库    L184-L199
        // 3. 失效 echo 缓存；USER 消息时刷新 AI Redis TTL 并返回主键    L200-L205

        // 1. 校验 appId、消息内容、类型、用户与审查字段合法性
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(messageType), ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(auditAction), ErrorCode.PARAMS_ERROR, "审查动作不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(auditHitRule), ErrorCode.PARAMS_ERROR, "命中规则不能为空");
        // 验证消息类型是否有效
        ChatHistoryMessageTypeEnum messageTypeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(messageType);
        ThrowUtils.throwIf(messageTypeEnum == null, ErrorCode.PARAMS_ERROR, "不支持的消息类型: " + messageType);
        // 2. 确保 audit 列与 longtext 兼容后组装实体并落库
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
        // 3. 失效 echo 缓存；USER 消息时刷新 AI Redis TTL 并返回主键
        chatHistoryEchoRedisSupport.invalidate(appId);
        if (ChatHistoryMessageTypeEnum.USER.getValue().equals(messageType)) {
            chatAiMemoryRedisSupport.refreshAiMemoryTtl(appId);
        }
        return chatHistory.getId();
    }



    /**
     * 分页查询指定应用的对话历史（游标分页，优先 echo 缓存）
     *
     * @param appId          应用 id
     * @param pageSize       每页条数（1-50）
     * @param lastCreateTime 游标：仅返回 createTime 小于等于该时间的记录；首页为 null
     * @param loginUser      当前登录用户
     * @return 带 beta 标记的分页 VO
     */
    @Override
    public AppChatHistoryPageVO listAppChatHistoryByPage(Long appId, int pageSize,
                                                      LocalDateTime lastCreateTime,
                                                      User loginUser) {
        // 方法大纲：
        // 1. 校验 appId、分页大小与登录态    L228-L230
        // 2. 校验应用存在性与创建者/管理员权限    L232-L238
        // 3. 从 echo 或 DB 加载分页并补 workflow 阶段状态后组装 VO    L240-L243

        // 1. 校验应用 id、分页大小与登录态,得到可继续鉴权与查库的前置条件
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > 50, ErrorCode.PARAMS_ERROR, "页面大小必须在1-50之间");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        // 2. 校验应用存在性与创建者/管理员权限
        // 验证权限：只有应用创建者和管理员可以查看
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        boolean isCreator = app.getUserId().equals(loginUser.getId());
        ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权查看该应用的对话历史");

        // 3. 从 echo 或 DB 加载分页并补 workflow 阶段状态后组装 VO
        Page<ChatHistory> page = loadAppChatHistoryPageFromEchoOrDb(appId, pageSize, lastCreateTime);

        ChatHistorySchemaMigrationSupport.appendWorkflowStageStatusForHistoryPage(page);
        return AppChatHistoryPageVO.from(page, app.getIsBeta());
    }

    /**
     * 优先 echo_memory 全文缓存，miss 时从 chat_history 加载并回填 Redis。
     *
     * @param appId          应用 id
     * @param pageSize       每页条数
     * @param lastCreateTime 游标：仅返回 createTime 小于等于该时间的记录；首页为 null
     * @return 内存分页结果，records 为当前页、totalRow 为全文条数
     */
    private Page<ChatHistory> loadAppChatHistoryPageFromEchoOrDb(Long appId, int pageSize, LocalDateTime lastCreateTime) {
        // 方法大纲：
        // 1. 尝试 echo_memory 命中全文列表，miss 时从 DB 升序拉全量并回填    L262-L278
        // 2. 按 lastCreateTime 过滤并在内存中倒序分页    L279-L305
        // 3. 组装 Page 对象（records 为当前页、totalRow 为全文条数）    L306-L310

        // 1. 尝试 echo_memory 命中全文列表
        List<ChatHistory> fullList = chatHistoryEchoRedisSupport.getCachedFullHistory(appId);
        if (fullList == null) {
            // 2. miss 时从 chat_history 升序拉全量并回填 Redis
            QueryWrapper allQ = new QueryWrapper();
            allQ.eq(ChatHistory::getAppId, appId);
            allQ.orderBy(ChatHistory::getCreateTime, true);
            fullList = this.list(allQ);
            if (fullList == null) {
                fullList = Collections.emptyList();
            }
            chatHistoryEchoRedisSupport.putFullHistory(appId, fullList);
        }
        // 2. 按 lastCreateTime 过滤并在内存中倒序分页，得到与旧 SQL 语义一致的当前页
        List<ChatHistory> filtered = fullList.stream()
                .filter(row -> lastCreateTime == null
                        || row.getCreateTime() == null
                        || !row.getCreateTime().isAfter(lastCreateTime))
                .sorted((a, b) -> {
                    if (a.getCreateTime() == null && b.getCreateTime() == null) {
                        return Long.compare(
                                b.getId() == null ? 0L : b.getId(),
                                a.getId() == null ? 0L : a.getId());
                    }
                    if (a.getCreateTime() == null) {
                        return 1;
                    }
                    if (b.getCreateTime() == null) {
                        return -1;
                    }
                    int cmp = b.getCreateTime().compareTo(a.getCreateTime());
                    if (cmp != 0) {
                        return cmp;
                    }
                    return Long.compare(
                            b.getId() == null ? 0L : b.getId(),
                            a.getId() == null ? 0L : a.getId());
                })
                .limit(pageSize)
                .toList();
        // 3. 组装 Page 对象（records 为当前页、totalRow 为全文条数）
        Page<ChatHistory> page = new Page<>(1, pageSize);
        page.setRecords(new ArrayList<>(filtered));
        page.setTotalRow(fullList.size());
        return page;
    }


    /**
     * 分页查询当前登录用户自己的对话历史，并批量补全应用名称
     *
     * @param queryRequest 查询条件（pageNum/pageSize/messageType/appId 等）
     * @param loginUser    当前登录用户
     * @return 带 appName 的用户对话历史分页 VO
     */
    @Override
    public Page<UserChatHistoryItemVO> listMyChatHistoryByPage(ChatHistoryQueryRequest queryRequest, User loginUser) {
        // 方法大纲：
        // 1. 校验查询参数与登录态    L325-L327
        // 2. 构造安全查询对象并强制写入当前用户 id    L329-L338
        // 3. 分页查库并批量补全 appName    L340-L374
        // 4. 实体转 VO 并组装分页结果返回    L376-L379

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

        // 3. 分页查库并批量补全 appName
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

        // 4. 实体转 VO 并组装分页结果返回
        // 8. 组装VO分页对象并返回，保持总条数与分页参数和原始查询结果一致。
        Page<UserChatHistoryItemVO> voPage = new Page<>(pageNum, pageSize, page.getTotalRow());
        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * 按应用 id 删除该应用下的全部对话历史
     *
     * @param appId 应用 id
     * @return 删除成功为 true；非法 appId 返回 false
     */
    @Override
    public boolean removeByAppId(Long appId) {
        // 方法大纲：
        // 1. 非法 appId 直接返回 false（幂等）    L398-L401
        // 2. 构造按 appId 匹配的删除条件并执行 remove    L402-L406

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

    /**
     * 根据查询请求组装 MyBatis-Flex QueryWrapper（支持 eq/like/le/orderBy）
     *
     * @param queryRequest 查询条件
     * @return 可交给分页或列表查询的 QueryWrapper
     */
    @Override
    public QueryWrapper buildQueryWrapper(ChatHistoryQueryRequest queryRequest) {
        // 方法大纲：
        // 1. 请求体为空时抛业务异常    L422-L425
        // 2. 逐字段叠加 eq/like/le 条件    L427-L455
        // 3. 设置排序（自定义字段或默认 createTime 降序）并返回    L457-L464

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

    /**
     * 将 ChatHistory 实体转换为前端展示 VO
     *
     * @param chatHistory 对话历史实体
     * @return 转换后的 VO；入参为 null 时返回 null
     */
    @Override
    public ChatHistoryVO getChatHistoryVO(ChatHistory chatHistory) {
        // 方法大纲：
        // 1. 空实体直接返回 null    L483-L486
        // 2. 复制同名字段到 VO 并返回    L487-L490

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


    /**
     * 批量将 ChatHistory 实体列表转换为 VO 列表
     *
     * @param chatHistoryList 对话历史实体列表
     * @return 与输入同序的 VO 列表；空或 null 时返回不可变空集合
     */
    @Override
    public List<ChatHistoryVO> getChatHistoryVOList(List<ChatHistory> chatHistoryList) {
        // 方法大纲：
        // 1. 空列表返回 Collections.emptyList()    L507-L510
        // 2. 逐条 map 为 VO 并收集返回    L511-L514

        // 1. 空或空列表直接返回不可变空集合,得到调用方无需再判空的流式安全结果
        if (chatHistoryList == null || chatHistoryList.isEmpty()) {
            return Collections.emptyList();
        }
        // 2. 逐条映射为 VO 并收集为 List,得到与输入同序的展示对象列表
        return chatHistoryList.stream()
                .map(this::getChatHistoryVO)
                .collect(Collectors.toList());
    }


    /**
     * 从 chat_history 倒序拉取最近 maxCount 条并重建 Redis ChatMemory（含 AI 长文压缩与系统提示词过滤）
     * <p>遗留路径：当前生产预加载已改用 rebuildAiChatMemoryFromShrink，调用方应优先使用 {@link #loadConversationMemoryStateAndInject}。</p>
     *
     * @param addId                   应用 id（同时作为 ChatMemory 的 memoryId）
     * @param messageWindowChatMemory 待写入的 LangChain4j 窗口记忆实例
     * @param maxCount                最多拉取的历史条数
     * @return 成功写入 ChatMemory 的消息条数；无历史时返回 0
     */
    @Override
    public int turnHistoryToMemory(Long addId, MessageWindowChatMemory messageWindowChatMemory, int maxCount) {
        // 方法大纲：
        // 1. 校验 appId、内存实例与 maxCount    L538-L541
        // 2. 从 DB 倒序拉最近 maxCount 条并反转为时间正序    L543-L555
        // 3. 清空旧 Redis 记忆并加载系统提示词用于过滤    L561-L591
        // 4. 计算豁免压缩的主 AI 下标并按类型逐条写入 ChatMemory    L595-L633

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
     * 生成前预加载：清空窗口后由 rebuild 组装 AI 轨历史，再委托 memory_state 注入索引类 SystemMessage。
     * <p>生产路径走 {@link ChatHistoryAiMemoryRebuildSupport#rebuildAiChatMemoryFromShrink}，非 {@link #turnHistoryToMemory}。</p>
     *
     * @param appId 应用 id
     * @param messageWindowChatMemory 聊天内存
     * @param maxCount 最大历史条数
     * @param codeGenTypeEnum 代码生成类型
     * @return 注入后的内存消息条数,用户+ai对话的总轮数
     */
    @Override
    public int loadConversationMemoryStateAndInject(Long appId, MessageWindowChatMemory messageWindowChatMemory, int maxCount, CodeGenTypeEnum codeGenTypeEnum) {
        // 方法大纲：
        // 1. 清空 ChatMemory 并从 shrink+DB 重建 AI 轨历史    L659-L662
        // 2. 委托 ConversationMemoryStateService 注入 memory_state（失败降级）    L667-L675
        // 3. 汇总 restored + injected 条数并打日志返回    L679-L682

        // 1. 清空 ChatMemory 并从 shrink+DB 重建 AI 轨历史
        messageWindowChatMemory.clear();
        int restored = chatHistoryAiMemoryRebuildSupport.rebuildAiChatMemoryFromShrink(
                appId, messageWindowChatMemory, maxCount, codeGenTypeEnum);
        // 注入数量
        int injectedCount = 0;

        // 2. 委托 ConversationMemoryStateService 注入 memory_state（失败降级）
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

        // 3. 汇总 restored + injected 条数并打日志返回
        // 3. 返回“注入后总条数”，避免和注释语义不一致。
        int finalCount = restored + Math.max(0, injectedCount);
        log.info("会话记忆预加载完成，appId={}, restoredCount={}, injectedCount={}, finalCount={}",
                appId, restored, injectedCount, finalCount);
        return finalCount;
    }












    /**
     * 分页查询指定应用的全部对话历史（导出用，需创建者或管理员权限）
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户
     * @return 该应用全部对话历史的 VO 列表
     */
    @Override
    public List<ChatHistoryVO> listAllByAppIdForExport(Long appId, User loginUser) {
        // 方法大纲：
        // 1. 校验 appId 与登录态并校验创建者/管理员权限    L706-L714
        // 2. 优先 echo 缓存，miss 时从 DB 升序拉全量并回填    L716-L727
        // 3. 批量转 VO 列表返回    L728-L730

        // 1. 校验 appId 与登录态,得到可继续鉴权的前提
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        boolean isCreator = app.getUserId().equals(loginUser.getId());
        ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权导出该应用的对话历史");

        // 2. 优先 echo 缓存，miss 时从 DB 升序拉全量并回填
        List<ChatHistory> list = chatHistoryEchoRedisSupport.getCachedFullHistory(appId);
        if (list == null) {
            QueryWrapper queryWrapper = new QueryWrapper();
            queryWrapper.eq(ChatHistory::getAppId, appId);
            queryWrapper.orderBy(ChatHistory::getCreateTime, true);
            list = this.list(queryWrapper);
            if (list == null) {
                list = Collections.emptyList();
            }
            chatHistoryEchoRedisSupport.putFullHistory(appId, list);
        }
        log.info("导出对话历史，appId={}, count={}", appId, list.size());
        // 3. 将实体列表批量转为 VO 列表,得到导出接口可直接序列化的结果
        return getChatHistoryVOList(list);
    }

    /**
     * 在线截断 Redis 后，将变更的 AI 行 upsert 到 memory_shrink（与 compactMemoryMessagesIfNeeded 配套，记忆轨改造后计划停用）。
     *
     * @param appId           应用 id
     * @param before          截断前 Redis 消息列表
     * @param after           截断后 Redis 消息列表
     * @param codeGenTypeEnum 代码生成类型（豁免主 AI 规则）
     */
    private void syncTruncateShrinksAfterRedisCompact(Long appId, List<ChatMessage> before, List<ChatMessage> after,
                                                      CodeGenTypeEnum codeGenTypeEnum) {
        // 方法大纲：
        // 1. 校验 before/after 等长，否则直接返回    L747-L749
        // 2. 委托 internal 实现，异常时打 warn 吞掉    L750-L754

        // 1. 校验 before/after 等长，否则直接返回
        if (before == null || after == null || before.size() != after.size()) {
            return;
        }
        try {
            syncTruncateShrinksAfterRedisCompactInternal(appId, before, after, codeGenTypeEnum);
        } catch (Exception e) {
            log.warn("同步 message_truncate 到 memory_shrink 失败，appId={}", appId, e);
        }
    }

    /**
     * 按时间序对齐 Redis AiMessage 与 chat_history AI 行，对发生 compact 的行 upsert message_truncate
     *
     * @param appId           应用 id
     * @param before          截断前 Redis 消息
     * @param after           截断后 Redis 消息
     * @param codeGenTypeEnum 代码生成类型
     */
    private void syncTruncateShrinksAfterRedisCompactInternal(Long appId, List<ChatMessage> before, List<ChatMessage> after,
                                                              CodeGenTypeEnum codeGenTypeEnum) {
        // 方法大纲：
        // 1. 拉取未合并的 DB 行并按时间序对齐 Redis AiMessage    L772-L795
        // 2. 遍历 after 序列，对发生 compact 的 AI 行 upsert message_truncate    L796-L837

        // 1. 拉取未合并的 DB 行并按时间序对齐 Redis AiMessage
        memoryShrinkService.ensureTableExists();
        Set<Long> mergedSourceIds = memoryShrinkService.collectAllMergedSourceChatHistoryIds(appId);
        QueryWrapper q = new QueryWrapper();
        q.eq(ChatHistory::getAppId, appId);
        if (!mergedSourceIds.isEmpty()) {
            q.notIn(ChatHistory::getId, mergedSourceIds);
        }
        q.orderBy(ChatHistory::getCreateTime, true);
        List<ChatHistory> dbRows = this.list(q);
        if (dbRows == null || dbRows.isEmpty()) {
            return;
        }
        List<ChatHistory> dbAiRows = dbRows.stream()
                .filter(h -> ChatHistoryMessageTypeEnum.AI.getValue().equals(h.getMessageType()))
                .toList();
        int exemptAiIdx = ChatHistorySchemaMigrationSupport.indexOfExemptAiCompactionRedisMessages(after, codeGenTypeEnum);
        int aiDbIdx = 0;
        Long lastUserId = dbRows.stream()
                .filter(h -> ChatHistoryMessageTypeEnum.USER.getValue().equals(h.getMessageType()))
                .reduce((first, second) -> second)
                .map(ChatHistory::getUserId)
                .orElse(null);
        // 2. 遍历 after 序列，对发生 compact 的 AI 行 upsert message_truncate
        for (int i = 0; i < after.size(); i++) {
            ChatMessage msg = after.get(i);
            if (msg instanceof UserMessage) {
                for (ChatHistory row : dbRows) {
                    if (ChatHistoryMessageTypeEnum.USER.getValue().equals(row.getMessageType())
                            && Objects.equals(row.getMessage(), ((UserMessage) msg).singleText())) {
                        lastUserId = row.getUserId();
                    }
                }
                continue;
            }
            if (!(msg instanceof AiMessage aiMessage)) {
                continue;
            }
            if (exemptAiIdx >= 0 && i == exemptAiIdx) {
                if (aiDbIdx < dbAiRows.size()) {
                    aiDbIdx++;
                }
                continue;
            }
            ChatMessage oldMsg = before.get(i);
            String oldText = oldMsg instanceof AiMessage o ? o.text() : null;
            String newText = aiMessage.text();
            if (Objects.equals(oldText, newText) || aiDbIdx >= dbAiRows.size()) {
                if (aiDbIdx < dbAiRows.size()) {
                    aiDbIdx++;
                }
                continue;
            }
            ChatHistory sourceRow = dbAiRows.get(aiDbIdx);
            aiDbIdx++;
            if (sourceRow.getId() == null || lastUserId == null) {
                continue;
            }
            memoryShrinkService.upsertMessageTruncate(
                    appId,
                    lastUserId,
                    sourceRow.getId(),
                    sourceRow.getCreateTime(),
                    newText,
                    ChatHistoryMessageTypeEnum.AI.getValue());
        }
    }

    /**
     * 统计指定应用的对话轮数（以 DB 中 USER 条数为准），并比对 echo 缓存一致性
     *
     * @param appId     应用 id
     * @return DB 中 USER 消息条数（对话轮数）
     */
    @Override
    public int countUserRoundsInternal(Long appId) {
        // 方法大纲：
        // 1. 非法 appId 直接返回 0    L856-L858
        // 2. 按 appId + USER 类型统计 chat_history 条数并返回    L859-L862

        // 1. 非法 appId 视为无轮次,得到幂等 0 结果
        if (appId == null || appId <= 0) {
            return 0;
        }
        // 2. 构造 USER 类型精确匹配条件并 count,得到 DB 权威对话轮数
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(ChatHistory::getAppId, appId);
        queryWrapper.eq(ChatHistory::getMessageType, ChatHistoryMessageTypeEnum.USER.getValue());
        return (int) this.count(queryWrapper);
    }

    /**
     * 统计指定应用对话轮数：以 DB USER 条数为权威值，并比对 echo_memory 全文缓存一致性
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户（创建者或管理员可查看）
     * @return DB 中 USER 消息条数（对话轮数）
     */
    @Override
    public int countRoundsByAppId(Long appId, User loginUser) {
        // 方法大纲：
        // 1. 校验 appId、登录态与创建者/管理员权限    L855-L863
        // 2. 统计 DB 中 USER 条数作为权威轮数    L865-L869
        // 3. 与 echo_memory 全文缓存比对，偏差时自愈一次    L871-L918
        // 4. 仍以 DB 统计为准返回 int    L920-L921

        // 1. 校验 appId 与登录态,得到可统计轮数的安全上下文
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        boolean isCreator = app.getUserId().equals(loginUser.getId());
        ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权查看该应用的对话轮数");

        // 2. 统计 DB 中 USER 条数作为权威轮数
        long count = countUserRoundsInternal(appId);

        // 3. 仅对比“回显全文缓存”（chat:echo_memory:{appId}）与 DB 轮数，避免与 AI ChatMemory（压缩/注入/窗口）产生噪声偏差
        // 3.1 优先从 echo_memory 命中全文列表；miss 时回源 DB 并回填
        List<ChatHistory> echoFull = chatHistoryEchoRedisSupport.getCachedFullHistory(appId);
        if (echoFull == null) {
            QueryWrapper allQ = new QueryWrapper();
            allQ.eq(ChatHistory::getAppId, appId);
            allQ.orderBy(ChatHistory::getCreateTime, true);
            echoFull = this.list(allQ);
            if (echoFull == null) {
                echoFull = Collections.emptyList();
            }
            // 将 chat_history 全文写入 echo_memory 并设置 TTL
            chatHistoryEchoRedisSupport.putFullHistory(appId, echoFull);
        }

        // 计算 echo_memory 中的 USER 条数
        int echoRounds = (int) echoFull.stream()
                .filter(row -> ChatHistoryMessageTypeEnum.USER.getValue().equals(row.getMessageType()))
                .count();

        if (echoRounds != (int) count) {
            // 3.2 自愈一次：invalidate + 回源 DB 重建 echo_memory 后再次比对
            int echoRoundsAfter = echoRounds;
            try {
                chatHistoryEchoRedisSupport.invalidate(appId);
                QueryWrapper allQ2 = new QueryWrapper();
                allQ2.eq(ChatHistory::getAppId, appId);
                allQ2.orderBy(ChatHistory::getCreateTime, true);
                List<ChatHistory> rebuilt = this.list(allQ2);
                if (rebuilt == null) {
                    rebuilt = Collections.emptyList();
                }
                chatHistoryEchoRedisSupport.putFullHistory(appId, rebuilt);
                echoRoundsAfter = (int) rebuilt.stream()
                        .filter(row -> ChatHistoryMessageTypeEnum.USER.getValue().equals(row.getMessageType()))
                        .count();
            } catch (Exception ignore) {
                // 保持主流程可用：比对失败不影响对外返回值（仍以 DB 为准）
            }
            if (echoRoundsAfter != (int) count) {
                log.warn("对话轮数存在 echo_memory/DB 偏差，appId={}, dbRounds={}, echoRoundsBefore={}, echoRoundsAfter={}",
                        appId, count, echoRounds, echoRoundsAfter);
            } else {
                log.info("对话轮数已自愈对齐，appId={}, rounds={}, echoAligned=true", appId, count);
            }
        } else {
            log.info("统计对话轮数，appId={}, rounds={}, echoAligned=true", appId, count);
        }

        // 4. 对外仍以 DB 统计为准返回 int,得到与持久化一致的业务轮数
        return (int) count;
    }

    /**
     * 当未合并 DB USER 轮数超过阈值时，将最早轮次 AI 摘要合并写入 memory_shrink 并重建 AI Redis
     *
     * @param appId         应用 id
     * @param userId        用户 id
     * @param triggerReason 触发原因（日志用）
     * @return 实际执行了 memory_shrink 合并时为 true，未超阈值或跳过为 false
     */
    @Override
    // 只要抛出 Exception（及子类），就回滚数据库操作
    @Transactional(rollbackFor = Exception.class)
    public boolean trySummarizeOldestRoundsIfNeeded(Long appId, Long userId, String triggerReason) {
        // 方法大纲：
        // 1. 非法入参或 DB 未合并 USER 轮数未超阈值则跳过    L940-L952
        // 2. 循环合并最早轮次：AI 摘要后写入 memory_shrink 唯一摘要对    L955-L1008
        // 3. 若有合并则失效 echo 并重建 AI Redis ChatMemory    L1014-L1027

        if (appId == null || appId <= 0 || userId == null) {
            return false;
        }
        String reason = StrUtil.blankToDefault(triggerReason, "unknown");
        // 确保 memory_shrink 表存在，首次使用自动建表
        memoryShrinkService.ensureTableExists();
        // 收集已合并的 chat_history ID 集合，用于排除已合并轮次
        Set<Long> mergedSourceIds = memoryShrinkService.collectAllMergedSourceChatHistoryIds(appId);
        // 统计未合并的 DB USER 轮数，判断是否达到压缩阈值
        int dbUsersBefore = memoryShrinkService.countUnmergedDbUserRounds(appId, mergedSourceIds);
        if (dbUsersBefore <= ChatHistoryConstant.TARGET_UNMERGED_DB_USER_ROUNDS) {
            log.info("会话级总结跳过，compressType=conversation_summary, triggerReason={}, appId={}, unmergedDbUsers={}, target={}",
                    reason, appId, dbUsersBefore, ChatHistoryConstant.TARGET_UNMERGED_DB_USER_ROUNDS);
            return false;
        }
        // 确保 audit 列在 chat_history 表存在，兼容旧表结构
        ChatHistorySchemaMigrationSupport.ensureAuditColumnsIfMissing(appId, this.getMapper());
        int mergedCount = 0;
        while (true) {
            // 每次循环重新收集已合并 ID，排除已处理的轮次
            Set<Long> excludeIds = memoryShrinkService.collectAllMergedSourceChatHistoryIds(appId);
            // 统计排除后的未合并 USER 轮数
            int dbUsers = memoryShrinkService.countUnmergedDbUserRounds(appId, excludeIds);
            if (dbUsers <= ChatHistoryConstant.TARGET_UNMERGED_DB_USER_ROUNDS) {
                break;
            }
            // 查询当前已有的对话摘要对（用于滚动合并场景）
            var existingSummary = memoryShrinkService.getConversationSummaryPair(appId);
            List<ChatHistory> toMerge;
            String summaryText;
            LocalDateTime anchorCreateTime;
            List<Long> sourceIds;
            try {
                if (existingSummary.isEmpty()) {
                    // 查询最早 N 轮待合并的消息（首次合并场景）
                    toMerge = ChatHistorySchemaMigrationSupport.listOldestMessagesForMergeValidated(
                            appId, ChatHistoryConstant.MESSAGES_PER_MERGE, ChatHistoryConstant.ROUNDS_TO_MERGE,
                            this.getMapper(), excludeIds);
                    if (toMerge == null) {
                        log.warn("对话合并：不足两轮最早消息，跳过本次合并，appId={}", appId);
                        break;
                    }
                    // 调用 AI 对最早两轮做摘要合并
                    summaryText = ChatHistorySchemaMigrationSupport.summarizeTwoRoundsWithAi(toMerge, chatModel);
                    anchorCreateTime = toMerge.getFirst().getCreateTime();
                    sourceIds = toMerge.stream().map(ChatHistory::getId).filter(Objects::nonNull).toList();
                } else {
                    var pair = existingSummary.get();
                    // 查询最新一轮消息，将当前摘要与新轮次滚动合并
                    toMerge = ChatHistorySchemaMigrationSupport.listOldestMessagesForMergeValidated(
                            appId, 2, 1, this.getMapper(), excludeIds);
                    if (toMerge == null) {
                        log.warn("对话合并：不足一轮最早消息用于滚动并入摘要，跳过本次合并，appId={}", appId);
                        break;
                    }
                    // 调用 AI 将已有摘要与新轮次做滚动摘要
                    summaryText = ChatHistorySchemaMigrationSupport.summarizeWithExistingSummary(
                            pair.userSummary(), pair.aiSummary(), toMerge, chatModel);
                    anchorCreateTime = pair.anchorCreateTime() != null
                            ? pair.anchorCreateTime()
                            : toMerge.getFirst().getCreateTime();
                    sourceIds = new ArrayList<>(pair.sourceChatHistoryIds());
                    for (ChatHistory row : toMerge) {
                        if (row.getId() != null) {
                            sourceIds.add(row.getId());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("AI 总结对话失败，跳过本次合并，appId={}", appId, e);
                break;
            }
            // 解析 AI 返回的摘要文本为用户摘要/AI 摘要两部分
            String[] parsed = ChatHistorySchemaMigrationSupport.parseSummaryResponse(summaryText);
            // 将合并结果写入 memory_shrink（唯一摘要对），替换旧摘要
            memoryShrinkService.replaceConversationSummary(
                    appId, userId, anchorCreateTime, parsed[0], parsed[1], sourceIds);
            mergedCount++;
            log.info("已滚动合并写入 memory_shrink（唯一摘要对），appId={}, mergedCount={}", appId, mergedCount);
        }
        // 收集压缩后的统计信息供日志
        Set<Long> mergedAfter = memoryShrinkService.collectAllMergedSourceChatHistoryIds(appId);
        // 统计压缩后仍然未合并的 DB USER 轮数
        int dbUsersAfter = memoryShrinkService.countUnmergedDbUserRounds(appId, mergedAfter);
        // 统计压缩后的摘要轮数
        int summaryUsers = memoryShrinkService.countSummaryUserRounds(appId);
        log.info("会话级总结完成，compressType=conversation_summary, triggerReason={}, appId={}, beforeDbUsers={}, afterDbUsers={}, summaryUsers={}, mergedIterations={}",
                reason, appId, dbUsersBefore, dbUsersAfter, summaryUsers, mergedCount);
        boolean didCompress = mergedCount > 0;
        if (didCompress) {
            // 失效 echo 缓存，下次访问强制从 DB 重建
            chatHistoryEchoRedisSupport.invalidate(appId);
            MessageWindowChatMemory rebuildMemory = MessageWindowChatMemory.builder()
                    .id(appId)
                    .chatMemoryStore(chatMemoryStore)
                    .maxMessages(ChatHistoryConstant.CHAT_MEMORY_MAX_MESSAGES)
                    .build();
            rebuildMemory.clear();
            // 查询应用信息，确定代码生成类型用于后续 AI 记忆重建
            App app = appService.getById(appId);
            CodeGenTypeEnum codeGenTypeEnum = app != null ? CodeGenTypeEnum.getEnumByValue(app.getCodeGenType()) : null;
            // 从 memory_shrink 重建 AI ChatMemory，使最新摘要生效
            chatHistoryAiMemoryRebuildSupport.rebuildAiChatMemoryFromShrink(
                    appId, rebuildMemory, ChatHistoryConstant.MEMORY_PRELOAD_MESSAGE_ROWS, codeGenTypeEnum);
        }
        return didCompress;
    }

    /**
     * 单测反射入口：将 AI 长文压缩为适合 Redis 记忆的摘要片段
     *
     * @param rawMessage      原始 AI 消息正文
     * @param codeGenTypeEnum 代码生成类型（决定压缩策略）
     * @return 压缩后的文本
     */
    private String compactAiMessageForMemory(String rawMessage, CodeGenTypeEnum codeGenTypeEnum) {
        // 方法大纲：
        // 1. 转发到 ChatHistorySchemaMigrationSupport 执行 AI 长文压缩    L1045-L1046

        // 单测反射入口：转发到 support 做 AI 长文压缩
        return ChatHistorySchemaMigrationSupport.compactAiMessageForMemory(rawMessage, codeGenTypeEnum);
    }

    /**
     * HTML/MULTI_FILE 下对 Redis ChatMemory 中的非主 AI 长文做在线截断，并同步 message_truncate 到 memory_shrink
     * <p>典型触发：缓存命中 cache_hit、Workflow 质检通过 workflow_quality_pass；记忆轨改造后计划移除此类生产调用。</p>
     *
     * @param appId           应用 id
     * @param codeGenTypeEnum 代码生成类型
     * @param triggerReason   触发原因（日志用）
     */
    @Override
    public void compactMemoryMessagesIfNeeded(Long appId, CodeGenTypeEnum codeGenTypeEnum, String triggerReason) {
        // 方法大纲：
        // 1. 非法 appId 或非 HTML/MULTI_FILE 类型直接跳过    L1070-L1079
        // 2. 从 Redis 读取消息序列，对非豁免 AI 行应用 compact    L1080-L1109
        // 3. 若有变更则写回 Redis、同步 shrink 并刷新 TTL    L1110-L1120

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
                syncTruncateShrinksAfterRedisCompact(appId, messages, newList, codeGenTypeEnum);
                chatAiMemoryRedisSupport.refreshAiMemoryTtl(appId);
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

    /**
     * 委托 ChatAiMemoryRedisSupport 刷新 AI 轨 Redis TTL
     *
     * @param appId 应用 id
     */
    @Override
    public void refreshAiChatMemoryTtl(Long appId) {
        // 方法大纲：
        // 1. 委托 ChatAiMemoryRedisSupport 原样回写 Redis 消息以续期 AI 轨 TTL    L1133-L1134

        // 1. 原样 updateMessages 触发 LangChain4j Store 续期，不修改 ChatMemory 正文
        chatAiMemoryRedisSupport.refreshAiMemoryTtl(appId);
    }

    /**
     * 移除 Redis ChatMemory 尾部首个超长 AiMessage，用于失败轮重试前清理
     *
     * @param appId           应用 id
     * @param codeGenTypeEnum 代码生成类型（仅 HTML/MULTI_FILE 生效）
     * @param triggerReason   触发原因（日志用）
     * @return 成功移除目标 AI 消息为 true；未命中或跳过为 false
     */
    @Override
    public boolean removeLatestFailedAiMessageForRetry(Long appId, CodeGenTypeEnum codeGenTypeEnum, String triggerReason) {
        // 方法大纲：
        // 1. 非法 appId 或非 HTML/MULTI_FILE 类型直接返回 false    L1155-L1165
        // 2. 从 Redis 尾部向前扫描首个超长 AiMessage 下标    L1166-L1188
        // 3. 命中则移除并写回 Redis，返回 true    L1189-L1195

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


    /**
     * 按内容精确匹配删除最新一条 USER 消息，并清理 echo 缓存与 AI ChatMemory
     *
     * @param appId   应用 id
     * @param userId  用户 id
     * @param message 待匹配的用户消息正文
     * @return 成功删除为 true；未命中或失败为 false
     */
    @Override
    public boolean removeUserMessageByContent(Long appId, Long userId, String message) {
        // 方法大纲：
        // 1. 入参非法时直接 false    L1211-L1214
        // 2. 查最新一条MySQL同内容 USER 消息并按主键删除    L1215-L1232
        // 3. 删除Redis成功后失效 echo 缓存并清除 AI ChatMemory    L1233-L1246

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
            // 3. 命中则按主键删除该条用户消息
            boolean removed = this.removeById(latestMatches.getFirst().getId());
            if (!removed) {
                return false;
            }
            // 4. 删除成功后：失效 echo 缓存 + 清除 AI ChatMemory，防止 Redis 残留取消轮 UserMessage
            try {
                chatHistoryEchoRedisSupport.invalidate(appId);
            } catch (Exception e) {
                log.warn("没有成功的清理本应删除的失效的 echo 缓存，appId={}", appId, e);
            }
            try {
                // 先全部删除ai自带的mmemorystore,再后续获取aiservice的时候懒加载
                chatMemoryStore.deleteMessages(appId);
                log.info("AI ChatMemory 已清除（取消轮回滚后重建），appId={}", appId);
            } catch (Exception e) {
                log.warn("清除 AI ChatMemory 失败已忽略，appId={}", appId, e);
            }
            return true;
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
        // 方法大纲：
        // 1. 无 buffer 与耗时指标时，以 0 委托带指标的重载实现    L1268-L1269

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
        // 方法大纲：
        // 1. 关键主键缺失时直接返回（幂等）    L1289-L1292
        // 2. 委托 ConversationMemoryStateService 收口（失败自吞）    L1293-L1301

        // 1. 关键主键缺失时直接返回,得到幂等无操作的收口
        if (appId == null || appId <= 0 || roundId == null || roundId <= 0 || userId == null || userId <= 0) {
            return;
        }
        try {
            // 2. 委托 ConversationMemoryStateService 收口（失败自吞）
            // 1. 收口逻辑委托给独立 memory_state 服务，隔离主会话入库与 SSE 输出。
            // 2. 透传执行器采集的真实 bufferChars/elapsedMs，满足 E 指标口径。
            conversationMemoryStateService.onRoundCompleted(appId, roundId, userId, codeGenTypeEnum, workflowMode, bufferChars, elapsedMs);
        } catch (Exception e) {
            // 3. 失败自吞：严格遵循主链路隔离原则。
            log.warn("onRoundCompleted 执行失败已忽略，appId={}, roundId={}", appId, roundId, e);
        }
    }

    /**
     * 判断 workflow 下一轮生成前是否应触发会话级总结（依据最新 AI 消息状态）
     *
     * @param appId 应用 id
     * @return 最新 AI 含成功完成标记且非失败态时为 true
     */
    @Override
    public boolean shouldSummarizeBeforeWorkflowGeneration(Long appId) {
        // 方法大纲：
        // 1. 非法 appId 直接 false    L1316-L1319
        // 2. 查最新一条 AI 消息并判断是否含失败/成功标记    L1320-L1343

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
            if (latestLower.contains("[workflow] 生成失败")
                    || latest.contains(ChatHistoryConstant.GENERATION_FAILED_USER_MESSAGE)) {
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
