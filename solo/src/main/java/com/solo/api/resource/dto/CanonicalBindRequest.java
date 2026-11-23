package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 同源绑定入参（3.10.3）。
 *
 * <p>只做"新建组"：至少两条，且每条都必须是自由行（未属于任何组、也不是别组组主）。</p>
 */
@Schema(description = "同源绑定入参")
@Data
public class CanonicalBindRequest {

    @Schema(description = "通道 id 列表（至少 2 条）")
    @NotNull(message = "通道 id 列表不能为空")
    @Size(min = 2, max = 50, message = "同源绑定一次可选 2~50 条通道")
    private List<Long> ids;
}
