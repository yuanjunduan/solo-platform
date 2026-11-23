package com.solo.domain.event.model;

import com.solo.infra.mapper.projection.SnapshotRow;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

/**
 * 全量快照的一页（6.4）。
 *
 * <p><b>{@code cursor} 是这一页对应的全局游标</b>：客户端按"先拉完快照、再拿 cursor
 * 连 SSE"的顺序接入，两边就严丝合缝——快照覆盖 {@code <= cursor} 的状态，
 * 事件流从 {@code > cursor} 开始补增量。这个字段不是装饰，缺了它对接方只能
 * "先连流再拉快照"，中间那段重复与遗漏无法判断。</p>
 *
 * <p>{@code total/hasNext} 一起给：只想做完整性校验的对接方可以在最后一页比对总数，
 * 不必自己累计。</p>
 */
@Schema(description = "全量快照的一页")
public record ResourceSnapshotPage(

        @Schema(description = "ORG/DEVICE/CHANNEL")
        String type,

        @Schema(description = "实际生效的来源平台（已按订阅 scope 收敛）")
        @JsonSerialize(using = ToStringSerializer.class)
        Long platformId,

        @Schema(description = "实际生效的组织子树前缀（已按订阅 scope 收敛）")
        String orgPath,

        int page,

        int size,

        long total,

        boolean hasNext,

        @Schema(description = "本页对应的全局游标：连 SSE 时作为 Last-Event-ID")
        @JsonSerialize(using = ToStringSerializer.class)
        Long cursor,

        List<SnapshotRow> items
) {
}
