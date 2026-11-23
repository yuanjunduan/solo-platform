package com.solo.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.system.response.UserAuthResponse;
import com.solo.system.response.UserStatusCountVO;
import com.solo.system.entity.SysUser;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT r.role_code FROM sys_user u " +
            "JOIN sys_user_role ur ON u.id = ur.user_id " +
            "JOIN sys_role r ON ur.role_id = r.id " +
            "WHERE u.id = #{userId} AND u.deleted = 0 AND r.deleted = 0 AND r.status = 1")
    List<String> selectRoleCodes(Long userId);

    @Select("SELECT DISTINCT p.permission_code FROM sys_user u " +
            "JOIN sys_user_role ur ON u.id = ur.user_id " +
            "JOIN sys_role_permission rp ON ur.role_id = rp.role_id " +
            "JOIN sys_permission p ON rp.permission_id = p.id " +
            "JOIN sys_role r ON ur.role_id = r.id " +
            "WHERE u.id = #{userId} AND u.deleted = 0 AND r.deleted = 0 AND r.status = 1 AND p.deleted = 0")
    List<String> selectPermissionCodes(Long userId);

    @Select("SELECT p.id, p.permission_name,p.url, p.icon, p.parent_id, p.sort " +
            "FROM sys_user_role ur " +
            "JOIN sys_role_permission rp ON ur.role_id = rp.role_id " +
            "JOIN sys_permission p ON rp.permission_id = p.id " +
            "JOIN sys_role r ON ur.role_id = r.id " +
            "WHERE ur.user_id = #{userId} AND p.type = 1 AND p.deleted = 0 AND r.deleted = 0 AND r.status = 1 " +
            "ORDER BY p.sort ASC")
    List<UserAuthResponse> auth(Long userId);

    @Select("SELECT status, COUNT(*) AS count FROM sys_user WHERE deleted = 0 GROUP BY status")
    List<UserStatusCountVO> selectStatusCount();

    /**
     * 物理清除同用户名的「逻辑删除墓碑」。
     *
     * <p>与 {@link SysRoleMapper#purgeDeletedByRoleCode} 同因：uk_username 不含 deleted，
     * {@code @TableLogic} 让 createUser 的查重看不到墓碑、插入却撞唯一键，兜底成 500「系统异常」。
     * 插入前先清墓碑，用户名即可复用。</p>
     *
     * @return 被清理的墓碑行数（0 表示没有墓碑，属正常路径）
     */
    @Delete("DELETE FROM sys_user WHERE username = #{username} AND deleted = 1")
    int purgeDeletedByUsername(@Param("username") String username);
}
