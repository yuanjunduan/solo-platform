package com.solo.gb28181.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "新建/编辑本地组织请求")
@Data
public class GbOrgSaveRequest {

    @Schema(description = "父组织主键（根 = 0 或留空）")
    private Long parentId;

    @Schema(description = "组织名称（必填，同一父节点下唯一）")
    @NotBlank(message = "组织名称不能为空")
    @Size(max = 64, message = "组织名称不能超过 64 个字符")
    private String name;

    @Schema(description = "组织国标编码（可空；填写时必须是 20 位数字，平台内唯一）")
    private String code;

    @Schema(description = "同级排序（小的在前，默认 0）")
    private Integer sort;

    @Schema(description = "备注")
    @Size(max = 255, message = "备注不能超过 255 个字符")
    private String remark;
}
