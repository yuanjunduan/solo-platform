package com.solo.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.system.entity.SysServer;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysServerMapper extends BaseMapper<SysServer> {

    /**
     * 物理清除同服务器名称的「逻辑删除墓碑」。
     *
     * <p>uk_server_name 不含 deleted，{@code @TableLogic} 又让查重看不到墓碑，
     * 新增/改名复用同一 server_name 时会撞唯一键、被兜底成 500「系统异常」。</p>
     *
     * @return 被清理的墓碑行数（0 表示没有墓碑，属正常路径）
     */
    @Delete("DELETE FROM sys_server WHERE server_name = #{serverName} AND deleted = 1")
    int purgeDeletedByServerName(@Param("serverName") String serverName);
}
