package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 组织新增 / 编辑入参（只针对本地资源；外部来源由同步链路写入）。
 */
@Schema(description = "组织保存入参")
@Data
public class OrgSaveRequest {

    @Schema(description = "组织编码（本地自编，建立后不可改——它是业务键的一部分）")
    @NotBlank(message = "组织编码不能为空")
    @Size(max = 64, message = "组织编码不能超过 64 字符")
    private String code;

    @Schema(description = "组织名称")
    @NotBlank(message = "组织名称不能为空")
    @Size(max = 128, message = "组织名称不能超过 128 字符")
    private String name;

    @Schema(description = "父组织 id；空或 0 表示根节点")
    private Long parentId;

    @Schema(description = "本地别名（同步不覆盖，可空）")
    @Size(max = 128, message = "本地别名不能超过 128 字符")
    private String localName;

    @Schema(description = "同级排序号，越小越靠前")
    private Integer sortNo;
}
