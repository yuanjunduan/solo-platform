package com.solo.gb28181.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

@Schema(description = "本地组织树节点")
@Data
public class GbOrgTreeVO {

    @Schema(description = "组织主键 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "父组织主键 ID（根 = 0）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    @Schema(description = "组织名称")
    private String name;

    @Schema(description = "组织国标编码")
    private String code;

    @Schema(description = "物化路径 /根id/.../自身id/")
    private String path;

    @Schema(description = "层级（根 = 1）")
    private Integer level;

    @Schema(description = "同级排序")
    private Integer sort;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "直接归属本组织的设备数（不含子组织）")
    private Long directDeviceCount;

    @Schema(description = "直接归属本组织的通道数（含通道级覆盖值，不含子组织）")
    private Long directChannelCount;

    @Schema(description = "子组织")
    private List<GbOrgTreeVO> children;
}
