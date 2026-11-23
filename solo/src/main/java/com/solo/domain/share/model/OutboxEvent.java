package com.solo.domain.share.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

/**
 * 出站变更事件（本地消息表，3.9.5 / C15）。
 *
 * <p><b>必须与共享变更同事务写入</b>：事务外发消息会出现"共享已生效、上级永远收不到"
 * 或"消息发出去了、共享回滚了"两种都不好查的窗口。写失败就让这次共享提交失败。</p>
 *
 * <p>{@code seqNo} 是<b>平台内</b>单调号，不是全局号：同一个上级收到的序号必须连续，
 * 才能判断"新旧 / 有没有漏包"。分配时锁平台行（{@code platform ... FOR UPDATE}），
 * 不引入额外的号段表。</p>
 */
@Schema(description = "出站变更事件（推送队列）")
@Data
@TableName("outbox")
public class OutboxEvent {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long platformId;

    @Schema(description = "平台内单调递增")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long seqNo;

    @Schema(description = "ADD/UPDATE/DELETE/REFRESH")
    private String eventType;

    @Schema(description = "GROUP / CHANNEL / DEVICE")
    private String refType;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long refId;

    private String refCode;

    @Schema(description = "节点暴露码（报文里的 DeviceID）")
    private String exposedCode;

    @Schema(description = "组装报文所需快照（JSON 文本）：只带定位与名称")
    private String payload;

    @Schema(description = "目录版本，供上级判新旧")
    private Long version;

    @Schema(description = "PENDING/SENT/FAILED/DEAD/PAUSED")
    private String status;

    private Integer retryCount;

    private LocalDateTime nextRetryAt;

    private String lastError;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long roundId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 联表带出：平台名称（不落库） */
    @TableField(exist = false)
    private String platformName;

    public boolean isRefresh() {
        return PublishEventType.REFRESH.equals(eventType);
    }
}
