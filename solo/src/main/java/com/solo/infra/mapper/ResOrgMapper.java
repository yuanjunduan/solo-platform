package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.resource.model.ResOrg;
import com.solo.domain.resource.model.ResourceStatus;
import com.solo.domain.resource.model.SourceType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 组织持久化。
 *
 * <p>子树相关的写操作全部用"前缀替换"一次性完成，不递归逐条 UPDATE：
 * 1000 个组织、5 层深时递归会有上千次往返，且中途失败会留下半截路径。</p>
 */
@Mapper
public interface ResOrgMapper extends BaseMapper<ResOrg> {

    /**
     * 按业务键取组织（同步写入的唯一入口，见 3.6.6）。
     * <p>用 {@code selectOne} 而非 {@code selectList}：唯一键保证最多一条，取列表等于把
     * "唯一键被破坏"这种事故藏起来。</p>
     */
    default ResOrg selectByBizKey(String sourceType, Long sourcePlatformId, String code) {
        return selectOne(new LambdaQueryWrapper<ResOrg>()
                .eq(ResOrg::getSourceType, sourceType)
                .eq(ResOrg::getSourcePlatformId, sourcePlatformId)
                .eq(ResOrg::getCode, code)
                .last("limit 1"));
    }

    /**
     * 直接子组织数（删除组织前的级联校验）。
     * <p>这里不按状态过滤：STALE/REMOVED 的子行仍然占着路径前缀，删父会让它们变成孤儿。</p>
     */
    default long countByParentId(Long parentId) {
        return selectCount(new LambdaQueryWrapper<ResOrg>().eq(ResOrg::getParentId, parentId));
    }

    /** 同一父下是否已存在同名组织（本地资源重名校验） */
    default long countSiblingName(Long parentId, String name, Long excludeId) {
        LambdaQueryWrapper<ResOrg> w = new LambdaQueryWrapper<ResOrg>()
                .eq(ResOrg::getSourceType, SourceType.NATIVE)
                .eq(ResOrg::getName, name)
                .ne(ResOrg::getStatus, ResourceStatus.REMOVED);
        if (parentId == null) {
            w.isNull(ResOrg::getParentId);
        } else {
            w.eq(ResOrg::getParentId, parentId);
        }
        if (excludeId != null) {
            w.ne(ResOrg::getId, excludeId);
        }
        return selectCount(w);
    }

    /**
     * 重写子树路径：把 {@code oldPrefix} 打头的 path 一次性换成 {@code newPrefix}。
     * <p>{@code REPLACE} 与 {@code CONCAT} 在 MySQL / PostgreSQL 上语义一致，不需要 databaseId 分支。</p>
     *
     * @return 受影响行数（含自身）
     */
    @Update("""
            UPDATE res_org
            SET path = REPLACE(path, #{oldPrefix}, #{newPrefix}),
                update_time = #{now}
            WHERE source_type = #{sourceType}
              AND source_platform_id = #{sourcePlatformId}
              AND path LIKE CONCAT(#{oldPrefix}, '%')
            """)
    int rewriteSubtreePath(@Param("sourceType") String sourceType,
                           @Param("sourcePlatformId") Long sourcePlatformId,
                           @Param("oldPrefix") String oldPrefix,
                           @Param("newPrefix") String newPrefix,
                           @Param("now") LocalDateTime now);

    /**
     * 调整子树层级：换父后层级整体平移 {@code delta}（可正可负）。
     * <p>必须在上一步换完 path 之后调用——这里按新前缀圈定范围。</p>
     */
    @Update("""
            UPDATE res_org
            SET level = level + #{delta},
                update_time = #{now}
            WHERE source_type = #{sourceType}
              AND source_platform_id = #{sourcePlatformId}
              AND path LIKE CONCAT(#{newPrefix}, '%')
            """)
    int shiftSubtreeLevel(@Param("sourceType") String sourceType,
                          @Param("sourcePlatformId") Long sourcePlatformId,
                          @Param("newPrefix") String newPrefix,
                          @Param("delta") int delta,
                          @Param("now") LocalDateTime now);
}
