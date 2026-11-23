package com.solo.gb28181.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.gb28181.entity.GbDevice;
import com.solo.gb28181.response.GbDeviceStatusCountVO;
import com.solo.gb28181.response.GbNavCountVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface GbDeviceMapper extends BaseMapper<GbDevice> {

    /**
     * 按状态统计设备数。
     *
     * <p>domain 非空时只统计该资源域；orgIds 非空时锁定 {@code domain = LOCAL} 并只统计这些组织，
     * 集合为空则恒假（组织不存在/已被删）；unassigned=true 表示只统计未归属（org_id IS NULL）。
     * 三个条件都不传时与旧行为完全一致。</p>
     */
    @Select("""
            <script>
            SELECT status, COUNT(*) AS count FROM gb_device
            <where>
                <if test="domain != null and domain != ''">domain = #{domain}</if>
                <if test="platformId != null and platformId != ''">AND device_id = #{platformId}</if>
                <if test="orgIds != null">
                    AND domain = 'LOCAL'
                    <choose>
                        <when test="orgIds.isEmpty()">AND 1 = 0</when>
                        <otherwise>
                            AND org_id IN
                            <foreach collection="orgIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                        </otherwise>
                    </choose>
                </if>
                <if test="unassigned">AND domain = 'LOCAL' AND org_id IS NULL</if>
            </where>
            GROUP BY status
            </script>
            """)
    List<GbDeviceStatusCountVO> selectStatusCount(@Param("domain") String domain,
                                                  @Param("orgIds") List<Long> orgIds,
                                                  @Param("unassigned") boolean unassigned,
                                                  @Param("platformId") String platformId);

    /**
     * 给定组织 id 批量统计直属本地设备数（左树 ORG 节点的计数徽标）。
     * <p>只数 {@code domain = LOCAL}：下级/远程镜像不归属本地组织，org_id 恒为空。</p>
     */
    @Select("""
            <script>
            SELECT org_id AS refId, COUNT(*) AS total
            FROM gb_device
            WHERE domain = 'LOCAL' AND org_id IN
            <foreach collection="orgIds" item="oid" open="(" separator="," close=")">#{oid}</foreach>
            GROUP BY org_id
            </script>
            """)
    List<GbNavCountVO> selectLocalDeviceCountByOrgIds(@Param("orgIds") List<Long> orgIds);
}
