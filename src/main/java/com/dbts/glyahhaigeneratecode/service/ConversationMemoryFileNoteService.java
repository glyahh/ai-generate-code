package com.dbts.glyahhaigeneratecode.service;

/**
 * 工具写盘后的 fileNote 待摘要队列与批量 flush。
 */
public interface ConversationMemoryFileNoteService {

    /**
     * 登记本轮待摘要路径（last-write-wins）。
     *
     * @param appId        应用 id
     * @param relativePath 项目内相对路径
     * @param changeHint   变更片段或写盘内容截断，可为空
     */
    void registerPendingFileChange(Long appId, String relativePath, String changeHint);

    /**
     * 将 pending 路径批量摘要并 merge 进 fileNotesJson。
     *
     * @param appId   应用 id
     * @param roundId 当前轮次 id
     * @return 合并后的 fileNotesJson 字符串；无 pending 时返回 {@code null}（调用方保留 DB 原值）
     */
    String flushPendingFileNotes(Long appId, Long roundId);
}
