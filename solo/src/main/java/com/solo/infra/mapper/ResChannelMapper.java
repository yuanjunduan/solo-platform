package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.resource.model.ResChannel;
import com.solo.infra.mapper.projection.CanonicalAnomaly;
import com.solo.infra.mapper.projection.CanonicalGroupCount;
import com.solo.infra.mapper.projection.OrgRefCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 通道持久化。
 */
@Mapper
public interface ResChannelMapper extends BaseMapper<ResChannel> {

    default ResChannel selectByBizKey(String sourceType, Long sourcePlatformId, String code) {
        return selectOne(new LambdaQueryWrapper<ResChannel>()
                .eq(ResChannel::getSourceType, sourceType)
                .eq(ResChannel::getSourcePlatformId, sourcePlatformId)
                .eq(ResChannel::getCode, code)
                .last("limit 1"));
    }

    /** 组织下是否还有通道（删除组织前的级联校验） */
    default long countByOrgId(Long orgId) {
        return selectCount(new LambdaQueryWrapper<ResChannel>().eq(ResChannel::getOrgId, orgId));
    }

    /** 设备下是否还有通道（删除设备前的级联校验） */
    default long countByDeviceId(Long deviceId) {
        return selectCount(new LambdaQueryWrapper<ResChannel>().eq(ResChannel::getDeviceId, deviceId));
    }

    /** 按组织分组统计通道数（组织树节点计数，一次查询取全量） */
    @Select("""
            SELECT org_id AS orgId, COUNT(*) AS total
            FROM res_channel
            WHERE status <> 'REMOVED'
            GROUP BY org_id
            """)
    List<OrgRefCount> countGroupByOrg();

    /**
     * 组织换父后刷新通道的位置快照（与 {@link ResDeviceMapper#rewriteOrgPath} 同口径）：
     * 只按 {@code org_path} 前缀圈定，不按来源过滤，避免多来源设备挂同一组织时漏刷。
     */
    @Update("""
            UPDATE res_channel
            SET org_path = REPLACE(org_path, #{oldPrefix}, #{newPrefix}),
                update_time = #{now}
            WHERE org_path LIKE CONCAT(#{oldPrefix}, '%')
            """)
    int rewriteOrgPath(@Param("oldPrefix") String oldPrefix,
                       @Param("newPrefix") String newPrefix,
                       @Param("now") LocalDateTime now);

    /**
     * 组织子树下的存活通道（SUBTREE 规则派生用）。
     * <p>排除 REMOVED：已消失的资源不该被规则重新派生出来（恢复走"复用自身墓碑码"那条路）。</p>
     */
    @Select("""
            SELECT * FROM res_channel
            WHERE status <> 'REMOVED' AND org_path LIKE CONCAT(#{pathPrefix}, '%')
            ORDER BY org_path, id
            """)
    List<ResChannel> selectAliveByOrgPathPrefix(@Param("pathPrefix") String pathPrefix);

    /** 设备下的存活通道（共享设备 = 共享其全部通道） */
    @Select("""
            SELECT * FROM res_channel
            WHERE status <> 'REMOVED' AND device_id = #{deviceId}
            ORDER BY id
            """)
    List<ResChannel> selectAliveByDeviceId(@Param("deviceId") Long deviceId);

    /**
     * 同源组成员：组主自身 + 所有指向它的子行（3.10.3）。
     * <p>排除 REMOVED：已消失的行只是历史记录，不再是可以参与取流的成员。</p>
     */
    @Select("""
            SELECT * FROM res_channel
            WHERE status <> 'REMOVED'
              AND (id = #{leaderId} OR canonical_id = #{leaderId})
            ORDER BY id
            """)
    List<ResChannel> selectByCanonicalGroup(@Param("leaderId") Long leaderId);

    /**
     * 这些行里"已经被别人指向"的 id（即已是某个组的组主）。
     * <p>绑定/自动关联前必须查一次：把别人的组主挂到新组主下面会形成两级指针，
     * 原来的子记录就再也查不到自己的组主了。</p>
     */
    @Select("""
            <script>
            SELECT DISTINCT canonical_id FROM res_channel
            WHERE status &lt;&gt; 'REMOVED'
              AND canonical_id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> selectReferencedLeaderIds(@Param("ids") Collection<Long> ids);

    /**
     * 某平台涉及到的全局身份，及其在全表范围内的全部存活行（L1 自动关联用，3.10.3）。
     *
     * <p>先取出本平台那些带身份的行所属的 {@code (origin_platform_id, origin_code)} 组合，
     * 再按组合把全表的行捞出来——只需一次往返，且组合数量受本平台通道数约束。
     * 命中 {@code idx_origin}。</p>
     */
    @Select("""
            SELECT c.*
            FROM res_channel c
            JOIN (
                SELECT DISTINCT origin_platform_id AS op, origin_code AS oc
                FROM res_channel
                WHERE source_platform_id = #{platformId}
                  AND status <> 'REMOVED'
                  AND origin_platform_id > 0
                  AND origin_code IS NOT NULL
                  AND origin_code <> ''
            ) p ON p.op = c.origin_platform_id AND p.oc = c.origin_code
            WHERE c.status <> 'REMOVED'
            ORDER BY c.origin_platform_id, c.origin_code, c.id
            """)
    List<ResChannel> selectByOriginPairsOf(@Param("platformId") Long platformId);

    /** 一批组主各自的成员数（列表页"同源 N"徽标用，避免每行再查一次） */
    @Select("""
            <script>
            SELECT COALESCE(NULLIF(canonical_id, 0), id) AS groupId, COUNT(*) AS total
            FROM res_channel
            WHERE status &lt;&gt; 'REMOVED'
              AND (id IN
              <foreach collection="leaderIds" item="lid" open="(" separator="," close=")">#{lid}</foreach>
              OR canonical_id IN
              <foreach collection="leaderIds" item="lid" open="(" separator="," close=")">#{lid}</foreach>)
            GROUP BY COALESCE(NULLIF(canonical_id, 0), id)
            </script>
            """)
    List<CanonicalGroupCount> countByGroupLeaders(@Param("leaderIds") Collection<Long> leaderIds);

    /**
     * 同源关联只改 {@code canonical_id}（3.10.3）。
     *
     * <p>显式带 {@code source_type}：一是满足 {@code ResourceWriteGuardInterceptor} 的
     * "写资源表必须圈定来源"要求，二是保证只动这一行（id + 来源），不会误伤别的镜像行。
     * 来源为空的老数据用 {@code OR #{sourceType} IS NULL} 兜住。</p>
     *
     * <p>带 {@code expectedCanonicalId} 做 CAS：两个人同时绑同一行时，后到的更新影响 0 行，
     * 由调用方提示"已被其它操作修改"。{@code COALESCE} 把历史 NULL 视作 0（未关联）。</p>
     */
    @Update("""
            UPDATE res_channel
            SET canonical_id = #{canonicalId},
                update_time = #{now}
            WHERE id = #{id}
              AND COALESCE(canonical_id, 0) = #{expectedCanonicalId}
              AND (source_type = #{sourceType} OR #{sourceType} IS NULL)
            """)
    int updateCanonical(@Param("id") Long id,
                        @Param("sourceType") String sourceType,
                        @Param("expectedCanonicalId") Long expectedCanonicalId,
                        @Param("canonicalId") Long canonicalId,
                        @Param("now") LocalDateTime now);

    /**
     * 同源组的双向一致性核对（只读，M5-S5）。
     *
     * <p>四个数分别对应一类"指针说不通"的行：</p>
     * <ul>
     *   <li>{@code selfPointer}：{@code canonical_id = id}——自指，语义上应写 0；</li>
     *   <li>{@code orphan}：指向了一个不存在的行（物理删除或数据搬迁留下的孤儿）；</li>
     *   <li>{@code twoLevel}：组主自己还有 {@code canonical_id}——两级指针，子记录会找不到最终组主；</li>
     *   <li>{@code leaderRemoved}：组主已 REMOVED——成员还活着但组主不再可播。</li>
     * </ul>
     *
     * <p>只统计存活行；只报不改：核对结果用于运维判断，修复一律走人工解除/重绑。</p>
     */
    @Select("""
            SELECT
                COUNT(CASE WHEN c.canonical_id = c.id THEN 1 END) AS selfPointer,
                COUNT(CASE WHEN c.canonical_id <> c.id AND l.id IS NULL THEN 1 END) AS orphan,
                COUNT(CASE WHEN l.canonical_id > 0 THEN 1 END) AS twoLevel,
                COUNT(CASE WHEN l.id IS NOT NULL AND l.status = 'REMOVED' THEN 1 END) AS leaderRemoved
            FROM res_channel c
            LEFT JOIN res_channel l ON l.id = c.canonical_id
            WHERE c.status <> 'REMOVED'
              AND c.canonical_id > 0
            """)
    CanonicalAnomaly countCanonicalAnomalies();
}
