package com.solo.api.platform.dto;

import com.solo.domain.platform.model.Platform;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台列表行：一行同时表达"它给我什么"和"我给它什么"（4.4.2）。
 *
 * <p>资源数/最近同步不是 {@code platform} 表的列，而是列表时按平台各查一次——
 * 平台表通常只有几十行，比为了展示在平台表上维护冗余计数（还要处理同步失败后的回滚）更省事。</p>
 */
@Schema(description = "平台列表行")
@Data
public class PlatformListItem {

    private Platform platform;

    @Schema(description = "入站组织数")
    private long inboundOrg;

    @Schema(description = "入站设备数")
    private long inboundDevice;

    @Schema(description = "入站通道数")
    private long inboundChannel;

    @Schema(description = "最近一轮的状态 / 时间 / 摘要")
    private String lastSyncStatus;

    private LocalDateTime lastSyncAt;

    @Schema(description = "最近一轮的 G2 是否跳过删除（优先于状态提示）")
    private Integer lastDeleteSkipped;

    @Schema(description = "当前活跃轮次：id 与进度；无活跃轮次时为 null")
    private Long activeTaskId;

    private Integer activeProgress;

    @Schema(description = "失败冷却剩余毫秒，0 = 可以立即触发")
    private long cooldownMs;
}
