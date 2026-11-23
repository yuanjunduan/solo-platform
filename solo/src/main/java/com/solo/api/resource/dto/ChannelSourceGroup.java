package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 同源组视图（3.10.3）：组主 + 全部成员。
 *
 * <p>为什么带平台名：成员之间的差别就是"来自哪条链路"，只给 id 或裸露的国标码，
 * 人眼没法判断该解除哪一条。</p>
 */
@Schema(description = "同源组视图")
@Data
public class ChannelSourceGroup {

    @Schema(description = "组主 id")
    private Long leaderId;

    @Schema(description = "成员（按 id 升序，含组主）")
    private List<Member> members;

    @Schema(description = "同源组成员")
    @Data
    public static class Member {

        @Schema(description = "res_channel.id")
        private Long id;

        @Schema(description = "通道编码（各自来源下的暴露码）")
        private String code;

        @Schema(description = "通道名称")
        private String name;

        @Schema(description = "NATIVE / INBOUND")
        private String sourceType;

        @Schema(description = "直接来源平台 id，0 = 本地")
        private Long sourcePlatformId;

        @Schema(description = "直接来源平台名称，本地为空")
        private String sourcePlatformName;

        @Schema(description = "在传在线状态 ON/OFF")
        private String onlineStatus;

        @Schema(description = "级联跳数（0 = 本地直连）")
        private Integer hopCount;

        @Schema(description = "资源状态 ACTIVE/STALE/OFFLINE/REMOVED")
        private String status;

        @Schema(description = "是否组主")
        private boolean leader;
    }
}
