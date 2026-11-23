package com.solo.gb28181.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.gb28181.entity.GbPlatformRegistry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 级联平台登记簿 Mapper。
 *
 * <p>计数与同步时间不改由业务逐条累加（并发下会漂），统一在目录同步收尾时按端点重算一次：
 * 通道量级大时这两条子查询都走 {@code idx_device_tree_path / uk_device_channel} 的
 * {@code device_id} 前缀，不会全表扫。</p>
 */
@Mapper
public interface GbPlatformRegistryMapper extends BaseMapper<GbPlatformRegistry> {

    /** 按目录端点重算通道数/分组数/同步时间/在线态（只动指定端点这一行） */
    @Update("""
            UPDATE gb_platform_registry
            SET channel_count = (SELECT COUNT(*) FROM gb_device_channel c
                                 WHERE c.device_id = gb_platform_registry.platform_gb_id AND c.parental = 0),
                group_count = (SELECT COUNT(*) FROM gb_device_channel c
                               WHERE c.device_id = gb_platform_registry.platform_gb_id AND c.parental = 1),
                last_catalog_sync_time = (SELECT MAX(d.last_catalog_sync_time) FROM gb_device d
                                          WHERE d.device_id = gb_platform_registry.platform_gb_id),
                online_status = COALESCE((SELECT MAX(d.status) FROM gb_device d
                                          WHERE d.device_id = gb_platform_registry.platform_gb_id),
                                         online_status)
            WHERE direction = 'DOWNSTREAM' AND platform_gb_id = #{platformGbId}
            """)
    int refreshCatalogStats(@Param("platformGbId") String platformGbId);
}
