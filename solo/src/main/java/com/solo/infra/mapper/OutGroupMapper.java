package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.share.model.OutGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 上级虚拟组织树持久化。
 *
 * <p>换父与整树重建都用<b>前缀重写</b>而不是逐条递归（与 {@code ResOrgMapper} 同口径）：
 * 一个上级下 1000 个节点、5 层深时递归有上千次往返，中途失败还会留下半截路径。</p>
 */
@Mapper
public interface OutGroupMapper extends BaseMapper<OutGroup> {

    /** 整棵树，按路径排序 = 父在前；组装目录报文与前端树共用 */
    @Select("""
            SELECT * FROM out_group
            WHERE platform_id = #{platformId}
            ORDER BY path, sort_no, id
            """)
    List<OutGroup> selectByPlatform(@Param("platformId") Long platformId);

    /** 一键生成的幂等键：同一个来源组织在该上级下最多一个节点 */
    @Select("""
            SELECT * FROM out_group
            WHERE platform_id = #{platformId} AND source_org_id = #{sourceOrgId}
            LIMIT 1
            """)
    OutGroup selectBySourceOrg(@Param("platformId") Long platformId,
                               @Param("sourceOrgId") Long sourceOrgId);

    @Select("""
            SELECT * FROM out_group
            WHERE platform_id = #{platformId} AND code = #{code}
            LIMIT 1
            """)
    OutGroup selectByCode(@Param("platformId") Long platformId, @Param("code") String code);

    /** 子树（含自身），按路径升序 */
    @Select("""
            SELECT * FROM out_group
            WHERE platform_id = #{platformId} AND path LIKE CONCAT(#{prefix}, '%')
            ORDER BY path
            """)
    List<OutGroup> selectSubtree(@Param("platformId") Long platformId,
                                 @Param("prefix") String prefix);

    /**
     * 换父后重写子树路径。
     * <p>{@code REPLACE + CONCAT} 在 MySQL / PostgreSQL 上语义一致，不需要 databaseId 分支。</p>
     */
    @Update("""
            UPDATE out_group
            SET path = REPLACE(path, #{oldPrefix}, #{newPrefix}),
                update_time = #{now}
            WHERE platform_id = #{platformId}
              AND path LIKE CONCAT(#{oldPrefix}, '%')
            """)
    int rewriteSubtreePath(@Param("platformId") Long platformId,
                           @Param("oldPrefix") String oldPrefix,
                           @Param("newPrefix") String newPrefix,
                           @Param("now") LocalDateTime now);

    /** 层级整体平移（可正可负），必须在上一步换完 path 之后调用 */
    @Update("""
            UPDATE out_group
            SET level = level + #{delta},
                update_time = #{now}
            WHERE platform_id = #{platformId}
              AND path LIKE CONCAT(#{newPrefix}, '%')
            """)
    int shiftSubtreeLevel(@Param("platformId") Long platformId,
                          @Param("newPrefix") String newPrefix,
                          @Param("delta") int delta,
                          @Param("now") LocalDateTime now);

    /** 父节点变了但子节点还指着旧 parent：逐条改会 N 次往返，这里按前缀批量刷 parent_code */
    @Update("""
            UPDATE out_group
            SET parent_code = #{newParentCode},
                update_time = #{now}
            WHERE platform_id = #{platformId} AND parent_id = #{parentId}
            """)
    int rebindChildrenParentCode(@Param("platformId") Long platformId,
                                 @Param("parentId") Long parentId,
                                 @Param("newParentCode") String newParentCode,
                                 @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*) FROM out_group WHERE platform_id = #{platformId}
            """)
    long countByPlatform(@Param("platformId") Long platformId);

    /** 撤销整平台共享时清理该上级的树（主表关系清空后这棵树没有意义） */
    @Update("""
            DELETE FROM out_group WHERE platform_id = #{platformId}
            """)
    int deleteByPlatform(@Param("platformId") Long platformId);

    /**
     * 镜像来源组织已被删除的虚拟分组（消失联动的收尾）。
     * <p>按 {@code res_org.source_platform_id} 圈定：一次同步只清理它自己推来的组织，
     * 不会误伤本地自建的组织镜像。深在前，便于上级按叶子 → 根的顺序删除。</p>
     */
    @Select("""
            SELECT g.* FROM out_group g
            JOIN res_org o ON o.id = g.source_org_id
            WHERE o.status = 'REMOVED' AND o.source_platform_id = #{sourcePlatformId}
            ORDER BY g.platform_id, g.level DESC, g.code
            """)
    List<OutGroup> selectByDeadSourceOrg(@Param("sourcePlatformId") Long sourcePlatformId);
}
