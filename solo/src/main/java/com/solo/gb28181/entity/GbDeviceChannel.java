package com.solo.gb28181.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

@Schema(description = "国标设备通道")
@Data
@TableName("gb_device_channel")
public class GbDeviceChannel {

    @Schema(description = "主键 ID")
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "设备国标 ID")
    private String deviceId;

    @Schema(description = "来源接入平台国标 ID（发起 Catalog 的注册方；多下级平台据此区分通道归属，现阶段与 deviceId 同值）")
    private String platformId;

    @Schema(description = "组织归属覆盖值 gb_org.id；NULL=继承所属设备的 org_id，有效组织 = COALESCE(通道值, 设备值)")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orgId;

    @Schema(description = "通道国标 ID（20 位）")
    private String channelId;

    @Schema(description = "通道上报原始名称")
    private String reportName;

    @Schema(description = "自定义名称")
    private String name;

    @Schema(description = "厂商")
    private String manufacturer;

    @Schema(description = "型号")
    private String model;

    @Schema(description = "设备归属")
    private String owner;

    @Schema(description = "行政区划代码")
    private String civilCode;

    @Schema(description = "警区")
    private String block;

    @Schema(description = "安装地址")
    private String address;

    @Schema(description = "资源类型（0=通道，1=分组，无 NULL 语义）")
    private Integer parental;

    @Schema(description = "安全接入方式")
    private Integer safetyWay;

    @Schema(description = "注册方式")
    private Integer registerWay;

    @Schema(description = "证书编号")
    private String certNum;

    @Schema(description = "证书认证标识")
    private Integer certifiable;

    @Schema(description = "错误码")
    private Integer errCode;

    @Schema(description = "证书有效期止")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    @Schema(description = "保密等级")
    private Integer secrecy;

    @Schema(description = "IP 地址")
    private String ipAddress;

    @Schema(description = "端口")
    private Integer port;

    @Schema(description = "密码")
    private String password;

    @Schema(description = "在线状态（ON/OFF/UNREGISTERED，仅由 SIP 交互改变，仅通道）")
    private String status;

    @Schema(description = "经度（原样存取，不做坐标系转换；页面手填或设备/下级平台 Catalog 上报是什么就存什么）")
    private Double longitude;
    @Schema(description = "纬度，约定同经度：原样存取，不做坐标系转换")
    private Double latitude;

    @Schema(description = "云台类型：0 未知、1 球机、2 半球、3 固定枪机、4 遥控枪机（仅通道）")
    private Integer ptzType;

    @Schema(description = "父节点 ID")
    private String parentId;

    @Schema(description = "组织路径：/设备码/父码/子码；分组与通道都写，根为 /设备码/自身码，用于子树前缀查询")
    private String treePath;

    @Schema(description = "数据来源（1=系统新增/2=表格导入/3=设备注册，Catalog 同步自动入库）")
    private Integer source;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    @Schema(description = "对上级暴露的国标码")
    @TableField(exist = false)
    private String exposedCode;

    @Schema(description = "所属业务分组（215）国标码；仅级联推送 216 虚拟组织时回填，不落库")
    @TableField(exist = false)
    private String businessGroupId;

    // ---------------- 全局身份（3.9.3 / 3.10.3）：组报文用，不落库 ----------------
    // 落库的是 res_channel / sync_staging 上的 origin_* 四列；这里只是把它们带到 XML 层。
    // 出站恒带 OriginPlatformId + OriginCode：本地资源 = 本平台国标码 + 自身码（由 XML 兜底），
    // 入站/转发资源 = 逐跳透传过来的最上游身份，上游没给就只能诚实填"直接来源"。

    @Schema(description = "最上游平台国标码；NULL/空 = 本地资源，XML 兜底本平台国标码")
    @TableField(exist = false)
    private String originPlatformCode;

    @Schema(description = "最上游平台上的原始编码；NULL/空 = 本地资源，XML 兜底自身编码")
    @TableField(exist = false)
    private String originCode;

    @Schema(description = "来源链（平台国标码逗号串，最上游 → 直接来源）；空 = 未知")
    @TableField(exist = false)
    private String originChain;

    @Schema(description = "级联跳数；0/空 = 本地资源")
    @TableField(exist = false)
    private Integer hopCount;

    public String getExposedOrChannelId() {
        return (exposedCode != null && !exposedCode.isBlank()) ? exposedCode : channelId;
    }
}
