package com.solo.gb28181.response;

import com.solo.gb28181.entity.GbDeviceChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

@Schema(description = "通道分页响应项")
@Data
@EqualsAndHashCode(callSuper = true)
public class GbDeviceChannelResponse extends GbDeviceChannel {

    /**
     * 有效组织：通道自身 org_id 优先，为空则继承所属设备的 org_id。
     * 服务端算好回填，前端不必再为每行反查设备。
     */
    @Schema(description = "有效组织 id（自身 org_id 优先，否则继承所属设备；null=未归属）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long effectiveOrgId;

    /**
     * 资源域：通道没有独立的域列，一律继承所属设备（LOCAL / SUBORDINATE / REMOTE）。
     * 服务端算好回填，前端据此决定播放等能力是否可用（远程域只有目录，暂不可点播）。
     */
    @Schema(description = "资源域（继承所属设备；REMOTE=第三方资源平台，仅目录）")
    private String domain;
}
