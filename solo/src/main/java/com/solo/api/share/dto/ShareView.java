package com.solo.api.share.dto;

import com.solo.domain.resource.model.ResChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 共享三页的展示行。
 */
@Schema(description = "共享展示行")
public class ShareView {

    /**
     * 目标上级平台的汇总卡片（4.2 共享总览）。
     *
     * <p>{@code summaryStatus} 直接由 {@code ShareStatusMerge.worst} 算出，
     * 前端不再自己比较优先级——两处各写一套，迟早出现"后端说 DEAD、页面显示正常"。</p>
     */
    @Data
    @Schema(description = "上级平台汇总")
    public static class Target {

        private Long id;

        private String name;

        private String serverGbId;

        @Schema(description = "0=禁用 1=启用")
        private Integer enabled;

        @Schema(description = "0=离线 1=在线")
        private Integer status;

        @Schema(description = "可见通道数")
        private long channelCount;

        @Schema(description = "生效子树规则数")
        private long ruleCount;

        @Schema(description = "被剔除的通道数")
        private long excludedCount;

        @Schema(description = "平台汇总状态（取最差）；null = 还没有共享项")
        private String summaryStatus;

        @Schema(description = "待推出站事件数")
        private long pendingCount;

        @Schema(description = "最近一轮推送状态")
        private String lastRoundStatus;

        private Integer lastRoundSent;

        private Integer lastRoundFailed;

        private LocalDateTime lastPushedAt;
    }

    /**
     * 编排页"选择资源"的一行：通道本体 + 名称 + 当前对该上级的可见性。
     *
     * <p>{@code shared/excluded} 必须由后端回填：前端只有当前页的勾选状态，
     * 而"这条通道在别的页面已经被共享/剔除"恰恰是最容易重复操作的地方。</p>
     */
    @Data
    @Schema(description = "可选通道（含对该上级的共享状态）")
    public static class ChannelOption {

        private ResChannel channel;

        private String deviceName;

        private String orgName;

        @Schema(description = "已对该上级可见")
        private boolean shared;

        @Schema(description = "已被剔除（enabled=0）")
        private boolean excluded;

        @Schema(description = "已分配的暴露码；未分配为空")
        private String exposedCode;
    }
}
