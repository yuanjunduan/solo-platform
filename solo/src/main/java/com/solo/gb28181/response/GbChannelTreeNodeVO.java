package com.solo.gb28181.response;

import com.solo.gb28181.entity.GbDeviceChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 国标目录树节点：字段与 {@link GbDeviceChannel} 一致（前端可直接复用检视器），
 * 分组（parental=1）通过 children 挂下级分组/通道，通道（parental=0）为叶子。
 */
@Schema(description = "国标目录树节点")
@Data
@EqualsAndHashCode(callSuper = true)
public class GbChannelTreeNodeVO extends GbDeviceChannel {

    @Schema(description = "子节点：分组下挂的下级分组/通道；通道无子节点；懒加载时为空，按需再查")
    private List<GbChannelTreeNodeVO> children = new ArrayList<>();

    @Schema(description = "该节点（含所有子分组）下的通道总数；通道节点为 null")
    private Long subtreeChannelCount;

    @Schema(description = "是否还有直接下级（控制展开箭头）；通道节点为 false")
    private Boolean hasChildren = Boolean.FALSE;
}
