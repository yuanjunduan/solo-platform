package com.solo.api.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 共享三页的查询入参。
 *
 * <p>分页从 1 开始、{@code pageSize} 默认 20：与同步中心同口径，
 * 前端 {@code useResource} 一页 50 但允许按需覆盖。</p>
 */
@Schema(description = "共享查询入参")
public class ShareQueryRequest {

    @Data
    @Schema(description = "共享项分页（共享总览 / 编排页已共享清单）")
    public static class Item {

        private Long page = 1L;

        private Long pageSize = 20L;

        @Schema(description = "目标上级平台；空 = 全部")
        private Long platformId;

        @Schema(description = "ORG / DEVICE / CHANNEL；空 = 全部")
        private String refType;

        @Schema(description = "EXPLICIT / SUBTREE；空 = 全部")
        private String scopeMode;

        @Schema(description = "PENDING/SYNCED/FAILED/DEAD/REVOKING；空 = 全部")
        private String status;

        @Schema(description = "1=可见 0=已剔除；空 = 全部")
        private Integer enabled;

        @Schema(description = "1=资源存活 0=已撤销；空 = 全部")
        private Integer refAlive;

        @Schema(description = "匹配资源原始编码 / 组织路径")
        private String keyword;
    }

    @Data
    @Schema(description = "出站事件分页（推送记录页）")
    public static class Outbox {

        private Long page = 1L;

        private Long pageSize = 20L;

        private Long platformId;

        @Schema(description = "PENDING/SENT/FAILED/DEAD/PAUSED；空 = 全部")
        private String status;

        @Schema(description = "ADD/UPDATE/DELETE/REFRESH；空 = 全部")
        private String eventType;

        @Schema(description = "匹配 ref_code / exposed_code")
        private String keyword;
    }

    @Data
    @Schema(description = "推送轮次分页")
    public static class Round {

        private Long page = 1L;

        private Long pageSize = 20L;

        private Long platformId;

        @Schema(description = "RUNNING/DONE/FAILED/SKIPPED；空 = 全部")
        private String status;
    }
}
