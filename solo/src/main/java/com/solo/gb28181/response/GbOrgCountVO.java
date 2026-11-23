package com.solo.gb28181.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "组织维度的资源计数")
@Data
public class GbOrgCountVO {

    @Schema(description = "组织主键 ID")
    private Long orgId;

    @Schema(description = "资源数量")
    private Long total;
}
