package com.solo.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.system.entity.SysDictType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysDictTypeMapper extends BaseMapper<SysDictType> {

    /**
     * 物理清除同字典类型编码的「逻辑删除墓碑」。
     *
     * <p>uk_dict_type 不含 deleted，{@code @TableLogic} 又让查重看不到墓碑，
     * 新增/改编码复用同一 dict_type 时会撞唯一键、被兜底成 500「系统异常」。</p>
     *
     * @return 被清理的墓碑行数（0 表示没有墓碑，属正常路径）
     */
    @Delete("DELETE FROM sys_dict_type WHERE dict_type = #{dictType} AND deleted = 1")
    int purgeDeletedByDictType(@Param("dictType") String dictType);
}
