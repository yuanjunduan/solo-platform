package com.solo.infra.mapper;

import com.solo.infra.mapper.projection.SnapshotRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 全量快照的分页读取（6.4 "全量快照接口"）。
 *
 * <p><b>为什么不用 MP 的 {@code Page} 插件</b>：这里要的 WHERE 只有两个可选条件
 * （来源平台 / 组织路径前缀），写成 6 条固定 SQL 比让分页插件改写语句更可控——
 * 方言差异也只在 {@code LIMIT/OFFSET} 上，MySQL 与 PG 写法一致。</p>
 *
 * <p><b>为什么排除 {@code REMOVED}</b>：快照是"当前态"，已确认删除的行不该出现在里面
 * （STALE 保留，见 {@link SnapshotRow} 的说明）。事件流才是"发生过什么"。</p>
 */
@Mapper
public interface ResourceSnapshotMapper {

    @Select("""
            <script>
            SELECT id, code, name, path AS org_path, parent_code, level, status, update_time
            FROM res_org
            WHERE source_type = 'INBOUND' AND status &lt;&gt; 'REMOVED'
            <if test="platformId != null"> AND source_platform_id = #{platformId} </if>
            <if test="orgPath != null"> AND path LIKE CONCAT(#{orgPath}, '%') </if>
            ORDER BY code
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<SnapshotRow> pageOrgs(@Param("platformId") Long platformId,
                               @Param("orgPath") String orgPath,
                               @Param("offset") int offset,
                               @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM res_org
            WHERE source_type = 'INBOUND' AND status &lt;&gt; 'REMOVED'
            <if test="platformId != null"> AND source_platform_id = #{platformId} </if>
            <if test="orgPath != null"> AND path LIKE CONCAT(#{orgPath}, '%') </if>
            </script>
            """)
    long countOrgs(@Param("platformId") Long platformId, @Param("orgPath") String orgPath);

    @Select("""
            <script>
            SELECT id, code, name, org_path, status, update_time
            FROM res_device
            WHERE source_type = 'INBOUND' AND status &lt;&gt; 'REMOVED'
            <if test="platformId != null"> AND source_platform_id = #{platformId} </if>
            <if test="orgPath != null"> AND org_path LIKE CONCAT(#{orgPath}, '%') </if>
            ORDER BY code
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<SnapshotRow> pageDevices(@Param("platformId") Long platformId,
                                  @Param("orgPath") String orgPath,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM res_device
            WHERE source_type = 'INBOUND' AND status &lt;&gt; 'REMOVED'
            <if test="platformId != null"> AND source_platform_id = #{platformId} </if>
            <if test="orgPath != null"> AND org_path LIKE CONCAT(#{orgPath}, '%') </if>
            </script>
            """)
    long countDevices(@Param("platformId") Long platformId, @Param("orgPath") String orgPath);

    @Select("""
            <script>
            SELECT id, code, name, org_path, device_id, online_status, capability, status, update_time
            FROM res_channel
            WHERE source_type = 'INBOUND' AND status &lt;&gt; 'REMOVED'
            <if test="platformId != null"> AND source_platform_id = #{platformId} </if>
            <if test="orgPath != null"> AND org_path LIKE CONCAT(#{orgPath}, '%') </if>
            ORDER BY code
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<SnapshotRow> pageChannels(@Param("platformId") Long platformId,
                                   @Param("orgPath") String orgPath,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM res_channel
            WHERE source_type = 'INBOUND' AND status &lt;&gt; 'REMOVED'
            <if test="platformId != null"> AND source_platform_id = #{platformId} </if>
            <if test="orgPath != null"> AND org_path LIKE CONCAT(#{orgPath}, '%') </if>
            </script>
            """)
    long countChannels(@Param("platformId") Long platformId, @Param("orgPath") String orgPath);
}
