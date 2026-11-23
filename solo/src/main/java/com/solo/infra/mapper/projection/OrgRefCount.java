package com.solo.infra.mapper.projection;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * "按组织分组"的资源计数投影（组织树节点展示用）。
 *
 * <p>用一条 {@code GROUP BY} 取全量计数，而不是每个节点各 count 一次：
 * 树上有 N 个组织时后者是 N 次往返，200 个组织就会把树接口拖到几百毫秒。</p>
 */
@Schema(description = "组织下的资源计数")
@Data
public class OrgRefCount {

    @Schema(description = "组织 id")
    private Long orgId;

    @Schema(description = "该组织直接归属的资源数（不含子组织）")
    private Long total;
}
