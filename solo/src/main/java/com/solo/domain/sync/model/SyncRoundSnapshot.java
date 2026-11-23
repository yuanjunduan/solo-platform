package com.solo.domain.sync.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 原始报文快照（3.13.2，C 档：默认关闭）。
 *
 * <p>唯一能回答"<b>是上游没发，还是我们弄丢了</b>"的证据。只存比对所需字段，
 * 不存整段报文——它要能扛住 100 万通道 × 2 轮约 400MB 的量级，且删得快。</p>
 */
@Schema(description = "原始报文快照：默认关闭，只保留最近 N 轮")
@Data
@TableName("sync_round_snapshot")
public class SyncRoundSnapshot {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long taskId;
    private Long platformId;

    @Schema(description = "ORG/GROUP/DEVICE/CHANNEL")
    private String itemType;

    private String code;
    private String parentCode;
    private String name;
    private String onlineStatus;
    private String deviceCode;
    private String streamType;
    private String capability;
    private String deviceIp;
    private String vendor;
    private String model;
    private String groupType;

    private LocalDateTime createTime;
}
