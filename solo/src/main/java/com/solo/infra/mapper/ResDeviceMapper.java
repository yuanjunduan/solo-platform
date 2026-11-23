package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.resource.model.ResDevice;
import com.solo.infra.mapper.projection.OrgRefCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备持久化。
 */
@Mapper
public interface ResDeviceMapper extends BaseMapper<ResDevice> {

    default ResDevice selectByBizKey(String sourceType, Long sourcePlatformId, String code) {
        return selectOne(new LambdaQueryWrapper<ResDevice>()
                .eq(ResDevice::getSourceType, sourceType)
                .eq(ResDevice::getSourcePlatformId, sourcePlatformId)
                .eq(ResDevice::getCode, code)
                .last("limit 1"));
    }

    /** 组织下是否还有设备（删除组织前的级联校验） */
    default long countByOrgId(Long orgId) {
        return selectCount(new LambdaQueryWrapper<ResDevice>().eq(ResDevice::getOrgId, orgId));
    }

    /** 按组织分组统计设备数（组织树节点计数，一次查询取全量） */
    @Select("""
            SELECT org_id AS orgId, COUNT(*) AS total
            FROM res_device
            WHERE status <> 'REMOVED'
            GROUP BY org_id
            """)
    List<OrgRefCount> countGroupByOrg();

    /**
     * 组织换父后刷新设备的位置快照。
     * <p>按 {@code org_path} 前缀圈定受影响范围——它本身就是所属组织的路径快照，
     * 不需要先把子树组织 id 全查出来再 IN，组织规模大时这一步是 O(1) 条 SQL。</p>
     *
     * <p><b>不按来源圈定</b>：设备的来源平台不一定等于它所属组织的来源平台
     * （同一组织下可能挂多个下级推来的设备），按来源过滤会漏刷，留下旧路径前缀。</p>
     */
    @Update("""
            UPDATE res_device
            SET org_path = REPLACE(org_path, #{oldPrefix}, #{newPrefix}),
                update_time = #{now}
            WHERE org_path LIKE CONCAT(#{oldPrefix}, '%')
            """)
    int rewriteOrgPath(@Param("oldPrefix") String oldPrefix,
                       @Param("newPrefix") String newPrefix,
                       @Param("now") LocalDateTime now);
}
