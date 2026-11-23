package com.solo.gb28181.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.solo.gb28181.entity.GbDevice;
import com.solo.gb28181.entity.GbPlatformRegistry;

import java.util.List;

/**
 * 级联平台登记簿。
 *
 * <p>登记行只做"聚合与展示"，业务键始终是端点国标码；目录数据本身仍只存在
 * {@code gb_device_channel} 一处，避免两套真相。</p>
 */
public interface GbPlatformRegistryService extends IService<GbPlatformRegistry> {

    /** 注册成功时登记/刷新（方向固定 DOWNSTREAM，类型按端点码判定） */
    void upsertOnRegister(GbDevice device);

    /** 端点离线/注销：只把在线态置 0，登记行保留（下次注册复用同一行） */
    void markOffline(String platformGbId);

    /** 目录同步收尾：按端点重算通道数/分组数/同步时间 */
    void refreshCatalogStats(String platformGbId);

    /** 左树根层：按方向取启用中的登记行（下级按通道数倒序，便于优先展示大户） */
    List<GbPlatformRegistry> listByDirection(String direction);
}
