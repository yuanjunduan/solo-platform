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
 * 组织的集合 SQL（3.8.2）：消失标记 / 建树 / 差异更新 / 树修复。
 *
 * <p>组织比设备多一层麻烦：{@code path} 与 {@code level} 是从父节点推出来的物化字段，
 * 一条 INSERT 算不出整棵树。这里拆成三步，由服务层循环推进：</p>
 * <ol>
 *   <li>{@link #refreshParent}：把 {@code parent_code} 解析成 {@code parent_id}（父还没到的先留 NULL）</li>
 *   <li>{@link #insertReady}：只插"父已就位或本来就是根"的行，重复调用直到插入 0 行</li>
 *   <li>{@link #repairRoots} + {@link #repairChildren}：按层把 {@code path}/{@code level} 推到收敛</li>
 * </ol>
 * <p>为什么不在 SQL 里用递归 CTE 一把写完：MySQL 的 {@code INSERT ... WITH RECURSIVE} 与 PG 写法不同，
 * 而且层级只有个位数，按层循环的可读性与可测性都更好。</p>
 */
@Mapper
public interface SyncOrgAlignmentMapper {

    /**
     * 本轮暂存里没出现、本地仍 ACTIVE 的组织 → STALE。
     *
     * <p>两类行必须排除，否则每轮都会被误判消失：{@code [未分类]}（本轮没有它，但它是兜底）
     * 与平台根组织（{@code code = platform.code}，对端目录里通常不包含自己）。</p>
     */
    @Update("""
            <script>
            UPDATE res_org d
            SET status = 'STALE',
                update_time = #{now}
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status = 'ACTIVE'
              AND d.unclassified = 0
              AND d.code &lt;&gt; #{platformCode}
              AND NOT EXISTS (SELECT 1 FROM sync_staging s
                              WHERE s.task_id = #{taskId}
                                AND s.item_type = 'ORG'
                                AND s.platform_id = d.source_platform_id
                                AND s.code = d.code)
            </script>
            """)
    int markMissing(@Param("taskId") Long taskId,
                    @Param("platformId") Long platformId,
                    @Param("platformCode") String platformCode,
                    @Param("now") LocalDateTime now);

    /**
     * {@code sync.delete.mode=real} 的消失动作：置 REMOVED + removed_at（软删，保留行）。
     *
     * <p>谓词与 {@link #markMissing} 完全一致（只动本轮没出现的 ACTIVE 行），
     * 区别只在 SET 的终态。{@code G2} 分子共用 {@link #countWouldStale}，所以两道闸门的
     * 保护对 mark / real 两种模式都生效。</p>
     */
    @Update("""
            <script>
            UPDATE res_org d
            SET status = 'REMOVED',
                removed_at = #{now},
                update_time = #{now}
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status = 'ACTIVE'
              AND d.unclassified = 0
              AND d.code &lt;&gt; #{platformCode}
              AND NOT EXISTS (SELECT 1 FROM sync_staging s
                              WHERE s.task_id = #{taskId}
                                AND s.item_type = 'ORG'
                                AND s.platform_id = d.source_platform_id
                                AND s.code = d.code)
            </script>
            """)
    int markRemoved(@Param("taskId") Long taskId,
                    @Param("platformId") Long platformId,
                    @Param("platformCode") String platformCode,
                    @Param("now") LocalDateTime now);

    /**
     * 重现即恢复（3.8.4 规则 3）：上一轮标 STALE/REMOVED、本轮目录里又出现的行回 ACTIVE。
     * <p>不需要人工干预，因为编码没变；恢复动作必须发生在新增/差异更新之前，
     * 否则 REMOVED 行会被 {@code NOT EXISTS} 当成"已存在"而永远不回到 ACTIVE。</p>
     */
    @Update("""
            <script>
            UPDATE res_org d
            SET status = 'ACTIVE',
                removed_at = NULL,
                update_time = #{now}
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status IN ('STALE', 'REMOVED')
              AND EXISTS (SELECT 1 FROM sync_staging s
                          WHERE s.task_id = #{taskId}
                            AND s.item_type = 'ORG'
                            AND s.platform_id = d.source_platform_id
                            AND s.code = d.code)
            </script>
            """)
    int restoreActive(@Param("taskId") Long taskId,
                      @Param("platformId") Long platformId,
                      @Param("now") LocalDateTime now);

    /** G2 的分子：这一轮"预计会消失"的组织数（与 {@link #markMissing} 完全同一谓词） */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM res_org d
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status = 'ACTIVE'
              AND d.unclassified = 0
              AND d.code &lt;&gt; #{platformCode}
              AND NOT EXISTS (SELECT 1 FROM sync_staging s
                              WHERE s.task_id = #{taskId}
                                AND s.item_type = 'ORG'
                                AND s.platform_id = d.source_platform_id
                                AND s.code = d.code)
            </script>
            """)
    long countWouldStale(@Param("taskId") Long taskId,
                         @Param("platformId") Long platformId,
                         @Param("platformCode") String platformCode);

    /**
     * 把 {@code parent_code} 解析回 {@code parent_id}。
     * <p>数据源是 res_org 自己（不是 staging）：这样跨轮也能自愈——上一轮父没到、这一轮到了，
     * 下一轮同步会自动挂上去，不需要人工干预。</p>
     */
    @Update("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                UPDATE res_org c
                SET parent_id = p.id,
                    update_time = #{now}
                FROM res_org p
                WHERE c.source_type = 'INBOUND'
                  AND c.source_platform_id = #{platformId}
                  AND c.parent_code IS NOT NULL
                  AND c.status &lt;&gt; 'REMOVED'
                  AND p.source_type = 'INBOUND'
                  AND p.source_platform_id = c.source_platform_id
                  AND p.code = c.parent_code
                  AND p.status &lt;&gt; 'REMOVED'
                  AND (c.parent_id IS NULL OR c.parent_id &lt;&gt; p.id)
              </when>
              <otherwise>
                UPDATE res_org c
                JOIN res_org p
                  ON p.source_type = 'INBOUND'
                 AND p.source_platform_id = c.source_platform_id
                 AND p.code = c.parent_code
                 AND p.status &lt;&gt; 'REMOVED'
                SET c.parent_id = p.id,
                    c.update_time = #{now}
                WHERE c.source_type = 'INBOUND'
                  AND c.source_platform_id = #{platformId}
                  AND c.parent_code IS NOT NULL
                  AND c.status &lt;&gt; 'REMOVED'
                  AND (c.parent_id IS NULL OR c.parent_id &lt;&gt; p.id)
              </otherwise>
            </choose>
            </script>
            """)
    int refreshParent(@Param("platformId") Long platformId,
                      @Param("now") LocalDateTime now);

    /**
     * 插入"可以确定位置"的组织：父编码不在本轮目录里（视为根），或父已落库。
     * <p>{@code path} 与 {@code level} 直接从父行取，所以调用方必须<b>逐层重复调用</b>直到返回 0。</p>
     *
     * @return 本次插入行数；0 表示没有更多可插的
     */
    @Insert("""
            <script>
            INSERT INTO res_org
                (id, tenant_id, code, parent_code, parent_id, path, level, name,
                 source_type, source_platform_id, origin_chain, hop_count,
                 status, org_missing, unclassified, sort_no, create_time, update_time)
            SELECT s.id, #{tenantId}, s.code, s.parent_code, p.id,
                   CONCAT(COALESCE(p.path, '/'), s.id, '/'),
                   COALESCE(p.level, 0) + 1,
                   s.name,
                   'INBOUND', s.platform_id, s.origin_chain, s.hop_count,
                   'ACTIVE', 0, 0, 0, #{now}, #{now}
            FROM sync_staging s
            LEFT JOIN res_org p
                   ON p.source_type = 'INBOUND'
                  AND p.source_platform_id = s.platform_id
                  AND p.code = s.parent_code
                  AND p.status &lt;&gt; 'REMOVED'
            WHERE s.task_id = #{taskId}
              AND s.item_type = 'ORG'
              AND NOT EXISTS (SELECT 1 FROM res_org d
                              WHERE d.source_type = 'INBOUND'
                                AND d.source_platform_id = s.platform_id
                                AND d.code = s.code)
              AND (s.parent_code IS NULL
                   OR NOT EXISTS (SELECT 1 FROM sync_staging sp
                                  WHERE sp.task_id = s.task_id
                                    AND sp.item_type = 'ORG'
                                    AND sp.code = s.parent_code)
                   OR p.id IS NOT NULL)
            </script>
            """)
    int insertReady(@Param("taskId") Long taskId,
                    @Param("tenantId") Long tenantId,
                    @Param("now") LocalDateTime now);

    /** 差异更新：{@code SET} 与 {@code WHERE} 共用 {@code columns}（C9） */
    @Update("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                UPDATE res_org d
                SET
                <foreach collection="columns" item="c" separator=",">
                    d.${c.resColumn} = s.${c.stagingColumn}
                </foreach>
                , d.update_time = #{now}
                FROM sync_staging s
                WHERE s.task_id = #{taskId}
                  AND s.item_type = 'ORG'
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
                UPDATE res_org d
                JOIN sync_staging s
                  ON s.task_id = #{taskId}
                 AND s.item_type = 'ORG'
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

    /** 根组织的 path/level 归一（父为 NULL 或父已被移除时回到根） */
    @Update("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                UPDATE res_org c
                SET path = CONCAT('/', c.id, '/'),
                    level = 1,
                    update_time = #{now}
                WHERE c.source_type = 'INBOUND'
                  AND c.source_platform_id = #{platformId}
                  AND c.parent_id IS NULL
                  AND c.status &lt;&gt; 'REMOVED'
                  AND (c.path &lt;&gt; CONCAT('/', c.id, '/') OR c.level &lt;&gt; 1)
              </when>
              <otherwise>
                UPDATE res_org c
                SET c.path = CONCAT('/', c.id, '/'),
                    c.level = 1,
                    c.update_time = #{now}
                WHERE c.source_type = 'INBOUND'
                  AND c.source_platform_id = #{platformId}
                  AND c.parent_id IS NULL
                  AND c.status &lt;&gt; 'REMOVED'
                  AND (c.path &lt;&gt; CONCAT('/', c.id, '/') OR c.level &lt;&gt; 1)
              </otherwise>
            </choose>
            </script>
            """)
    int repairRoots(@Param("platformId") Long platformId,
                    @Param("now") LocalDateTime now);

    /** 子组织 path/level 对齐父行；重复调用直到返回 0（层级深度即轮数） */
    @Update("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                UPDATE res_org c
                SET path = CONCAT(p.path, c.id, '/'),
                    level = p.level + 1,
                    update_time = #{now}
                FROM res_org p
                WHERE c.parent_id = p.id
                  AND c.source_type = 'INBOUND'
                  AND c.source_platform_id = #{platformId}
                  AND c.status &lt;&gt; 'REMOVED'
                  AND (c.path &lt;&gt; CONCAT(p.path, c.id, '/') OR c.level &lt;&gt; p.level + 1)
              </when>
              <otherwise>
                UPDATE res_org c
                JOIN res_org p ON p.id = c.parent_id
                SET c.path = CONCAT(p.path, c.id, '/'),
                    c.level = p.level + 1,
                    c.update_time = #{now}
                WHERE c.source_type = 'INBOUND'
                  AND c.source_platform_id = #{platformId}
                  AND c.status &lt;&gt; 'REMOVED'
                  AND (c.path &lt;&gt; CONCAT(p.path, c.id, '/') OR c.level &lt;&gt; p.level + 1)
              </otherwise>
            </choose>
            </script>
            """)
    int repairChildren(@Param("platformId") Long platformId,
                       @Param("now") LocalDateTime now);
}
