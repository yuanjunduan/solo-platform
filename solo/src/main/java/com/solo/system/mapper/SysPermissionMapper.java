package com.solo.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.system.entity.SysPermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /**
     * 物理清除同权限编码的「逻辑删除墓碑」。
     *
     * <p>uk_permission_code 不含 deleted，{@code @TableLogic} 又让查重看不到墓碑，
     * 新增/改名复用同一编码时会撞唯一键、被兜底成 500「系统异常」。</p>
     *
     * @return 被清理的墓碑行数（0 表示没有墓碑，属正常路径）
     */
    @Delete("DELETE FROM sys_permission WHERE permission_code = #{permissionCode} AND deleted = 1")
    int purgeDeletedByPermissionCode(@Param("permissionCode") String permissionCode);
}
