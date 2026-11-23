package com.solo.gb28181.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.gb28181.entity.GbParentPlatform;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GbParentPlatformMapper extends BaseMapper<GbParentPlatform> {

    /**
     * 锁住上级平台行并返回其 id（事务内生效）。
     *
     * <p>出站暴露码的序号分配与推送轮次互斥都要先拿这把锁（C24）：锁放 DB 而不是进程内变量，
     * 多实例部署时另一个节点才挡得住。只取 {@code id}，减少行锁持有期间的无关读取。</p>
     */
    @Select("""
            SELECT id FROM gb_parent_platform WHERE id = #{id} FOR UPDATE
            """)
    Long lockById(@Param("id") Long id);
}
