package com.solo.infra.mapper;

import com.solo.domain.sync.model.MutableColumn;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通道的集合 SQL（3.8.2）：消失标记 / 新增 / 差异更新。
 *
 * <p>与设备相比多两道约束：</p>
 * <ul>
 *   <li><b>载体必须有</b>：{@code InNER JOIN res_device} 故意写成内连接——设备缺失的通道
 *       直接不插入，由服务层统计成 {@code failed_count} 并在摘要里报出来。
 *       用 LEFT JOIN 塞个 {@code device_id = 0} 只会把问题挪到取流时才暴露。</li>
 *   <li><b>位置优先继承设备</b>：目录里通道的 {@code parent_code} 通常是设备码，
 *       所以第 ① 步按 {@code parent_code} 找组织，找不到就落设备的 {@code org_id}，
 *       再不行才是 {@code [未分类]}。</li>
 * </ul>
 */
@Mapper
public interface SyncChannelAlignmentMapper {

    /** 本轮暂存里没出现、本地仍 ACTIVE 的通道 → STALE */
    @Update("""
            UPDATE res_channel d
            SET status = 'STALE',
                update_time = #{now}
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM sync_staging s
                              WHERE s.task_id = #{taskId}
                                AND s.item_type = 'CHANNEL'
                                AND s.platform_id = d.source_platform_id
                                AND s.code = d.code)
            """)
    int markMissing(@Param("taskId") Long taskId,
                    @Param("platformId") Long platformId,
                    @Param("now") LocalDateTime now);

    /** {@code sync.delete.mode=real}：与 markMissing 同谓词，终态为 REMOVED */
    @Update("""
            UPDATE res_channel d
            SET status = 'REMOVED',
                removed_at = #{now},
                update_time = #{now}
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM sync_staging s
                              WHERE s.task_id = #{taskId}
                                AND s.item_type = 'CHANNEL'
                                AND s.platform_id = d.source_platform_id
                                AND s.code = d.code)
            """)
    int markRemoved(@Param("taskId") Long taskId,
                    @Param("platformId") Long platformId,
                    @Param("now") LocalDateTime now);

    /** 重现即恢复（3.8.4 规则 3） */
    @Update("""
            UPDATE res_channel d
            SET status = 'ACTIVE',
                removed_at = NULL,
                update_time = #{now}
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status IN ('STALE', 'REMOVED')
              AND EXISTS (SELECT 1 FROM sync_staging s
                          WHERE s.task_id = #{taskId}
                            AND s.item_type = 'CHANNEL'
                            AND s.platform_id = d.source_platform_id
                            AND s.code = d.code)
            """)
    int restoreActive(@Param("taskId") Long taskId,
                      @Param("platformId") Long platformId,
                      @Param("now") LocalDateTime now);

    /** G2 的分子（与 {@link #markMissing} 同一谓词） */
    @Select("""
            SELECT COUNT(*)
            FROM res_channel d
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM sync_staging s
                              WHERE s.task_id = #{taskId}
                                AND s.item_type = 'CHANNEL'
                                AND s.platform_id = d.source_platform_id
                                AND s.code = d.code)
            """)
    long countWouldStale(@Param("taskId") Long taskId,
                         @Param("platformId") Long platformId);

    /**
     * 新增通道。{@code id} 借 {@code sync_staging.id}（雪花且全局唯一），
     * 已存在的业务键被 {@code NOT EXISTS} 排除，重复同步不换 id（C3）。
     */
    @Insert("""
            <script>
            INSERT INTO res_channel
                (id, tenant_id, code, name, org_id, org_path, device_id, group_id,
                 stream_type, online_status, capability, media_hint,
                 source_type, source_platform_id, origin_platform_id, origin_code,
                 origin_chain, hop_count, canonical_id, status, org_missing,
                 create_time, update_time)
            SELECT s.id, #{tenantId}, s.code, s.name,
                   COALESCE(o1.id, dv.org_id, un.id),
                   COALESCE(o1.path, dv.org_path, un.path),
                   dv.id, NULL,
                   COALESCE(s.stream_type, 'MAIN'),
                   COALESCE(s.online_status, 'OFF'),
                   COALESCE(s.capability, ''),
                   s.media_hint,
                   'INBOUND', s.platform_id, s.origin_platform_id, s.origin_code,
                   s.origin_chain, s.hop_count, 0, 'ACTIVE',
                   CASE WHEN COALESCE(o1.id, un.id) IS NULL THEN 1 ELSE 0 END,
                   #{now}, #{now}
            FROM sync_staging s
            JOIN res_device dv
                  ON dv.source_type = 'INBOUND'
                 AND dv.source_platform_id = s.platform_id
                 AND dv.code = COALESCE(s.device_code, s.parent_code)
            LEFT JOIN res_org o1
                  ON o1.source_type = 'INBOUND'
                 AND o1.source_platform_id = s.platform_id
                 AND o1.code = s.parent_code
                 AND o1.status &lt;&gt; 'REMOVED'
            LEFT JOIN res_org un
                  ON un.source_type = 'INBOUND'
                 AND un.source_platform_id = s.platform_id
                 AND un.unclassified = 1
            WHERE s.task_id = #{taskId}
              AND s.item_type = 'CHANNEL'
              AND NOT EXISTS (SELECT 1 FROM res_channel d
                              WHERE d.source_type = 'INBOUND'
                                AND d.source_platform_id = s.platform_id
                                AND d.code = s.code)
            </script>
            """)
    int insertNew(@Param("taskId") Long taskId,
                  @Param("tenantId") Long tenantId,
                  @Param("now") LocalDateTime now,
                  @Param("columns") List<MutableColumn> columns);

    /**
     * 差异更新：{@code SET} 与 {@code WHERE} 共用 {@code columns}（C9）。
     * <p>验收口径：同一批数据连跑两次，第二次影响行数为 0。</p>
     */
    @Update("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                UPDATE res_channel d
                SET
                <foreach collection="columns" item="c" separator=",">
                    d.${c.resColumn} = s.${c.stagingColumn}
                </foreach>
                , d.update_time = #{now}
                FROM sync_staging s
                WHERE s.task_id = #{taskId}
                  AND s.item_type = 'CHANNEL'
                  AND d.source_type = 'INBOUND'
                  AND d.source_platform_id = s.platform_id
                  AND d.code = s.code
                  AND (
                  <foreach collection="columns" item="c" separator=" OR ">
                      d.${c.resColumn} IS DISTINCT FROM s.${c.stagingColumn}
                  </foreach>
                  )
              </when>
              <otherwise>
                UPDATE res_channel d
                JOIN sync_staging s
                  ON s.task_id = #{taskId}
                 AND s.item_type = 'CHANNEL'
                 AND s.platform_id = d.source_platform_id
                 AND s.code = d.code
                SET
                <foreach collection="columns" item="c" separator=",">
                    d.${c.resColumn} = s.${c.stagingColumn}
                </foreach>
                , d.update_time = #{now}
                WHERE d.source_type = 'INBOUND'
                  AND (
                  <foreach collection="columns" item="c" separator=" OR ">
                      (d.${c.resColumn} &lt;=&gt; s.${c.stagingColumn}) = 0
                  </foreach>
                  )
              </otherwise>
            </choose>
            </script>
            """)
    int updateDiff(@Param("taskId") Long taskId,
                   @Param("now") LocalDateTime now,
                   @Param("columns") List<MutableColumn> columns);

    /** 归属纠正（组织晚到 / 人工绑定新增后，已存在的通道要改挂） */
    @Update("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                UPDATE res_channel d
                SET org_id = t.org_id,
                    org_path = t.org_path,
                    org_missing = 0,
                    update_time = #{now}
                FROM (
                    SELECT s.code,
                           s.platform_id,
                           COALESCE(o1.id, dv.org_id, un.id) AS org_id,
                           COALESCE(o1.path, dv.org_path, un.path) AS org_path
                    FROM sync_staging s
                    JOIN res_device dv ON dv.source_type = 'INBOUND'
                         AND dv.source_platform_id = s.platform_id
                         AND dv.code = COALESCE(s.device_code, s.parent_code)
                    LEFT JOIN res_org o1 ON o1.source_type = 'INBOUND'
                         AND o1.source_platform_id = s.platform_id
                         AND o1.code = s.parent_code AND o1.status &lt;&gt; 'REMOVED'
                    LEFT JOIN res_org un ON un.source_type = 'INBOUND'
                         AND un.source_platform_id = s.platform_id AND un.unclassified = 1
                    WHERE s.task_id = #{taskId} AND s.item_type = 'CHANNEL'
                ) t
                WHERE d.source_type = 'INBOUND'
                  AND d.source_platform_id = t.platform_id
                  AND d.code = t.code
                  AND t.org_id IS NOT NULL
                  AND (d.org_id IS DISTINCT FROM t.org_id OR d.org_missing = 1)
              </when>
              <otherwise>
                UPDATE res_channel d
                JOIN (
                    SELECT s.code,
                           s.platform_id,
                           COALESCE(o1.id, dv.org_id, un.id) AS org_id,
                           COALESCE(o1.path, dv.org_path, un.path) AS org_path
                    FROM sync_staging s
                    JOIN res_device dv ON dv.source_type = 'INBOUND'
                         AND dv.source_platform_id = s.platform_id
                         AND dv.code = COALESCE(s.device_code, s.parent_code)
                    LEFT JOIN res_org o1 ON o1.source_type = 'INBOUND'
                         AND o1.source_platform_id = s.platform_id
                         AND o1.code = s.parent_code AND o1.status &lt;&gt; 'REMOVED'
                    LEFT JOIN res_org un ON un.source_type = 'INBOUND'
                         AND un.source_platform_id = s.platform_id AND un.unclassified = 1
                    WHERE s.task_id = #{taskId} AND s.item_type = 'CHANNEL'
                ) t ON t.platform_id = d.source_platform_id AND t.code = d.code
                SET d.org_id = t.org_id,
                    d.org_path = t.org_path,
                    d.org_missing = 0,
                    d.update_time = #{now}
                WHERE d.source_type = 'INBOUND'
                  AND t.org_id IS NOT NULL
                  AND (d.org_id &lt;&gt; t.org_id OR d.org_missing = 1)
              </otherwise>
            </choose>
            </script>
            """)
    int refreshOrg(@Param("taskId") Long taskId,
                   @Param("now") LocalDateTime now);

    /**
     * 设备缺失导致本轮没能插入的通道数（进 {@code failed_count}，不静默丢）。
     * <p>口径：暂存里有该通道，但本地既没有它、也找不到它的载体设备。</p>
     */
    @Select("""
            SELECT COUNT(*)
            FROM sync_staging s
            WHERE s.task_id = #{taskId}
              AND s.item_type = 'CHANNEL'
              AND NOT EXISTS (SELECT 1 FROM res_device dv
                              WHERE dv.source_type = 'INBOUND'
                                AND dv.source_platform_id = s.platform_id
                                AND dv.code = COALESCE(s.device_code, s.parent_code))
            """)
    long countOrphanByMissingDevice(@Param("taskId") Long taskId);

    @Select("""
            <script>
            SELECT COUNT(*) FROM res_channel
            WHERE source_type = 'INBOUND'
              AND source_platform_id = #{platformId}
              AND org_missing = 1
              AND status &lt;&gt; 'REMOVED'
            </script>
            """)
    long countOrgMissing(@Param("platformId") Long platformId);
}
