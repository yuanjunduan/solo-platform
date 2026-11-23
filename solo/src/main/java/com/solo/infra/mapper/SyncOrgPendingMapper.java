package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.sync.model.SyncOrgPending;
import com.solo.infra.mapper.projection.PendingPlatform;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 组织缺失设备的延迟重试队列（3.8.2）。
 *
 * <p>{@code capture} 在 finish 事务内执行：把本轮落到 {@code [未分类]} 的设备连同
 * 它们的 {@code parent_code} 抄进队列，收尾清 staging 后仍能重走 4 步回填。
 * {@code deleteResolved} 把已经归属成功的行删掉，队列只留"还没归位"的设备。</p>
 */
@Mapper
public interface SyncOrgPendingMapper extends BaseMapper<SyncOrgPending> {

    /**
     * 登记本轮仍未归属的设备。
     * <p>按 {@code (platform_id, device_code)} upsert：同一设备跨轮仍缺组织时只刷新
     * {@code parent_code} 与 {@code update_time}，不重复排队。</p>
     */
    @Insert("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                INSERT INTO sync_org_pending
                    (id, tenant_id, platform_id, device_code, parent_code, create_time, update_time)
                SELECT (EXTRACT(EPOCH FROM clock_timestamp()) * 1000000)::BIGINT
                           + ROW_NUMBER() OVER (ORDER BY s.code),
                       #{tenantId}, s.platform_id, s.code, s.parent_code, #{now}, #{now}
                FROM sync_staging s
                JOIN res_device d
                  ON d.source_type = 'INBOUND'
                 AND d.source_platform_id = s.platform_id
                 AND d.code = s.code
                WHERE s.task_id = #{taskId}
                  AND s.item_type = 'DEVICE'
                  AND d.org_missing = 1
                ON CONFLICT (platform_id, device_code) DO UPDATE SET
                    parent_code = EXCLUDED.parent_code,
                    update_time = EXCLUDED.update_time
              </when>
              <otherwise>
                INSERT INTO sync_org_pending
                    (id, tenant_id, platform_id, device_code, parent_code, create_time, update_time)
                SELECT UUID_SHORT(), #{tenantId}, s.platform_id, s.code, s.parent_code, #{now}, #{now}
                FROM sync_staging s
                JOIN res_device d
                  ON d.source_type = 'INBOUND'
                 AND d.source_platform_id = s.platform_id
                 AND d.code = s.code
                WHERE s.task_id = #{taskId}
                  AND s.item_type = 'DEVICE'
                  AND d.org_missing = 1
                ON DUPLICATE KEY UPDATE
                    parent_code = VALUES(parent_code),
                    update_time = VALUES(update_time)
              </otherwise>
            </choose>
            </script>
            """)
    int capture(@Param("taskId") Long taskId,
                @Param("tenantId") Long tenantId,
                @Param("now") LocalDateTime now);

    @Delete("DELETE FROM sync_org_pending WHERE platform_id = #{platformId}")
    int deleteByPlatform(@Param("platformId") Long platformId);

    /**
     * 删除该平台已归属成功的行。
     * <p>条件写成"设备不再 org_missing"而不是"设备 org_id 变了"：重试把设备从
     * {@code [未分类]} 挂到真实组织后，标记会被清掉，队列里就不该再留它。</p>
     */
    @Delete("""
            <script>
            DELETE FROM sync_org_pending
            WHERE platform_id = #{platformId}
              AND NOT EXISTS (SELECT 1 FROM res_device d
                              WHERE d.source_type = 'INBOUND'
                                AND d.source_platform_id = sync_org_pending.platform_id
                                AND d.code = sync_org_pending.device_code
                                AND d.org_missing = 1
                                AND d.status &lt;&gt; 'REMOVED')
            </script>
            """)
    int deleteResolved(@Param("platformId") Long platformId);

    /** 到期的平台（最早一行登记时间不晚于阈值）；新一轮开始时由 trigger 直接清队列合并 */
    @Select("""
            <script>
            SELECT platform_id AS platformId, MIN(create_time) AS oldest
            FROM sync_org_pending
            GROUP BY platform_id
            HAVING MIN(create_time) &lt;= #{threshold}
            </script>
            """)
    List<PendingPlatform> selectDuePlatforms(@Param("threshold") LocalDateTime threshold);

    /** 队列里最老的一行登记时间（无队列返回 null），用于"超过上限强制跑"的告警 */
    @Select("SELECT MIN(create_time) FROM sync_org_pending WHERE platform_id = #{platformId}")
    LocalDateTime selectOldest(@Param("platformId") Long platformId);

    @Select("SELECT COUNT(*) FROM sync_org_pending WHERE platform_id = #{platformId}")
    long countByPlatform(@Param("platformId") Long platformId);
}
