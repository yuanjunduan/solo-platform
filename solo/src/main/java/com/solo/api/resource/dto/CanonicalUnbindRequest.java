package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 解除同源关联入参（3.10.3）。
 *
 * <p>只解除"这一行"：它是组主且组内还有成员时，后端会从剩余成员里提升新组主，
 * 不会把整组一起打散——整组撤销没有"一键"入口，必须一条一条来。</p>
 */
@Schema(description = "解除同源关联入参")
@Data
public class CanonicalUnbindRequest {

    @Schema(description = "要解除关联的通道 id")
    @NotNull(message = "通道 id 不能为空")
    private Long id;
}
