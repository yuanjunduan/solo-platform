package com.solo.gb28181.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 左树层内计数（子节点数 / 直属设备数）。
 *
 * <p>单独建 VO 而不用 Map 接结果，是因为 MySQL 保留别名大小写、PostgreSQL 一律折叠成小写，
 * 走 Map 会在双库之间产生 key 不一致的隐性 bug。</p>
 */
@Schema(description = "左树层内计数")
@Data
public class GbNavCountVO {

    @Schema(description = "被统计的主键（组织 id 或父组织 id）")
    private Long refId;

    @Schema(description = "数量")
    private Long total;
}
