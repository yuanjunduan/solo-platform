package com.solo.gb28181.service;

import com.solo.gb28181.request.GbLocalConfigSaveRequest;
import com.solo.gb28181.response.GbLocalConfigResponse;

/**
 * 本平台国标身份与接入参数（gb_local_config 单行表）。
 *
 * <p>库配置优先于 application yaml：启动时由 {@code GbLocalConfigLoader}
 * 在 SIP 协议栈启动前覆盖 {@link com.solo.gb28181.config.Gb28181Properties}；
 * 保存后身份项（id/domain/ip/port）只在重启生效，其余字段即时热更。</p>
 */
public interface GbLocalConfigService {

    /**
     * 启动时加载：有库记录则逐字段非空覆盖 properties，无记录则沿用 yaml。
     * 加载完成后记录“当前运行身份”快照，供页面判断是否有改动待重启。
     */
    void applyOnStartup();

    /**
     * 读取当前配置。身份项返回库里保存的值（可能是待重启的），
     * 同时通过 running* 字段返回当前实际生效值；password 恒为掩码。
     */
    GbLocalConfigResponse getConfig();

    /**
     * 保存配置：校验 → 身份变更影响校验 → 整行落库 → 热更可即时生效的字段。
     * 返回值同 {@link #getConfig()}，页面据此展示“待重启”标记。
     */
    GbLocalConfigResponse save(GbLocalConfigSaveRequest request);
}
