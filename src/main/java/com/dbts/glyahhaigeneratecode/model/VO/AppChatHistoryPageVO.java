package com.dbts.glyahhaigeneratecode.model.VO;

import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.mybatisflex.core.paginate.Page;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 应用对话历史分页结果（附带 app.is_beta，供前端判断是否展示工作流进度卡片）。
 */
@Data
public class AppChatHistoryPageVO implements Serializable {

    private List<ChatHistory> records;
    private Long pageNumber;
    private Long pageSize;
    private Long totalPage;
    private Long totalRow;

    /**
     * 是否 workflow beta 应用：0-否，1-是。
     */
    private Integer isBeta;

    private static final long serialVersionUID = 1L;

    public static AppChatHistoryPageVO from(Page<ChatHistory> page, Integer isBeta) {
        AppChatHistoryPageVO vo = new AppChatHistoryPageVO();
        if (page != null) {
            vo.setRecords(page.getRecords());
            vo.setPageNumber(page.getPageNumber());
            vo.setPageSize(page.getPageSize());
            vo.setTotalPage(page.getTotalPage());
            vo.setTotalRow(page.getTotalRow());
        }
        vo.setIsBeta(isBeta != null ? isBeta : 0);
        return vo;
    }
}
