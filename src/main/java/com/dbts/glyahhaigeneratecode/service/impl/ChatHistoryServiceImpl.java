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
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    @Resource
    private ChatModel chatModel;

    /** 用于在 Redis 中管理对话记忆和轮数统计，仅通过压缩 Redis 降低上下文长度（不修改 DB） */
    @Resource
    private ChatMemoryStore chatMemoryStore;

    @Override
    public boolean addChatMessage(Long appId, String message, String messageType, Long userId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(messageType), ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户ID不能为空");
        // 验证消息类型是否有效
        ChatHistoryMessageTypeEnum messageTypeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(messageType);
        ThrowUtils.throwIf(messageTypeEnum == null, ErrorCode.PARAMS_ERROR, "不支持的消息类型: " + messageType);
        ChatHistory chatHistory = ChatHistory.builder()
                .appId(appId)
                .message(message)
                .messageType(messageType)
                .userId(userId)
                .build();
        return this.save(chatHistory);
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
        return this.page(Page.of(1, pageSize), queryWrapper);
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

        // 最小改动：从 MySQL 恢复历史时，过滤掉「曾被误存为 AI 回复」的系统提示词，避免出现两条提示词（AI + SYSTEM）
        String systemPromptForFilter = null;
        try {
            // 优先按应用 codeGenType 取对应系统提示词（与 appendSystemPromptToMemory 一致）
            App app = appService.getById(addId);
            if (app != null) {
                CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
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
                    // 过滤：若 DB 中这条 AI 消息内容就是系统提示词，则不再写入 Redis
                    if (StrUtil.isNotBlank(systemPromptForFilter)
                            && StrUtil.isNotBlank(history.getMessage())
                            && history.getMessage().trim().equals(systemPromptForFilter.trim())) {
                        log.debug("跳过写入 Redis：MySQL 中该条 AI 消息为系统提示词，appId={}", addId);
                        break;
                    }
                    messageWindowChatMemory.add(new AiMessage(history.getMessage()));
                    restoredCount++;
                    break;
                default:
                    // ERROR 等类型不写入内存
                    log.error("在将history写入Redis管理的ChatMemory出错, 出现error枚举类");
                    break;
            }
        }
        log.info("已将对话历史加载到内存，appId={}, count={}", addId, restoredCount);

        // 根据应用的代码生成类型追加对应的系统提示词到对话内存，避免混入无关 Prompt 干扰上下文
        appendSystemPromptToMemory(addId, messageWindowChatMemory);

        return restoredCount;
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
        log.info("统计对话轮数，appId={}, rounds={}", appId, count);
        return (int) count;
    }

    @Override
    public void trySummarizeOldestRoundsIfNeeded(Long appId, Long userId) {
        if (appId == null || appId <= 0 || userId == null) {
            return;
        }
        // 仅统计该应用下用户消息条数作为「轮数」，不校验登录态（内部在对话完成后调用）
        int roundCount = countRoundsByAppIdInternal(appId);
        if (roundCount <= ChatHistoryConstant.MAX_ROUNDS_BEFORE_SUMMARY) {
            return;
        }
        // 关键：每多 2 轮就合并最早 2 轮为 1 轮，循环直到不超过 20 轮（例如 26 轮 → 合并 6 次 → 20 轮）
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
            log.info("已将最早两轮合并为一轮并同步 Redis，appId={}, 当前约轮数={}", appId, roundCount);
        }
    }

    /**
     * 仅按 appId 统计 Redis 中用户消息条数（即「轮数」），不做权限校验，供内部压缩逻辑使用。
     * 若 Redis 统计失败，则回退到基于 DB 的统计，避免影响整体流程。
     */
    private int countRoundsByAppIdInternal(Long appId) {
        try {
            List<ChatMessage> messages = chatMemoryStore.getMessages(appId);
            if (messages == null || messages.isEmpty()) {
                return 0;
            }
            int userCount = 0;
            for (ChatMessage message : messages) {
                if (message instanceof UserMessage) {
                    userCount++;
                }
            }
            return userCount;
        } catch (Exception e) {
            log.warn("从 Redis 统计对话轮数失败，回退到数据库统计, appId={}", appId, e);
            QueryWrapper q = new QueryWrapper();
            q.eq(ChatHistory::getAppId, appId);
            q.eq(ChatHistory::getMessageType, ChatHistoryMessageTypeEnum.USER.getValue());
            return (int) this.count(q);
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
        } catch (Exception e) {
            log.error("同步 Redis 对话记忆失败，appId={}", appId, e);
        }
    }
}
