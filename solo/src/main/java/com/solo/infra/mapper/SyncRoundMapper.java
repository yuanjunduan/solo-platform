package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.sync.model.SyncRound;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 轮次台账（3.13.1）。
 *
 * <p>{@code task_id} 上有唯一键：一轮只能有一行台账。收尾按 taskId 更新，不新增第二行。</p>
 */
@Mapper
public interface SyncRoundMapper extends BaseMapper<SyncRound> {

    default SyncRound selectByTaskId(Long taskId) {
        List<SyncRound> list = selectList(new LambdaQueryWrapper<SyncRound>()
                .eq(SyncRound::getTaskId, taskId)
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 保留期分批删除（M7，5.2：轮次台账保留 1 年） */
    @Delete("""
            DELETE FROM sync_round WHERE id IN (
                SELECT id FROM (
                    SELECT id FROM sync_round WHERE begin_time < #{before} LIMIT #{limit}
                ) t
            )
            """)
    int deleteBeforeBatch(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
