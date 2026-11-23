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
 * 目录分组（GB28181 215 业务分组 / 216 虚拟组织）的集合 SQL。
 *
 * <p>与组织同构，唯一差别是<b>没有 {@code parent_code} 列</b>：分组的父子关系只存在于本轮暂存里，
 * 所以 {@code refreshParent} 必须带上 {@code task_id}。这也意味着分组树的调整是"本轮内收敛"，
 * 跨轮的结构变化要靠下一轮的暂存重新算——分组数量小，这个代价可以接受。</p>
 */
@Mapper
public interface SyncGroupAlignmentMapper {

    @Update("""
            UPDATE res_group d
            SET status = 'STALE',
                update_time = #{now}
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM sync_staging s
                              WHERE s.task_id = #{taskId}
                                AND s.item_type = 'GROUP'
                                AND s.platform_id = d.source_platform_id
                                AND s.code = d.code)
            """)
    int markMissing(@Param("taskId") Long taskId,
                    @Param("platformId") Long platformId,
                    @Param("now") LocalDateTime now);

    /** {@code sync.delete.mode=real}：与 markMissing 同谓词，终态为 REMOVED */
    @Update("""
            UPDATE res_group d
            SET status = 'REMOVED',
                removed_at = #{now},
                update_time = #{now}
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM sync_staging s
                              WHERE s.task_id = #{taskId}
                                AND s.item_type = 'GROUP'
                                AND s.platform_id = d.source_platform_id
                                AND s.code = d.code)
            """)
    int markRemoved(@Param("taskId") Long taskId,
                    @Param("platformId") Long platformId,
                    @Param("now") LocalDateTime now);

    /** 重现即恢复（3.8.4 规则 3） */
    @Update("""
            UPDATE res_group d
            SET status = 'ACTIVE',
                removed_at = NULL,
                update_time = #{now}
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status IN ('STALE', 'REMOVED')
              AND EXISTS (SELECT 1 FROM sync_staging s
                          WHERE s.task_id = #{taskId}
                            AND s.item_type = 'GROUP'
                            AND s.platform_id = d.source_platform_id
                            AND s.code = d.code)
            """)
    int restoreActive(@Param("taskId") Long taskId,
                      @Param("platformId") Long platformId,
                      @Param("now") LocalDateTime now);

    /** G2 的分子（与 {@link #markMissing} 同一谓词） */
    @Select("""
            SELECT COUNT(*)
            FROM res_group d
            WHERE d.source_type = 'INBOUND'
              AND d.source_platform_id = #{platformId}
              AND d.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM sync_staging s
                              WHERE s.task_id = #{taskId}
                                AND s.item_type = 'GROUP'
                                AND s.platform_id = d.source_platform_id
                                AND s.code = d.code)
            """)
    long countWouldStale(@Param("taskId") Long taskId,
                         @Param("platformId") Long platformId);

    /** 暂存里的 parent_code → res_group.parent_id（本轮内收敛，父未落库的保持 NULL 当根） */
    @Update("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                UPDATE res_group c
                SET parent_id = p.id,
                    update_time = #{now}
                FROM sync_staging s, res_group p
                WHERE s.task_id = #{taskId}
                  AND s.item_type = 'GROUP'
                  AND s.parent_code IS NOT NULL
                  AND c.source_type = 'INBOUND'
                  AND c.source_platform_id = s.platform_id
                  AND c.code = s.code
                  AND c.status &lt;&gt; 'REMOVED'
                  AND p.source_type = 'INBOUND'
                  AND p.source_platform_id = s.platform_id
                  AND p.code = s.parent_code
                  AND p.status &lt;&gt; 'REMOVED'
                  AND (c.parent_id IS NULL OR c.parent_id &lt;&gt; p.id)
              </when>
              <otherwise>
                UPDATE res_group c
                JOIN sync_staging s
                  ON s.task_id = #{taskId}
                 AND s.item_type = 'GROUP'
                 AND s.platform_id = c.source_platform_id
                 AND s.code = c.code
                JOIN res_group p
                  ON p.source_type = 'INBOUND'
                 AND p.source_platform_id = s.platform_id
                 AND p.code = s.parent_code
                 AND p.status &lt;&gt; 'REMOVED'
                SET c.parent_id = p.id,
                    c.update_time = #{now}
                WHERE c.source_type = 'INBOUND'
                  AND c.status &lt;&gt; 'REMOVED'
                  AND s.parent_code IS NOT NULL
                  AND (c.parent_id IS NULL OR c.parent_id &lt;&gt; p.id)
              </otherwise>
            </choose>
            </script>
            """)
    int refreshParent(@Param("taskId") Long taskId,
                      @Param("now") LocalDateTime now);

    /** 插入"父已就位或本来就是根"的分组；调用方逐层重复直到返回 0 */
    @Insert("""
            <script>
            INSERT INTO res_group
                (id, tenant_id, code, name, parent_id, path, level, group_type,
                 source_type, source_platform_id, origin_chain, hop_count,
                 status, sort_no, create_time, update_time)
            SELECT s.id, #{tenantId}, s.code, s.name, p.id,
                   CONCAT(COALESCE(p.path, '/'), s.id, '/'),
                   COALESCE(p.level, 0) + 1,
                   COALESCE(s.group_type, '215'),
                   'INBOUND', s.platform_id, s.origin_chain, s.hop_count,
                   'ACTIVE', 0, #{now}, #{now}
            FROM sync_staging s
            LEFT JOIN res_group p
                   ON p.source_type = 'INBOUND'
                  AND p.source_platform_id = s.platform_id
                  AND p.code = s.parent_code
                  AND p.status &lt;&gt; 'REMOVED'
            WHERE s.task_id = #{taskId}
              AND s.item_type = 'GROUP'
              AND NOT EXISTS (SELECT 1 FROM res_group d
                              WHERE d.source_type = 'INBOUND'
                                AND d.source_platform_id = s.platform_id
                                AND d.code = s.code)
              AND (s.parent_code IS NULL
                   OR NOT EXISTS (SELECT 1 FROM sync_staging sp
                                  WHERE sp.task_id = s.task_id
                                    AND sp.item_type = 'GROUP'
                                    AND sp.code = s.parent_code)
                   OR p.id IS NOT NULL)
            </script>
            """)
    int insertReady(@Param("taskId") Long taskId,
                    @Param("tenantId") Long tenantId,
                    @Param("now") LocalDateTime now);

    @Update("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                UPDATE res_group d
                SET
                <foreach collection="columns" item="c" separator=",">
                    d.${c.resColumn} = s.${c.stagingColumn}
                </foreach>
                , d.update_time = #{now}
                FROM sync_staging s
                WHERE s.task_id = #{taskId}
                  AND s.item_type = 'GROUP'
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
                UPDATE res_group d
                JOIN sync_staging s
                  ON s.task_id = #{taskId}
                 AND s.item_type = 'GROUP'
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

    @Update("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                UPDATE res_group c
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
                UPDATE res_group c
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

    @Update("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                UPDATE res_group c
                SET path = CONCAT(p.path, c.id, '/'),
                    level = p.level + 1,
                    update_time = #{now}
                FROM res_group p
                WHERE c.parent_id = p.id
                  AND c.source_type = 'INBOUND'
                  AND c.source_platform_id = #{platformId}
                  AND c.status &lt;&gt; 'REMOVED'
                  AND (c.path &lt;&gt; CONCAT(p.path, c.id, '/') OR c.level &lt;&gt; p.level + 1)
              </when>
              <otherwise>
                UPDATE res_group c
                JOIN res_group p ON p.id = c.parent_id
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
