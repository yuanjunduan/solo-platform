package com.solo.gb28181.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.gb28181.entity.GbOrg;
import com.solo.gb28181.response.GbNavCountVO;
import com.solo.gb28181.response.GbOrgCountVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface GbOrgMapper extends BaseMapper<GbOrg> {

    /**
     * 各组织直接归属的本地设备数（不含子组织）。
     * <p>只有 LOCAL 域才有 org_id，下级域的资源按目录 215/216 归组。</p>
     */
    @Select("""
            SELECT org_id AS orgId, COUNT(*) AS total
            FROM gb_device
            WHERE domain = 'LOCAL' AND org_id IS NOT NULL
            GROUP BY org_id
            """)
    List<GbOrgCountVO> selectDeviceCountByOrg();

    /**
     * 各组织直接归属的通道数：有效组织 = COALESCE(通道覆盖值, 设备归属值)。
     * <p>GROUP BY 用完整表达式而不是别名，MySQL 与 PostgreSQL 行为一致。</p>
     */
    @Select("""
            SELECT COALESCE(c.org_id, d.org_id) AS orgId, COUNT(*) AS total
            FROM gb_device_channel c
            JOIN gb_device d ON d.device_id = c.device_id
            WHERE c.parental = 0
              AND d.domain = 'LOCAL'
              AND COALESCE(c.org_id, d.org_id) IS NOT NULL
            GROUP BY COALESCE(c.org_id, d.org_id)
            """)
    List<GbOrgCountVO> selectChannelCountByOrg();

    /**
     * 给定父组织 id 批量统计子组织数（左树懒加载时判断展开箭头）。
     * <p>一次 IN 聚合替代逐节点 COUNT，层内最多 limit 个节点。</p>
     */
    @Select("""
            <script>
            SELECT parent_id AS refId, COUNT(*) AS total
            FROM gb_org
            WHERE parent_id IN
            <foreach collection="parentIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
            GROUP BY parent_id
            </script>
            """)
    List<GbNavCountVO> selectChildCountByParentIds(@Param("parentIds") List<Long> parentIds);
}
