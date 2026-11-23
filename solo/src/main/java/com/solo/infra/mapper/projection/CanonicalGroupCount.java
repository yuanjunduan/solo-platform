package com.solo.infra.mapper.projection;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 同源组的成员计数投影（3.10.3）。
 *
 * <p>列表页每行都要显示"同源 N"：按本页出现的组主批量数一次，
 * 而不是每行各查一次成员列表。</p>
 */
@Schema(description = "同源组成员数")
@Data
public class CanonicalGroupCount {

    @Schema(description = "组主 id（= 子记录的 canonical_id，或自身 id）")
    private Long groupId;

    @Schema(description = "组内存活成员数（含组主）")
    private Long total;
}
