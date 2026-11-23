package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 通道新增 / 编辑入参。
 *
 * <p>{@code orgId} 与 {@code deviceId} 双必填：前者是"位置"，后者是"取流载体"，
 * 缺任何一个通道都无法在树上下钻、也无法播放（架构约束 C4）。</p>
 */
@Schema(description = "通道保存入参")
@Data
public class ChannelSaveRequest {

    @Schema(description = "通道编码（建立后不可改——业务键）")
    @NotBlank(message = "通道编码不能为空")
    @Size(max = 64, message = "通道编码不能超过 64 字符")
    private String code;

    @Schema(description = "通道名称")
    @NotBlank(message = "通道名称不能为空")
    @Size(max = 128, message = "通道名称不能超过 128 字符")
    private String name;

    @Schema(description = "所属组织 id（必填）")
    @NotNull(message = "所属组织不能为空")
    private Long orgId;

    @Schema(description = "所属设备 id（必填）")
    @NotNull(message = "所属设备不能为空")
    private Long deviceId;

    @Schema(description = "主/子码流 MAIN/SUB/THIRD，默认 MAIN")
    @Size(max = 16, message = "码流类型不能超过 16 字符")
    private String streamType;

    @Schema(description = "能力位串 PTZ/RECORD/AUDIO/TALK，多个用逗号分隔")
    @Size(max = 128, message = "能力位串不能超过 128 字符")
    private String capability;

    @Schema(description = "取流线索（上游通道码/RTSP 模板），禁止写账号密码")
    @Size(max = 512, message = "取流线索不能超过 512 字符")
    private String mediaHint;

    @Schema(description = "本地别名")
    @Size(max = 128, message = "本地别名不能超过 128 字符")
    private String localName;
}
