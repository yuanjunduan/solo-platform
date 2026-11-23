package com.solo.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.system.entity.SysRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 物理清除同编码的「逻辑删除墓碑」。
     *
     * <p>uk_role_code 是不含 deleted 的普通唯一键，而 {@code @TableLogic} 只把行标记 deleted=1，
     * 于是 createRole 的同编码查重（自动带 deleted=0）看不到墓碑、插入却撞唯一键，
     * 最终被兜底异常处理成 500「系统异常」。插入前先清掉墓碑即可让编码复用。</p>
     *
     * @return 被清理的墓碑行数（0 表示没有墓碑，属正常路径）
     */
    @Delete("DELETE FROM sys_role WHERE role_code = #{roleCode} AND deleted = 1")
    int purgeDeletedByRoleCode(@Param("roleCode") String roleCode);
}
