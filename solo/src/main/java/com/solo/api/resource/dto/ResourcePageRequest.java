package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 资源分页查询入参（设备 / 通道共用）。
 *
 * <p>{@code orgId + includeSubtree} 走的是 {@code org_path} 前缀匹配，
 * 而不是先查出子树组织 id 再 IN——后者在组织多时会把 SQL 拼成几千个参数。</p>
 */
@Schema(description = "资源分页查询入参")
@Data
public class ResourcePageRequest {

    @Schema(description = "页码，从 1 开始")
    private Long page = 1L;

    @Schema(description = "每页条数")
    private Long pageSize = 20L;

    @Schema(description = "关键词，匹配编码 / 名称 / 设备地址")
    private String keyword;

    @Schema(description = "所属组织 id；空=不过滤")
    private Long orgId;

    @Schema(description = "按组织过滤时是否包含子孙组织，默认 true")
    private boolean includeSubtree = true;

    @Schema(description = "所属设备 id（仅通道页使用）")
    private Long deviceId;

    @Schema(description = "来源 NATIVE / INBOUND；空=全部")
    private String sourceType;

    @Schema(description = "状态 ACTIVE/STALE/OFFLINE/REMOVED；空=全部可见")
    private String status;
}
