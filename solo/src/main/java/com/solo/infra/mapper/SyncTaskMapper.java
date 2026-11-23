package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.sync.model.SyncTask;
import com.solo.domain.sync.model.SyncTaskStatus;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步任务台账。
 *
 * <p>同平台互斥靠库里的 {@code uk_sync_task_active}（在生成列上），不靠这里查一把再插——
 * 先查后插在并发下必然漏，正确做法是直接尝试插入、捕获唯一键冲突（C24）。</p>
 */
@Mapper
public interface SyncTaskMapper extends BaseMapper<SyncTask> {

    /** 该平台当前是否已有活跃任务（仅用于给出友好提示，互斥判定以插入结果为准） */
    default SyncTask selectActiveByPlatform(Long platformId) {
        List<SyncTask> list = selectList(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getPlatformId, platformId)
                .in(SyncTask::getStatus, (Object[]) SyncTaskStatus.ACTIVE)
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 只更新"还在活跃态"的任务，避免迟到的批次把已失败/已取消的会话又改回 SYNCHING。
     *
     * @return 受影响行数，0 = 会话已不在活跃态，调用方应丢弃这批
     */
    default int updateIfActive(SyncTask task) {
        return update(task, new LambdaUpdateWrapper<SyncTask>()
                .eq(SyncTask::getId, task.getId())
                .in(SyncTask::getStatus, (Object[]) SyncTaskStatus.ACTIVE));
    }

    /**
     * 该平台最近一轮（任何状态）。平台列表的"最近同步"与失败冷却提示用它。
     *
     * <p>按 {@code create_time} 倒序而不是 {@code end_time}：失败/取消的轮次可能没有 end_time，
     * 按 end_time 排序会把它们永久排到后面，界面上看起来"最后一次成功"。</p>
     */
    default SyncTask selectLatestByPlatform(Long platformId) {
        List<SyncTask> list = selectList(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getPlatformId, platformId)
                .orderByDesc(SyncTask::getCreateTime)
                .orderByDesc(SyncTask::getId)
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 保留期分批删除（M7，5.2；sync_round 保留 1 年，任务与台账同窗口） */
    @Delete("""
            DELETE FROM sync_task WHERE id IN (
                SELECT id FROM (
                    SELECT id FROM sync_task WHERE create_time < #{before} LIMIT #{limit}
                ) t
            )
            """)
    int deleteBeforeBatch(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
