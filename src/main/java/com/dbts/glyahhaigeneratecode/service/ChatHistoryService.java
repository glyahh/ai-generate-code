package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.DTO.ChatHistoryQueryRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.ChatHistoryVO;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话历史 服务层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
public interface ChatHistoryService extends IService<ChatHistory> {

    /**
     * 添加一条对话消息
     *
     * @param appId       应用 id
     * @param message     消息内容
     * @param messageType 消息类型（user/ai/error）
     * @param userId      用户 id
     * @return 是否保存成功
     */
    boolean addChatMessage(Long appId, String message, String messageType, Long userId);

    /**
     * 分页查询某个应用的对话历史（基于时间游标，向前加载）
     *
     * @param appId          应用 id
     * @param pageSize       每次加载条数
     * @param lastCreateTime 游标时间（传 null 加载最新的，传具体时间加载早于该时间的消息）
     * @param loginUser      当前登录用户（用于权限校验）
     * @return 分页结果
     */
    Page<ChatHistory> listAppChatHistoryByPage(Long appId, int pageSize, LocalDateTime lastCreateTime, User loginUser);

    /**
     * 根据应用 id 删除所有对话历史（逻辑删除）
     *
     * @param appId 应用 id
     * @return 是否删除成功
     */
    boolean removeByAppId(Long appId);

    /**
     * 构建 QueryWrapper
     *
     * @param queryRequest 查询请求
     * @return QueryWrapper
     */
    QueryWrapper buildQueryWrapper(ChatHistoryQueryRequest queryRequest);

    /**
     * 将实体转换为 VO
     *
     * @param chatHistory 对话历史实体
     * @return 对话历史 VO
     */
    ChatHistoryVO getChatHistoryVO(ChatHistory chatHistory);

    /**
     * 批量将实体转换为 VO
     *
     * @param chatHistoryList 对话历史实体列表
     * @return 对话历史 VO 列表
     */
    List<ChatHistoryVO> getChatHistoryVOList(List<ChatHistory> chatHistoryList);

    /**
     * 将对话历史转为内存
     *
     * @param addId                   应用 id
     * @param messageWindowChatMemory 聊天内存
     * @param maxCount                最大数量
     * @return 转换后的数量
     */
    int turnHistoryToMemory (Long addId, MessageWindowChatMemory messageWindowChatMemory, int maxCount);
}
