package com.solo.gb28181.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 树形统计的通用计数行：{@code code} 是国标码（分组/通道），{@code cnt} 是计数。
 * 供「本级子树通道数」「直接下级数」两类聚合 SQL 复用。
 */
@Schema(description = "树形统计计数行")
@Data
public class GbTreeCountVO {

    @Schema(description = "国标码")
    private String code;

    @Schema(description = "计数")
    private Long cnt;
}
