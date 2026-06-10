package com.dbts.glyahhaigeneratecode.core.support;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.mapper.ChatHistoryMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dbts.glyahhaigeneratecode.constant.ChatHistoryMemoryCompactionConstant.*;

/**
 * 历史分页回显补 workflow 五阶段标记 -> AI 长文按类型压缩并豁免本轮主 AI -> Redis/DB 轮数统计与最早消息选取 -> LLM 两轮合并摘要写库 -> 缺失 audit 列或 message 非 longtext 时 DDL 补齐
 */
public final class ChatHistorySchemaMigrationSupport {

    private static final Logger log = LoggerFactory.getLogger(ChatHistorySchemaMigrationSupport.class);

    /**
     * 禁止实例化，本类仅提供静态工具方法供会话记忆与历史回显链路调用。
     */
    private ChatHistorySchemaMigrationSupport() {
    }


    /**
     * 在历史回显分页结果中为 workflow AI 消息追加五阶段状态标记行。
     * @param page 历史分页结果
     * @return 无
     */
    public static void appendWorkflowStageStatusForHistoryPage(Page<ChatHistory> page) {
        // 方法大纲：
        // 1. 遍历分页 AI 记录，筛选 workflow 相关消息
        // 2. 清理旧阶段标记并重建五阶段状态行拼接到 message 尾部（仅内存，不改 DB）

        // 1. 遍历分页 AI 记录，筛选 workflow 相关消息
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
    public static boolean isWorkflowRelatedHistoryMessage(String message) {
        // 1. 空串不可能是 workflow 产物,直接判定为 false 避免无意义扫描
        if (StrUtil.isBlank(message)) {
            return false;
        }
        // 2. 转小写后检查固定标记子串,得到是否属于 workflow 回显文本域
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
    public static String buildWorkflowStageStatusMarkerLine(String message) {
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
    public static int resolveFailedWorkflowStageIndex(String rawText, String lowerText) {
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
    public static boolean containsAnyKeyword(String lowerText, List<String> keywords) {
        // 1. 文本或关键词集合无效时无法匹配,直接返回 false
        if (StrUtil.isBlank(lowerText) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        // 2. 线性扫描关键词列表,任一子串命中即返回 true,得到失败/成功语义检索结果
        for (String keyword : keywords) {
            if (StrUtil.isBlank(keyword)) {
                continue;
            }
            if (lowerText.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        // 3. 全部关键词未命中则返回 false,表示当前文本不含目标词集合
        return false;
    }


    /**
     * 在灌 Redis 或在线压缩时，对单条 AI 长文做摘要（仅影响模型上下文，不改 DB 原文）。
     * 调用方对「最后一轮主 AI」下标应跳过本方法以保留全文；仅 HTML / MULTI_FILE 且超长时压缩。
     * @param rawMessage 原始 AI 消息正文
     * @param codeGenTypeEnum 应用代码生成类型
     * @return 压缩后的正文；空串时返回占位文本
     */
    public static String compactAiMessageForMemory(String rawMessage, CodeGenTypeEnum codeGenTypeEnum) {
        // 1. 空串直接回落占位文本,得到 LangChain4j 可接受的非 null AiMessage 内容
        if (StrUtil.isBlank(rawMessage)) {
            return EMPTY_AI_MEMORY_PLACEHOLDER;
        }
        // 2. 非 HTML/MULTI_FILE 类型不做压缩,得到与模型原始输出一致的上下文
        if (codeGenTypeEnum != CodeGenTypeEnum.HTML && codeGenTypeEnum != CodeGenTypeEnum.MULTI_FILE) {
            return rawMessage;
        }
        // 3. 未超过记忆长度阈值则原样返回,得到无需摘要的短消息
        if (rawMessage.length() <= MEMORY_AI_MESSAGE_MAX_LENGTH) {
            return rawMessage;
        }

        // 4. 并行抽取 html/css/js fenced 块并按上限截断,得到可拼入摘要头的关键代码片段
        String html = extractAndTrimCodeBlock(rawMessage, HTML_BLOCK_PATTERN, MEMORY_AI_CODE_BLOCK_KEEP_LENGTH, "HTML");
        String css = extractAndTrimCodeBlock(rawMessage, CSS_BLOCK_PATTERN, MEMORY_AI_CODE_BLOCK_KEEP_LENGTH, "CSS");
        String js = extractAndTrimCodeBlock(rawMessage, JS_BLOCK_PATTERN, MEMORY_AI_CODE_BLOCK_KEEP_LENGTH, "JavaScript");

        // 5. 组装固定提示头与三个代码块片段,得到面向模型的「压缩说明 + 代码摘要」正文骨架
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

        // 6. 若三个块都未提取到有效内容则退化为头部截断,得到仍有时间线语义占位的长文前缀
        // 若未提取到代码块，退化为头部截断，保证仍有历史语义
        if (sb.length() < 80) {
            String head = rawMessage.substring(0, Math.min(MEMORY_AI_MESSAGE_MAX_LENGTH, rawMessage.length()));
            return head + "\n...（历史内容已截断）";
        }
        // 7. 正常命中代码块时返回拼接摘要串,得到显著低于原文 token 的记忆视图
        return sb.toString();
    }


    /**
     * 从消息中提取首个 fenced 代码块并按长度截断，用于 AI 长文记忆压缩。
     * @param message 含 markdown 代码块的完整消息
     * @param pattern 匹配代码块的正则
     * @param maxLen 代码块正文允许的最大长度
     * @param langLabel 语言标签（用于截断提示文案）
     * @return 提取并截断后的代码文本；未匹配到块时返回空串
     */
    public static String extractAndTrimCodeBlock(String message, Pattern pattern, int maxLen, String langLabel) {
        // 1. 用传入正则扫描全文,得到 fenced 代码块捕获组或空串
        Matcher matcher = pattern.matcher(message);
        if (!matcher.find()) {
            return "";
        }
        // 2. 取出首个匹配组并去空白,得到纯代码文本
        String code = StrUtil.blankToDefault(matcher.group(1), "");
        // 3. 未超长则原样返回,否则加注释头后截断到 maxLen,得到可嵌入摘要的短前缀
        if (code.length() <= maxLen) {
            return code;
        }
        String safePrefix = "/* 历史" + langLabel + "代码片段（已截断） */\n";
        // 4. 拼接安全前缀、截断主体与尾部提示,得到单语言块的最终压缩片段
        return safePrefix + code.substring(0, maxLen) + "\n// ...历史代码片段已截断";
    }


    /**
     * 在按时间正序排列的 chat_history 行中，定位最后一条 AI 记录的索引。
     * @param chronologicalOldToNew 从早到晚的历史行列表
     * @return 最后一条 AI 的下标；无 AI 行时返回 -1
     */
    private static int indexOfLastAiChatHistoryRow(List<ChatHistory> chronologicalOldToNew) {
        // 1. 从列表尾部向前扫描,得到时间轴上最后一条 AI 行的下标
        for (int i = chronologicalOldToNew.size() - 1; i >= 0; i--) {
            ChatHistoryMessageTypeEnum typeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(
                    chronologicalOldToNew.get(i).getMessageType());
            if (typeEnum == ChatHistoryMessageTypeEnum.AI) {
                return i;
            }
        }
        // 2. 全程未命中 AI 行则返回 -1,表示调用方不应做「最后 AI」相关豁免
        return -1;
    }


    /**
     * 在 Redis ChatMemory 消息序列中，定位最后一条 {@link AiMessage} 的下标。
     * @param messages Redis 中的 ChatMessage 列表
     * @return 最后一条 AiMessage 的下标；无 AI 消息时返回 -1
     */
    private static int indexOfLastAiChatMessage(List<ChatMessage> messages) {
        // 1. 从内存消息列表尾部向前扫描,得到最后一条 AiMessage 实例的下标
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage) {
                return i;
            }
        }
        // 2. 未找到 AiMessage 时返回 -1,与 DB 侧语义保持一致
        return -1;
    }


    /**
     * 统计数据库中某应用的 USER 类型消息条数，作为对话「轮数」。
     * @param appId 应用 id
     * @param chatHistoryMapper 对话历史 Mapper
     * @return USER 消息条数
     */
    public static int countUserRoundsFromDatabase(Long appId, ChatHistoryMapper chatHistoryMapper) {
        // 1. 构造仅统计 USER 类型条数的条件,得到该应用在 DB 中的「轮数」近似值
        QueryWrapper q = new QueryWrapper();
        q.eq(ChatHistory::getAppId, appId);
        q.eq(ChatHistory::getMessageType, ChatHistoryMessageTypeEnum.USER.getValue());
        // 2. 调用 MyBatis-Flex count,得到整数轮数供阈值判断使用
        return (int) chatHistoryMapper.selectCountByQuery(q);
    }


    /**
     * 会话压缩入口用「轮数」：优先 Redis 中 User 条数；Redis 空或 0 user 时回退 DB。
     * @param appId 应用 id
     * @param chatMemoryStore Redis 对话记忆存储
     * @param chatHistoryMapper 对话历史 Mapper（Redis 不可用时回退统计）
     * @return 有效用户轮数
     */
    public static int resolveEffectiveUserRoundCountForSummarize(Long appId, ChatMemoryStore chatMemoryStore, ChatHistoryMapper chatHistoryMapper) {
        try {
            // 1. 优先从 Redis ChatMemoryStore 读取消息列表,得到在线会话轮数的一手统计来源
            List<ChatMessage> messages = chatMemoryStore.getMessages(appId);
            if (messages == null || messages.isEmpty()) {
                log.info("从 Redis 统计轮数，appId={}, rounds=回退DB, reason=empty", appId);
                return countUserRoundsFromDatabase(appId, chatHistoryMapper);
            }
            // 2. 遍历 Redis 消息累加 UserMessage 条数,得到在线视角下的轮数统计值
            int userCount = 0;
            for (ChatMessage message : messages) {
                if (message instanceof UserMessage) {
                    userCount++;
                }
            }
            if (userCount == 0) {
                log.info("从 Redis 统计轮数，appId={}, rounds=回退DB, reason=noUserMessage", appId);
                return countUserRoundsFromDatabase(appId, chatHistoryMapper);
            }
            log.info("从 Redis 统计轮数，appId={}, rounds={}", appId, userCount);
            return userCount;
        } catch (Exception e) {
            log.warn("从 Redis 统计对话轮数失败，回退到数据库统计, appId={}", appId, e);
            return countUserRoundsFromDatabase(appId, chatHistoryMapper);
        }
    }


    /**
     * 在时间正序 chat_history 中，定位「本轮主 AI」行下标（压缩时保留全文）；无合适 AI 时回退到最后一条 AI。
     * @param chronologicalOldToNew 从早到晚的历史行列表
     * @param type 代码生成类型（仅 HTML / MULTI_FILE 参与豁免）
     * @return 应豁免压缩的 AI 行下标；-1 表示不豁免
     */
    public static int indexOfExemptAiCompactionChatRows(List<ChatHistory> chronologicalOldToNew, CodeGenTypeEnum type) {
        // 1. 列表为空或类型非 HTML/MULTI_FILE 时不做豁免,得到调用方应全量压缩的语义（返回 -1）
        if (chronologicalOldToNew == null || chronologicalOldToNew.isEmpty()
                || (type != CodeGenTypeEnum.HTML && type != CodeGenTypeEnum.MULTI_FILE)) {
            return -1;
        }
        // 2. 从尾部向前寻找最后一个 USER 行下标,得到「当前轮」起点
        int lastUserIdx = -1;
        for (int i = chronologicalOldToNew.size() - 1; i >= 0; i--) {
            ChatHistoryMessageTypeEnum t = ChatHistoryMessageTypeEnum.getEnumByValue(chronologicalOldToNew.get(i).getMessageType());
            if (t == ChatHistoryMessageTypeEnum.USER) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx < 0) {
            return -1;
        }
        // 3. 在最后一个 User 之后的子序列中挑选文本最长的 AI 行,得到应保留全文的「主 AI」下标候选
        int bestIdx = -1;
        int bestLen = -1;
        for (int i = lastUserIdx + 1; i < chronologicalOldToNew.size(); i++) {
            ChatHistory h = chronologicalOldToNew.get(i);
            if (ChatHistoryMessageTypeEnum.getEnumByValue(h.getMessageType()) != ChatHistoryMessageTypeEnum.AI) {
                continue;
            }
            String msg = StrUtil.blankToDefault(h.getMessage(), "");
            int len = msg.length();
            if (len > bestLen || (len == bestLen && i > bestIdx)) {
                bestLen = len;
                bestIdx = i;
            }
        }
        if (bestIdx < 0) {
            // 4. 子序列无 AI 时回退全局最后一条 AI,得到尽量合理的豁免目标
            return indexOfLastAiChatHistoryRow(chronologicalOldToNew);
        }
        // 5. 返回最佳 AI 行下标,供压缩逻辑跳过该行的全文截断
        return bestIdx;
    }


    /**
     * 在 Redis ChatMessage 序列中，定位「本轮主 AI」下标（在线压缩时保留全文）。
     * @param messages Redis 中的 ChatMessage 列表
     * @param type 代码生成类型（仅 HTML / MULTI_FILE 参与豁免）
     * @return 应豁免压缩的 AiMessage 下标；-1 表示不豁免
     */
    public static int indexOfExemptAiCompactionRedisMessages(List<ChatMessage> messages, CodeGenTypeEnum type) {
        // 1. 内存序列为空或类型非 HTML/MULTI_FILE 时不豁免,返回 -1
        if (messages == null || messages.isEmpty()
                || (type != CodeGenTypeEnum.HTML && type != CodeGenTypeEnum.MULTI_FILE)) {
            return -1;
        }
        // 2. 从尾部向前寻找最后一条 UserMessage 下标,得到当前轮对话起点
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx < 0) {
            return -1;
        }
        // 3. 在最后一个 User 之后子序列中挑选文本最长的 AiMessage,得到在线压缩豁免目标下标
        int bestIdx = -1;
        int bestLen = -1;
        for (int i = lastUserIdx + 1; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof AiMessage ai)) {
                continue;
            }
            String text = ai.text();
            int len = text != null ? text.length() : 0;
            if (len > bestLen || (len == bestLen && i > bestIdx)) {
                bestLen = len;
                bestIdx = i;
            }
        }
        if (bestIdx < 0) {
            // 3. 子序列无 AI 时回退全局最后一条 AiMessage 下标,保持与 DB 侧策略一致
            return indexOfLastAiChatMessage(messages);
        }
        // 4. 返回最佳 AI 下标,供在线压缩跳过该条全文截断
        return bestIdx;
    }


    /**
     * 按应用 codeGenType 将对应系统 Prompt 追加到 Redis 管理的对话内存中。
     * @param appId 应用 id
     * @param messageWindowChatMemory LangChain4j 窗口记忆实例
     * @param appService 应用服务，用于读取 codeGenType
     * @return 无
     */
    public static void appendSystemPromptToMemory(Long appId, MessageWindowChatMemory messageWindowChatMemory, AppService appService) {
        // 1. 读取应用实体,得到 codeGenType 与后续 classpath 提示词路径选择依据
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

        // 2. 按生成类型映射到资源路径字符串,得到待读取的系统提示词文件位置
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
            // 3. 将系统提示词包装为 AiMessage 写入 ChatMemory,得到模型侧可见的全局指令
            messageWindowChatMemory.add(new AiMessage(systemPrompt));
        }
    }


    /**
     * 从 classpath 读取系统 Prompt 文本文件（支持 classpath: 前缀与首尾空白规范化）。
     * @param classpathLocation 资源路径，如 Prompt/Single_File_Prompt.txt
     * @return UTF-8 文件全文
     */
    public static String readPromptFromClasspath(String classpathLocation) {
        // 1. 入参路径为空时直接抛系统异常,得到明确的配置错误信号
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

        // 2. 通过 ClassPathResource 打开输入流并读取 UTF-8 文本,得到提示词文件完整内容
        try (InputStream inputStream = new ClassPathResource(normalized).getInputStream()) {
            return IoUtil.readUtf8(inputStream);
        } catch (Exception e) {
            log.error("读取系统提示词失败, path={}", normalized, e);
            throw new MyException(ErrorCode.SYSTEM_ERROR, "读取系统提示词失败");
        }
    }


    /**
     * 将两轮合并后的用户摘要与 AI 摘要各写入一条 chat_history 记录。
     * @param appId 应用 id
     * @param userId 用户 id
     * @param anchorCreateTime 合并轮在时间轴上的锚定创建时间
     * @param userSummary 用户侧摘要正文
     * @param aiSummary AI 侧摘要正文
     * @param chatHistoryMapper 对话历史 Mapper
     * @return 无
     */
    public static void saveMergedRoundSummaryRows(Long appId, Long userId, LocalDateTime anchorCreateTime,
                                                      String userSummary, String aiSummary, ChatHistoryMapper chatHistoryMapper) {
        // 1. 取当前时间作为 updateTime,与 anchorCreateTime 一起保证合并轮在时间轴上占位正确
        LocalDateTime now = LocalDateTime.now();
        ChatHistory userRow = ChatHistory.builder()
                .appId(appId)
                .userId(userId)
                .message(userSummary)
                .messageType(ChatHistoryMessageTypeEnum.USER.getValue())
                .auditAction("SKIP")
                .auditHitRule("NONE")
                .createTime(anchorCreateTime)
                .updateTime(now)
                .build();
        // 2. 先写入用户摘要行,再写入 AI 摘要行,得到合并后的一轮对话在 DB 中的两条记录
        chatHistoryMapper.insert(userRow);
        ChatHistory aiRow = ChatHistory.builder()
                .appId(appId)
                .userId(userId)
                .message(aiSummary)
                .messageType(ChatHistoryMessageTypeEnum.AI.getValue())
                .auditAction("SKIP")
                .auditHitRule("NONE")
                .createTime(anchorCreateTime)
                .updateTime(now)
                .build();
        chatHistoryMapper.insert(aiRow);
    }


    /**
     * 按 appId 统计对话轮数（优先 Redis User 条数，失败或为空时回退 DB），供内部压缩逻辑使用。
     * @param appId 应用 id
     * @param chatMemoryStore Redis 对话记忆存储
     * @param chatHistoryMapper 对话历史 Mapper
     * @return 有效用户轮数
     */
    public static int countRoundsByAppIdInternal(Long appId, ChatMemoryStore chatMemoryStore, ChatHistoryMapper chatHistoryMapper) {
        // 1. 委托有效轮数解析逻辑,得到优先 Redis、失败回退 DB 的轮数统计值
        return resolveEffectiveUserRoundCountForSummarize(appId, chatMemoryStore, chatHistoryMapper);
    }


    /**
     * 按创建时间正序取该应用下最早的若干条消息，供两轮合并使用。
     * @param appId 应用 id
     * @param limit 最多取几条
     * @param chatHistoryMapper 对话历史 Mapper
     * @return 最早的消息列表（通常 4 条，对应两轮 user+ai）
     */
    public static List<ChatHistory> listOldestMessagesForMerge(Long appId, int limit, ChatHistoryMapper chatHistoryMapper) {
        // 1. 无排除 id 时委托四参重载，得到最早 limit 条未过滤 chat_history
        return listOldestMessagesForMerge(appId, limit, chatHistoryMapper, null);
    }

    /**
     * 按创建时间正序取最早若干条消息，排除已合并进 memory_shrink 的 chat_history id。
     *
     * @param appId                 应用 id
     * @param limit                   最多取几条
     * @param chatHistoryMapper       对话历史 Mapper
     * @param excludeChatHistoryIds   已纳入 summary 的 chat_history id，可为 null
     * @return 最早的消息列表（通常 4 条）
     */
    public static List<ChatHistory> listOldestMessagesForMerge(Long appId, int limit,
                                                               ChatHistoryMapper chatHistoryMapper,
                                                               java.util.Set<Long> excludeChatHistoryIds) {
        // 方法大纲：
        // 1. 构造按 createTime/id 正序的查询，排除已纳入 memory_shrink 的 chat_history id
        // 2. 限制条数并返回最早若干条 ChatHistory

        // 1. 构造按 createTime/id 正序的查询，排除已纳入 memory_shrink 的 chat_history id
        QueryWrapper q = new QueryWrapper();
        q.eq(ChatHistory::getAppId, appId);
        if (excludeChatHistoryIds != null && !excludeChatHistoryIds.isEmpty()) {
            q.notIn(ChatHistory::getId, excludeChatHistoryIds);
        }
        q.orderBy(ChatHistory::getCreateTime, true);
        q.orderBy(ChatHistory::getId, true);
        q.limit(limit);
        // 2. 执行 list 查询,得到最早 limit 条 ChatHistory 实体
        return chatHistoryMapper.selectListByQuery(q);
    }

    /**
     * 取最早若干条未合并消息，并校验 USER 轮数（2 轮需 4 条且 2 个 USER；已有摘要再合并 1 轮需 2 条且 1 个 USER）。
     *
     * @param appId                 应用 id
     * @param messageLimit          最多取几条（4 或 2）
     * @param expectedUserRounds    期望的 USER 轮数（2 或 1）
     * @param chatHistoryMapper     Mapper
     * @param excludeChatHistoryIds 已合并 id
     * @return 校验通过的消息列表；不足或轮数不符时返回 null
     */
    public static List<ChatHistory> listOldestMessagesForMergeValidated(Long appId, int messageLimit,
                                                                        int expectedUserRounds,
                                                                        ChatHistoryMapper chatHistoryMapper,
                                                                        Set<Long> excludeChatHistoryIds) {
        // 方法大纲：
        // 1. 取最早 messageLimit 条未合并消息
        // 2. 校验条数与 USER 轮数是否符合合并策略（2 轮需 4 条 2 USER，续合并 1 轮需 2 条 1 USER）

        // 1. 取最早 messageLimit 条未合并消息
        List<ChatHistory> rows = listOldestMessagesForMerge(appId, messageLimit, chatHistoryMapper, excludeChatHistoryIds);
        if (rows == null || rows.size() < messageLimit) {
            return null;
        }
        // 2. 统计 USER 条数并与期望值比对，不符则返回 null 阻断合并
        int userCount = 0;
        for (ChatHistory row : rows) {
            if (ChatHistoryMessageTypeEnum.USER.getValue().equals(row.getMessageType())) {
                userCount++;
            }
        }
        if (userCount != expectedUserRounds) {
            log.warn("对话合并：最早 {} 条消息中 USER 轮数为 {}，期望 {}，appId={}",
                    messageLimit, userCount, expectedUserRounds, appId);
            return null;
        }
        return rows;
    }


    /**
     * 调用大模型将两轮对话（4 条消息）压缩为带【用户总结】【AI总结】标记的文本。
     * @param fourMessages 最早两轮的四条历史消息（user+ai）
     * @param chatModel 用于总结的 ChatModel
     * @return 模型输出的总结原文
     */
    public static String summarizeTwoRoundsWithAi(List<ChatHistory> fourMessages, ChatModel chatModel) {
        // 1. 将四轮消息（两轮 user/ai）拼成带角色标签的长文本,得到送入总结模型的原始材料
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
        // 2. 调用注入的 ChatModel 执行一次非流式 chat,得到模型输出的总结原文
        return chatModel.chat(prompt);
    }

    /**
     * 在已有会话摘要基础上，再合并若干条 chat_history（通常 1 轮 2 条或 2 轮 4 条）。
     *
     * @param existingUserSummary 已有用户摘要
     * @param existingAiSummary   已有 AI 摘要
     * @param messages            待并入的最早消息（正序）
     * @param chatModel           总结模型
     * @return 模型输出的总结原文
     */
    public static String summarizeWithExistingSummary(String existingUserSummary, String existingAiSummary,
                                                      List<ChatHistory> messages, ChatModel chatModel) {
        // 方法大纲：
        // 1. 拼接已有 user/ai 摘要与待合并新对话正文
        // 2. 构造增量合并提示词并调用 ChatModel，得到带固定标记的新摘要原文

        // 1. 拼接已有 user/ai 摘要与待合并新对话正文
        StringBuilder content = new StringBuilder();
        content.append("【已有用户摘要】").append(StrUtil.blankToDefault(existingUserSummary, "")).append("\n");
        content.append("【已有AI摘要】").append(StrUtil.blankToDefault(existingAiSummary, "")).append("\n\n");
        content.append("【待合并的新对话】\n");
        for (int i = 0; i < messages.size(); i++) {
            ChatHistory m = messages.get(i);
            String role = ChatHistoryMessageTypeEnum.USER.getValue().equals(m.getMessageType()) ? "用户" : "AI";
            content.append("新对话-").append(role).append("：").append(m.getMessage()).append("\n");
        }
        // 2. 构造增量合并提示词并调用 ChatModel，得到带固定标记的新摘要原文
        String prompt = "你是一个对话总结助手。请在「已有摘要」基础上，将「待合并的新对话」并入为一段更完整的摘要，"
                + "严格按以下格式输出，不要其他内容：\n"
                + "【用户总结】用户的主要问题和诉求摘要\n"
                + "【AI总结】AI的回复要点摘要\n\n"
                + content;
        return chatModel.chat(prompt);
    }


    /**
     * 从 AI 返回文本中解析「用户总结」与「AI总结」两段；格式不符时使用占位摘要。
     * @param summaryText 模型返回的总结全文
     * @return 长度为 2 的数组：[0] 用户摘要，[1] AI 摘要
     */
    public static String[] parseSummaryResponse(String summaryText) {
        // 1. 准备默认占位摘要,在解析失败时仍返回长度为 2 的数组,得到调用方可安全解构的结构
        String userSummary = "（历史对话摘要）";
        String aiSummary = "（历史回复摘要）";
        if (StrUtil.isBlank(summaryText)) {
            return new String[]{userSummary, aiSummary};
        }
        // 2. 查找固定标记位置并截取两段文本,过长时硬截断到 2000 字,得到可落库的 user/ai 摘要字段
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
        // 3. 返回解析后的二元组,供合并写库使用
        return new String[]{userSummary, aiSummary};
    }


    /**
     * 写库或合并前检查 chat_history 表结构：缺失 audit 列则 DDL 追加，message 非 longtext 则扩列。
     * @param appId 应用 id（当前实现未使用，保留供后续按库分表扩展）
     * @param chatHistoryMapper 对话历史 Mapper，用于查 information_schema 与执行 DDL
     * @return 无
     */
    public static void ensureAuditColumnsIfMissing(Long appId, ChatHistoryMapper chatHistoryMapper) {
        // 1. 探测 auditAction 列是否存在于当前库表定义中,得到是否需要执行 DDL 追加
        boolean actionExists = isColumnExists(chatHistoryMapper, "auditAction");
        // 2. 并行探测 auditHitRule 与 message 列形态,与 auditAction 一起决定后续 DDL 分支
        boolean hitRuleExists = isColumnExists(chatHistoryMapper, "auditHitRule");
        boolean messageIsLongText = isMessageColumnLongText(chatHistoryMapper);
        if (!actionExists) {
            // 3. 列缺失时调用 mapper DDL,得到后续 insert 可写入 auditAction 的表结构
            chatHistoryMapper.alterChatHistoryAddAuditAction();
        }
        if (!hitRuleExists) {
            // 4. 同理补齐 auditHitRule 列,保证审查命中规则可持久化
            chatHistoryMapper.alterChatHistoryAddAuditHitRule();
        }
        if (!messageIsLongText) {
            // 5. 若 message 仍为 varchar 等短类型则升级为 longtext,避免长对话写库失败
            chatHistoryMapper.alterChatHistoryMessageToLongText();
        }
    }


    /**
     * 判断 chat_history 表是否已存在指定列名。
     * @param chatHistoryMapper 对话历史 Mapper
     * @param columnName 列名
     * @return true-列已存在；false-不存在
     */
    private static boolean isColumnExists(ChatHistoryMapper chatHistoryMapper, String columnName) {
        // 1. 查询 information_schema 中列元数据计数,得到该列是否已存在于 chat_history 表
        Integer count = chatHistoryMapper.countChatHistoryInformationSchemaColumn(columnName);
        // 2. 将查询条数与阈值比较,得到列是否已存在的布尔结论
        return count != null && count > 0;
    }


    /**
     * 判断 chat_history.message 列当前是否为 longtext 类型。
     * @param chatHistoryMapper 对话历史 Mapper
     * @return true-已是 longtext；false-仍为较短类型
     */
    private static boolean isMessageColumnLongText(ChatHistoryMapper chatHistoryMapper) {
        // 1. 读取 message 列 data_type 字符串,得到是否为 longtext 类型以决定扩列策略
        String dataType = chatHistoryMapper.selectChatHistoryMessageDataType();
        // 2. 与 longtext 字面比较（忽略大小写）,得到 message 列是否已为长文本类型
        return "longtext".equalsIgnoreCase(StrUtil.blankToDefault(dataType, ""));
    }
}
