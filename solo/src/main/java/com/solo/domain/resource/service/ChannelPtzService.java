package com.solo.domain.resource.service;

import com.solo.api.resource.dto.ChannelPresetSaveRequest;
import com.solo.api.resource.dto.ChannelPtzCommandRequest;
import com.solo.api.resource.dto.ChannelPtzDragZoomRequest;
import com.solo.domain.resource.model.ResChannelPreset;

import java.util.List;

/**
 * 统一通道入口的云台与预置位（3.11.5）。
 *
 * <p>本地（NATIVE）与入站（INBOUND）同一套端点：由 {@code res_channel} 解析出 SIP 的设备码/通道码，
 * 再复用既有 PTZ 指令构造与发送链路。入站指令实际发到来源平台，由它转发给真实设备。</p>
 *
 * <p>能力位明确不含 {@code PTZ} 时拒绝；为空按未知放行——设备会以失败应答回应。</p>
 */
public interface ChannelPtzService {

    /** 方向控制（八方向 + 缩放 + 停止） */
    void move(Long channelRowId, ChannelPtzCommandRequest req);

    /** 聚焦：near / far / stop */
    void focus(Long channelRowId, String direction, int speed);

    /** 光圈：open / close / stop */
    void iris(Long channelRowId, String direction, int speed);

    /** 雨刷：on / off */
    void wiper(Long channelRowId, String action);

    /** 拉框放大 / 缩小 */
    void dragZoom(Long channelRowId, ChannelPtzDragZoomRequest req);

    /** 看守位（自动归位） */
    void homePosition(Long channelRowId, boolean enabled, Integer resetTime, Integer presetIndex);

    /* ==================== 预置位 ==================== */

    List<ResChannelPreset> listPresets(Long channelRowId);

    /** 设置预置位：同编号已存在则覆盖该槽位（设备侧重新采集，本地只更新名称） */
    ResChannelPreset savePreset(Long channelRowId, ChannelPresetSaveRequest req);

    /** 删除预置位：先通知设备删除，成功后再删本地行 */
    void deletePreset(Long channelRowId, Long presetId);

    /** 调用预置位：设备转到该槽位 */
    void callPreset(Long channelRowId, Long presetId);
}
