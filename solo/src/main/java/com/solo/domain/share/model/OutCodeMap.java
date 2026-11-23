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
 * 编码映射：资源原始码 ↔ 对某个上级的暴露码（3.9.3 / C16）。
 *
 * <p><b>入站不改码，出站必重编号</b>：本地资源天然属于本平台域，透传；
 * 入站资源属于来源平台域，必须换码，否则上级会看到"别人的域"并与本地码冲突。</p>
 *
 * <p><b>编码一经分配不随资源变化</b>：改名、换组、换挂载位置都不重新分配。
 * 撤销时置 {@code TOMBSTONE} 而<b>不删行</b>：序号在不同资源之间永不复用，
 * 同一资源消失后恢复则复用自己的墓碑码——否则上级会把它当成"新通道"。</p>
 */
@Schema(description = "编码映射：原始码 ↔ 暴露码")
@Data
@TableName("out_code_map")
public class OutCodeMap {

    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_TOMBSTONE = "TOMBSTONE";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long platformId;

    @Schema(description = "CHANNEL / DEVICE / GROUP")
    private String refType;

    @Schema(description = "资源原始编码")
    private String nativeCode;

    @Schema(description = "对上级暴露码：域10 + 类型3 + 序号7")
    private String exposedCode;

    @Schema(description = "类型段：200 设备 / 131 通道 / 215 业务分组 / 216 虚拟组织")
    private String typeSeg;

    @Schema(description = "平台内分配序号，删除不复用")
    private Long seqNo;

    @Schema(description = "ACTIVE / TOMBSTONE")
    private String state;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    public boolean isTombstone() {
        return STATE_TOMBSTONE.equals(state);
    }
}
