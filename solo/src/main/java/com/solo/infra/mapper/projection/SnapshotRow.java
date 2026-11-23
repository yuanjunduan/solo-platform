package com.solo.infra.mapper.projection;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

/**
 * 快照行（6.4 的"全量快照接口"）。
 *
 * <p>三类资源的公共字段 + 各自的少量差异列合成一张投影：</p>
 * <ul>
 *   <li>组织：{@code parent_code / level}；</li>
 *   <li>设备：无额外列（{@code org_path} 就是位置）；</li>
 *   <li>通道：{@code device_id / online_status / capability}。</li>
 * </ul>
 *
 * <p><b>为什么带 {@code status} 而不只吐 ACTIVE</b>：{@code sync.delete.mode=mark} 下
 * 消失的资源是 STALE 而不是 REMOVED，它"还在但可能没了"。把状态原样给出去，
 * 对接方才能自己决定是删缓存还是标注，而不是被迫接受服务端的猜测。</p>
 */
@Data
public class SnapshotRow {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String code;

    private String name;

    /** 组织是 path，设备/通道是 org_path */
    private String orgPath;

    private String parentCode;

    private Integer level;

    /** ACTIVE / STALE / OFFLINE */
    private String status;

    /** 仅通道：ON / OFF */
    private String onlineStatus;

    /** 仅通道：PTZ/RECORD/AUDIO/TALK 位串 */
    private String capability;

    /** 仅通道：载体设备 id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long deviceId;

    private LocalDateTime updateTime;
}
