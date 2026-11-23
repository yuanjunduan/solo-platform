package com.solo.infra.mapper.projection;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 暴露码号段高水位投影（编码映射页展示"下一个可用序号"）。
 *
 * <p>不用 record 而用 {@code @Data} 类：与既有 projection 包同风格，
 * 也避免依赖编译参数 {@code -parameters} 才能按构造器名映射。</p>
 */
@Schema(description = "号段高水位：某个类型段已分配到的最大序号")
@Data
public class CodeSeqHighWater {

    @Schema(description = "类型段：200 设备 / 131 通道 / 215 业务分组 / 216 虚拟组织")
    private String typeSeg;

    @Schema(description = "已分配到的最大序号（含墓碑）")
    private Long seqNo;
}
