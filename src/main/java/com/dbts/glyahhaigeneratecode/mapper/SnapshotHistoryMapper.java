package com.dbts.glyahhaigeneratecode.mapper;

import com.dbts.glyahhaigeneratecode.model.Entity.SnapshotHistory;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Delete;

/**
 * manifest 快照历史 映射层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
public interface SnapshotHistoryMapper extends BaseMapper<SnapshotHistory> {

    @Delete("DELETE FROM snapshot_history WHERE createdAt < DATE_SUB(NOW(), INTERVAL 30 DAY)")
    int deleteOlderThan30Days();
}
