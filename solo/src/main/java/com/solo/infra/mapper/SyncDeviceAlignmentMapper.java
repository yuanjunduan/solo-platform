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
 * 设备的集合 SQL（3.8.2）：消失标记 / 新增 / 差异更新。
 *
 * <p>三条语句的分工与约束：</p>
 * <ul>
 *   <li><b>新增</b>：{@code id} 直接借 {@code sync_staging.id}——staging 行 id 也是雪花、全局唯一，
 *       不需要在应用层生成一遍再回填；已存在的业务键被 {@code NOT EXISTS} 排除，所以重复同步不会换 id（C3）。</li>
 *   <li><b>差异更新</b>：{@code SET} 列集与 {@code WHERE} 差异列集都由同一个 {@code columns} 参数
 *       用 {@code foreach} 展开，这就是 C9 的"同源生成"。</li>
 *   <li><b>消失</b>：只置 {@code STALE}，真正的 {@code REMOVED} 要等宽限期或删除通知（T2.7）。</li>
 * </ul>
 *
 * <p>为什么不把 {@code parent_code} 的 4 步回填放在应用层：设备是百万级，
 * 一条 {@code INSERT ... SELECT} 带 LEFT JOIN 能一次算完；拉进内存逐条判断就退化成内存 diff（C8）。</p>
 */
@Mapper
public interface SyncDeviceAlignmentMapper {

    /**
     * 本轮暂存里没出现、本地仍是 ACTIVE 的设备 → STALE。
     *
     * <p>排除平台载体设备（{@code code = platform.code}）：它是本平台为下级平台补的取流载体，
     * 对端目录里通常不包含自己，不排除就会被每轮误判消失。</p>
     *
     * @return 受影响行数
     */
    @Update("""
            <script>
            UPDATE res_device d
            SET status = 'STALE',
                update_time = #{now}
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status = 'ACTIVE'
              AND d.code &lt;&gt; #{platformCode}
              AND NOT EXISTS (SELECT 1 FROM sync_staging s
                              WHERE s.task_id = #{taskId}
                                AND s.item_type = 'DEVICE'
                                AND s.platform_id = d.source_platform_id
                                AND s.code = d.code)
            </script>
            """)
    int markMissing(@Param("taskId") Long taskId,
                    @Param("platformId") Long platformId,
                    @Param("platformCode") String platformCode,
                    @Param("now") LocalDateTime now);

    /** {@code sync.delete.mode=real}：与 markMissing 同谓词，终态为 REMOVED（软删，保留行） */
    @Update("""
            <script>
            UPDATE res_device d
            SET status = 'REMOVED',
                removed_at = #{now},
                update_time = #{now}
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status = 'ACTIVE'
              AND d.code &lt;&gt; #{platformCode}
              AND NOT EXISTS (SELECT 1 FROM sync_staging s
                              WHERE s.task_id = #{taskId}
                                AND s.item_type = 'DEVICE'
                                AND s.platform_id = d.source_platform_id
                                AND s.code = d.code)
            </script>
            """)
    int markRemoved(@Param("taskId") Long taskId,
                    @Param("platformId") Long platformId,
                    @Param("platformCode") String platformCode,
                    @Param("now") LocalDateTime now);

    /** 重现即恢复（3.8.4 规则 3） */
    @Update("""
            <script>
            UPDATE res_device d
            SET status = 'ACTIVE',
                removed_at = NULL,
                update_time = #{now}
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status IN ('STALE', 'REMOVED')
              AND EXISTS (SELECT 1 FROM sync_staging s
                          WHERE s.task_id = #{taskId}
                            AND s.item_type = 'DEVICE'
                            AND s.platform_id = d.source_platform_id
                            AND s.code = d.code)
            </script>
            """)
    int restoreActive(@Param("taskId") Long taskId,
                      @Param("platformId") Long platformId,
                      @Param("now") LocalDateTime now);

    /** G2 的分子（与 {@link #markMissing} 同一谓词） */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM res_device d
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status = 'ACTIVE'
              AND d.code &lt;&gt; #{platformCode}
              AND NOT EXISTS (SELECT 1 FROM sync_staging s
                              WHERE s.task_id = #{taskId}
                                AND s.item_type = 'DEVICE'
                                AND s.platform_id = d.source_platform_id
                                AND s.code = d.code)
            </script>
            """)
    long countWouldStale(@Param("taskId") Long taskId,
                         @Param("platformId") Long platformId,
                         @Param("platformCode") String platformCode);

    /**
     * 新增设备，同时完成 4 步归属回填（C13）：
     * <ol>
     *   <li①></li①> {@code parent_code} 命中本轮已落库的组织</li>
     *   <li②></li②> 命中人工绑定 {@code device_org_binding}</li>
     *   <li③></li③> {@code parent_code} 等于平台编码 / 本平台域 → 平台根组织</li>
     *   <li④></li④> 兜底 {@code [未分类]}（{@code unclassified = 1}）</li>
     * </ol>
     * 四步都落空时 {@code org_id} 为 NULL 会撞 NOT NULL——这是刻意的 fail fast：
     * 宁可整轮失败，也不要把孤儿设备写进库（{@code [未分类]} 由服务层在 start 阶段保证存在）。
     *
     * @param columns 可变列清单（与差异更新共用同一份，C9）
     */
    @Insert("""
            <script>
            INSERT INTO res_device
                (id, tenant_id, code, name, org_id, org_path, device_ip, vendor, model,
                 channel_count, source_type, source_platform_id, origin_platform_id, origin_code,
                 origin_chain, hop_count, status, org_missing, create_time, update_time)
            SELECT s.id, #{tenantId}, s.code, s.name,
                   COALESCE(o1.id, bo.id, o3.id, un.id),
                   COALESCE(o1.path, bo.path, o3.path, un.path),
                   s.device_ip, s.vendor, s.model, 0,
                   'INBOUND', s.platform_id, s.origin_platform_id, s.origin_code,
                   s.origin_chain, s.hop_count,
                   'ACTIVE',
                   CASE WHEN COALESCE(o1.id, bo.id, o3.id) IS NULL THEN 1 ELSE 0 END,
                   #{now}, #{now}
            FROM sync_staging s
            LEFT JOIN platform p
                   ON p.id = s.platform_id
            LEFT JOIN res_org o1
                   ON o1.source_type = 'INBOUND'
                  AND o1.source_platform_id = s.platform_id
                  AND o1.code = s.parent_code
                  AND o1.status &lt;&gt; 'REMOVED'
            LEFT JOIN device_org_binding b
                   ON b.source_platform_id = s.platform_id
                  AND b.device_code = s.code
            LEFT JOIN res_org bo
                   ON bo.id = b.org_id
            LEFT JOIN res_org o3
                   ON o3.source_type = 'INBOUND'
                  AND o3.source_platform_id = s.platform_id
                  AND o3.parent_id IS NULL
                  AND o3.unclassified = 0
                  AND (o3.code = p.code OR o3.code = p.local_domain)
            LEFT JOIN res_org un
                   ON un.source_type = 'INBOUND'
                  AND un.source_platform_id = s.platform_id
                  AND un.unclassified = 1
            WHERE s.task_id = #{taskId}
              AND s.item_type = 'DEVICE'
              AND NOT EXISTS (SELECT 1 FROM res_device d
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
     * 差异更新：只写真正变了的行。
     *
     * <p>验收口径：同一批数据连跑两次，第二次影响行数为 0（C9）。</p>
     *
     * <p>PG 用 {@code UPDATE ... FROM} + {@code IS DISTINCT FROM}；
     * MySQL 用 {@code UPDATE ... JOIN} + {@code (a &lt;=&gt; b) = 0}（NULL 安全比较）。</p>
     */
    @Update("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                UPDATE res_device d
                SET
                <foreach collection="columns" item="c" separator=",">
                    d.${c.resColumn} = s.${c.stagingColumn}
                </foreach>
                , d.update_time = #{now}
                FROM sync_staging s
                WHERE s.task_id = #{taskId}
                  AND s.item_type = 'DEVICE'
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
                UPDATE res_device d
                JOIN sync_staging s
                  ON s.task_id = #{taskId}
                 AND s.item_type = 'DEVICE'
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

    /**
     * 4 步回填后再算一次"本轮命中的组织"，把老设备的归属纠正过来。
     * <p>新增时算过一遍；但已有设备可能因为组织新到、绑定新增而需要改挂，
     * 这类改动不进 {@link #updateDiff}（它只管目录字段），单独一条。</p>
     *
     * <p><b>{@code org_missing} 必须一并重算，不能写死 0</b>：兜底组织 {@code [未分类]}
     * 只要存在，{@code COALESCE(...)} 就永远非空。若这里硬写 0，第④步刚落下的标记
     * 立刻被抹掉，{@code countOrgMissing} 恒为 0，延迟重试永远不会触发。</p>
     *
     * @return 归属或标记发生变化的行数
     */
    @Update("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                UPDATE res_device d
                SET org_id = t.org_id,
                    org_path = t.org_path,
                    org_missing = t.org_missing,
                    update_time = #{now}
                FROM (
                    SELECT s.code,
                           s.platform_id,
                           COALESCE(o1.id, bo.id, o3.id, un.id) AS org_id,
                           COALESCE(o1.path, bo.path, o3.path, un.path) AS org_path,
                           CASE WHEN o1.id IS NULL AND bo.id IS NULL AND o3.id IS NULL
                                THEN 1 ELSE 0 END AS org_missing
                    FROM sync_staging s
                    LEFT JOIN platform p ON p.id = s.platform_id
                    LEFT JOIN res_org o1 ON o1.source_type = 'INBOUND'
                         AND o1.source_platform_id = s.platform_id
                         AND o1.code = s.parent_code AND o1.status &lt;&gt; 'REMOVED'
                    LEFT JOIN device_org_binding b ON b.source_platform_id = s.platform_id
                         AND b.device_code = s.code
                    LEFT JOIN res_org bo ON bo.id = b.org_id
                    LEFT JOIN res_org o3 ON o3.source_type = 'INBOUND'
                         AND o3.source_platform_id = s.platform_id
                         AND o3.parent_id IS NULL AND o3.unclassified = 0
                         AND (o3.code = p.code OR o3.code = p.local_domain)
                    LEFT JOIN res_org un ON un.source_type = 'INBOUND'
                         AND un.source_platform_id = s.platform_id AND un.unclassified = 1
                    WHERE s.task_id = #{taskId} AND s.item_type = 'DEVICE'
                ) t
                WHERE d.source_type = 'INBOUND'
                  AND d.source_platform_id = t.platform_id
                  AND d.code = t.code
                  AND t.org_id IS NOT NULL
                  AND (d.org_id IS DISTINCT FROM t.org_id
                       OR d.org_path IS DISTINCT FROM t.org_path
                       OR d.org_missing IS DISTINCT FROM t.org_missing)
              </when>
              <otherwise>
                UPDATE res_device d
                JOIN (
                    SELECT s.code,
                           s.platform_id,
                           COALESCE(o1.id, bo.id, o3.id, un.id) AS org_id,
                           COALESCE(o1.path, bo.path, o3.path, un.path) AS org_path,
                           CASE WHEN o1.id IS NULL AND bo.id IS NULL AND o3.id IS NULL
                                THEN 1 ELSE 0 END AS org_missing
                    FROM sync_staging s
                    LEFT JOIN platform p ON p.id = s.platform_id
                    LEFT JOIN res_org o1 ON o1.source_type = 'INBOUND'
                         AND o1.source_platform_id = s.platform_id
                         AND o1.code = s.parent_code AND o1.status &lt;&gt; 'REMOVED'
                    LEFT JOIN device_org_binding b ON b.source_platform_id = s.platform_id
                         AND b.device_code = s.code
                    LEFT JOIN res_org bo ON bo.id = b.org_id
                    LEFT JOIN res_org o3 ON o3.source_type = 'INBOUND'
                         AND o3.source_platform_id = s.platform_id
                         AND o3.parent_id IS NULL AND o3.unclassified = 0
                         AND (o3.code = p.code OR o3.code = p.local_domain)
                    LEFT JOIN res_org un ON un.source_type = 'INBOUND'
                         AND un.source_platform_id = s.platform_id AND un.unclassified = 1
                    WHERE s.task_id = #{taskId} AND s.item_type = 'DEVICE'
                ) t ON t.platform_id = d.source_platform_id AND t.code = d.code
                SET d.org_id = t.org_id,
                    d.org_path = t.org_path,
                    d.org_missing = t.org_missing,
                    d.update_time = #{now}
                WHERE d.source_type = 'INBOUND'
                  AND t.org_id IS NOT NULL
                  AND ((d.org_id &lt;=&gt; t.org_id) = 0
                       OR (d.org_path &lt;=&gt; t.org_path) = 0
                       OR (d.org_missing &lt;=&gt; t.org_missing) = 0)
              </otherwise>
            </choose>
            </script>
            """)
    int refreshOrg(@Param("taskId") Long taskId,
                   @Param("now") LocalDateTime now);

    /**
     * 延迟重试用：按 {@code sync_org_pending} 里登记的 {@code parent_code} 重走①~④。
     *
     * <p>为什么不能直接复用 {@link #refreshOrg}：收尾一清 staging，{@code parent_code}
     * 就没了，而那正是④判定的唯一输入。所以先把"谁没归属 + 它的父码"抄进队列，
     * 重试时拿队列当输入。</p>
     *
     * @return 归属或标记被修正的行数
     */
    @Update("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                UPDATE res_device d
                SET org_id = t.org_id,
                    org_path = t.org_path,
                    org_missing = t.org_missing,
                    update_time = #{now}
                FROM (
                    SELECT pd.device_code,
                           COALESCE(o1.id, bo.id, o3.id, un.id) AS org_id,
                           COALESCE(o1.path, bo.path, o3.path, un.path) AS org_path,
                           CASE WHEN o1.id IS NULL AND bo.id IS NULL AND o3.id IS NULL
                                THEN 1 ELSE 0 END AS org_missing
                    FROM sync_org_pending pd
                    LEFT JOIN platform p ON p.id = pd.platform_id
                    LEFT JOIN res_org o1 ON o1.source_type = 'INBOUND'
                         AND o1.source_platform_id = pd.platform_id
                         AND o1.code = pd.parent_code AND o1.status &lt;&gt; 'REMOVED'
                    LEFT JOIN device_org_binding b ON b.source_platform_id = pd.platform_id
                         AND b.device_code = pd.device_code
                    LEFT JOIN res_org bo ON bo.id = b.org_id
                    LEFT JOIN res_org o3 ON o3.source_type = 'INBOUND'
                         AND o3.source_platform_id = pd.platform_id
                         AND o3.parent_id IS NULL AND o3.unclassified = 0
                         AND (o3.code = p.code OR o3.code = p.local_domain)
                    LEFT JOIN res_org un ON un.source_type = 'INBOUND'
                         AND un.source_platform_id = pd.platform_id AND un.unclassified = 1
                    WHERE pd.platform_id = #{platformId}
                ) t
                WHERE d.source_type = 'INBOUND'
                  AND d.source_platform_id = #{platformId}
                  AND d.code = t.device_code
                  AND t.org_id IS NOT NULL
                  AND (d.org_id IS DISTINCT FROM t.org_id
                       OR d.org_path IS DISTINCT FROM t.org_path
                       OR d.org_missing IS DISTINCT FROM t.org_missing)
              </when>
              <otherwise>
                UPDATE res_device d
                JOIN (
                    SELECT pd.device_code,
                           COALESCE(o1.id, bo.id, o3.id, un.id) AS org_id,
                           COALESCE(o1.path, bo.path, o3.path, un.path) AS org_path,
                           CASE WHEN o1.id IS NULL AND bo.id IS NULL AND o3.id IS NULL
                                THEN 1 ELSE 0 END AS org_missing
                    FROM sync_org_pending pd
                    LEFT JOIN platform p ON p.id = pd.platform_id
                    LEFT JOIN res_org o1 ON o1.source_type = 'INBOUND'
                         AND o1.source_platform_id = pd.platform_id
                         AND o1.code = pd.parent_code AND o1.status &lt;&gt; 'REMOVED'
                    LEFT JOIN device_org_binding b ON b.source_platform_id = pd.platform_id
                         AND b.device_code = pd.device_code
                    LEFT JOIN res_org bo ON bo.id = b.org_id
                    LEFT JOIN res_org o3 ON o3.source_type = 'INBOUND'
                         AND o3.source_platform_id = pd.platform_id
                         AND o3.parent_id IS NULL AND o3.unclassified = 0
                         AND (o3.code = p.code OR o3.code = p.local_domain)
                    LEFT JOIN res_org un ON un.source_type = 'INBOUND'
                         AND un.source_platform_id = pd.platform_id AND un.unclassified = 1
                    WHERE pd.platform_id = #{platformId}
                ) t ON t.device_code = d.code
                SET d.org_id = t.org_id,
                    d.org_path = t.org_path,
                    d.org_missing = t.org_missing,
                    d.update_time = #{now}
                WHERE d.source_type = 'INBOUND'
                  AND d.source_platform_id = #{platformId}
                  AND t.org_id IS NOT NULL
                  AND ((d.org_id &lt;=&gt; t.org_id) = 0
                       OR (d.org_path &lt;=&gt; t.org_path) = 0
                       OR (d.org_missing &lt;=&gt; t.org_missing) = 0)
              </otherwise>
            </choose>
            </script>
            """)
    int refreshOrgFromPending(@Param("platformId") Long platformId,
                              @Param("now") LocalDateTime now);

    /** 本轮落到 [未分类] 的设备数（要进摘要并在界面高亮，C13 第 4 步） */
    @Select("""
            <script>
            SELECT COUNT(*) FROM res_device
            WHERE source_type = 'INBOUND'
              AND source_platform_id = #{platformId}
              AND org_missing = 1
              AND status &lt;&gt; 'REMOVED'
            </script>
            """)
    long countOrgMissing(@Param("platformId") Long platformId);

    /** 每个设备的通道数（目录全量结束后一次算完，不在同步链路逐条累加） */
    @Update("""
            <script>
            UPDATE res_device d
            SET channel_count = (SELECT COUNT(*) FROM res_channel c
                                 WHERE c.device_id = d.id AND c.status &lt;&gt; 'REMOVED'),
                update_time = #{now}
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
            </script>
            """)
    int refreshChannelCount(@Param("platformId") Long platformId,
                            @Param("now") LocalDateTime now);
}
