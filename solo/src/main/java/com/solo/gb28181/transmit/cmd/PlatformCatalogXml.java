package com.solo.gb28181.transmit.cmd;

import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.entity.GbParentPlatform;

import java.util.List;

/**
 * <p>本平台作为下级向上级回复 Catalog 查询时使用。与 {@link DeviceCommandXml} 分离：
 * 后者构造发往下级设备的短命令，本类构造回给上级的目录分片。</p>
 *
 * <p>Item 类型（按 GB/T 28181-2016）：由 {@code GbDeviceChannel.parental} 区分：</p>
 * <ul>
 *   <li><b>分组 Item</b>（parental=1）：{@code <DeviceID>}/{@code <Name>}/{@code <ParentID>}，
 *       固定 {@code <Parental>1</Parental>}，分组类型码 215 业务分组 / 216 虚拟组织
 *       （216 时额外补 {@code <BusinessGroupID>}），无 {@code <Status>}。</li>
 *   <li><b>通道 Item</b>（parental=0）：完整前端设备字段集，{@code <Parental>0</Parental>}，
 *       {@code <Status>} 由 {@code status} 映射（ON/OFF）。</li>
 * </ul>
 */
public final class PlatformCatalogXml {

    private PlatformCatalogXml() {}

    /**
     * 构造一片 Catalog Response XML。
     *
     * @param platform    上级平台配置；从 p 取 serverGbId 拼 URI 等
     * @param deviceGbId  本平台国标编号（从全局 gb28181.id 取，ADR-036）；Item 内部兜底 ParentID 用
     * @param sn          原请求的 SN，透传回去
     * @param sumNum      全量总条数（每片都填相同值）
     * @param items       本片中的 catalog item（分组 parental=1 + 通道 parental=0），调用方保证
     *                    分组排在通道之前
     */
    public static String buildResponse(GbParentPlatform platform, String deviceGbId, String sn, int sumNum,
                                       List<GbDeviceChannel> items) {
        String charset = platform.getCharacterSet() == null ? "GB2312" : platform.getCharacterSet();
        int itemNum = items.size();

        StringBuilder sb = new StringBuilder(600 + itemNum * 300);
        sb.append("<?xml version=\"1.0\" encoding=\"").append(charset).append("\"?>\r\n");
        sb.append("<Response>\r\n");
        sb.append("<CmdType>Catalog</CmdType>\r\n");
        sb.append("<SN>").append(sn).append("</SN>\r\n");
        sb.append("<DeviceID>").append(deviceGbId).append("</DeviceID>\r\n");
        sb.append("<SumNum>").append(sumNum).append("</SumNum>\r\n");
        appendDeviceList(sb, deviceGbId, items);
        sb.append("</Response>\r\n");
        return sb.toString();
    }

    /**
     * 构造单条 Catalog 变更事件 NOTIFY body（{@code SumNum=1}、{@code Num=1}）。
     *
     * <p>上级对 Catalog NOTIFY 是<b>增量 upsert</b> 语义：全量快照里没出现的节点它不会删，
     * 撤销必须显式下发 {@code Event=DEL}。因此变更只推受影响的这一个节点，一次一包。</p>
     *
     * @param event ADD / UPDATE / DEL / ON / OFF
     */
    public static String buildEventNotify(GbParentPlatform platform, String deviceGbId, String sn,
                                          GbDeviceChannel item, String event) {
        String charset = platform.getCharacterSet() == null ? "GB2312" : platform.getCharacterSet();
        StringBuilder sb = new StringBuilder(900);
        sb.append("<?xml version=\"1.0\" encoding=\"").append(charset).append("\"?>\r\n");
        sb.append("<Notify>\r\n");
        sb.append("<CmdType>Catalog</CmdType>\r\n");
        sb.append("<SN>").append(sn).append("</SN>\r\n");
        sb.append("<DeviceID>").append(deviceGbId).append("</DeviceID>\r\n");
        sb.append("<SumNum>1</SumNum>\r\n");
        appendDeviceList(sb, deviceGbId, List.of(item), event);
        sb.append("</Notify>\r\n");
        return sb.toString();
    }

    /**
     * 渲染 DeviceList 片段（分组 parental=1 + 通道 parental=0），供 Response 与 NOTIFY 复用。
     */
    public static void appendDeviceList(StringBuilder sb, String deviceGbId, List<GbDeviceChannel> items) {
        appendDeviceList(sb, deviceGbId, items, null);
    }

    /**
     * 带事件类型的 DeviceList 片段；{@code event} 为空时与三参版本完全等价。
     */
    public static void appendDeviceList(StringBuilder sb, String deviceGbId, List<GbDeviceChannel> items,
                                        String event) {
        sb.append("<DeviceList Num=\"").append(items.size()).append("\">\r\n");
        for (GbDeviceChannel it : items) {
            appendItem(sb, it, deviceGbId, event);
        }
        sb.append("</DeviceList>\r\n");
    }

    /**
     * 合并版 Item 渲染：按 {@code parental} 选字段集。
     * <p>分组（parental=1）：DeviceID = item.channelId（20 位国标码 215/216）；
     *   ParentID = item.parentId（父分组 GB 码；ROOT_PARENT 时兜底到 deviceGbId）。</p>
     * <p>通道（parental=0）：DeviceID 优先 exposed_code（平台唯一），回退 channelId；
     *   ParentID = item.parentId（父分组 GB 码；ROOT_PARENT 时兜底到 deviceGbId）。</p>
     */
    private static void appendItem(StringBuilder sb, GbDeviceChannel it, String deviceGbId, String event) {
        boolean isGroup = it.getParental() != null && it.getParental() == 1;
        String reportId;
        if (isGroup) {
            // 分组：直接用自身 GB 码
            reportId = it.getChannelId();
        } else {
            // 通道：暴露码 helper 统一回退（ADR-026 决策 1 配套保险）
            reportId = it.getExposedOrChannelId();
        }

        String parentCode = resolveParentCode(it.getParentId(), deviceGbId);

        sb.append("<Item>\r\n");
        sb.append("<DeviceID>").append(nullSafe(reportId)).append("</DeviceID>\r\n");
        sb.append("<Name>").append(escapeXml(it.getName())).append("</Name>\r\n");
        if (isGroup) {
            // 分组：固定 Manufacturer/Model/Owner
            sb.append("<Manufacturer>Solo</Manufacturer>\r\n");
            sb.append("<Model>Group</Model>\r\n");
            sb.append("<Owner>Solo</Owner>\r\n");
        } else {
            // 通道：业务字段
            appendTag(sb, "Manufacturer", it.getManufacturer());
            appendTag(sb, "Model", it.getModel());
            appendTag(sb, "Owner", it.getOwner());
        }
        // CivilCode 一律写父节点编码：海康 iSecure Center 按 CivilCode 归属区域，空值或与本级域
        // 不一致时会把整条 Item 判为无效——表现为「SIP 回 200、上级目录里却看不到节点」。
        appendTag(sb, "CivilCode", parentCode);
        appendTag(sb, "Address", it.getAddress());
        sb.append("<Parental>").append(isGroup ? 1 : 0).append("</Parental>\r\n");
        sb.append("<ParentID>").append(parentCode).append("</ParentID>\r\n");
        if (isGroup) {
            sb.append("<SafetyWay>0</SafetyWay>\r\n");
            sb.append("<RegisterWay>1</RegisterWay>\r\n");
            sb.append("<Secrecy>0</Secrecy>\r\n");
            sb.append("<Status>ON</Status>\r\n");
            int type = typeCode(it.getChannelId());
            if (type == 216) {
                // 216 虚拟组织必须带所属业务分组（215）编码：优先用加载时沿父链算出的祖先码，
                // 只有脏数据/缺父链时才回落本平台 ID（回落值不合规，但比缺字段稍好）
                String businessGroupId = it.getBusinessGroupId();
                sb.append("<BusinessGroupID>")
                        .append(businessGroupId != null && !businessGroupId.isBlank() ? businessGroupId : deviceGbId)
                        .append("</BusinessGroupID>\r\n");
            }
        } else {
            appendTag(sb, "SafetyWay", it.getSafetyWay());
            appendTag(sb, "RegisterWay", it.getRegisterWay() == null ? 1 : it.getRegisterWay());
            appendTag(sb, "Secrecy", it.getSecrecy() == null ? 0 : it.getSecrecy());
            appendTag(sb, "IPAddress", it.getIpAddress());
            appendTag(sb, "Port", it.getPort());
            appendTag(sb, "Password", it.getPassword());
            boolean online = "ON".equalsIgnoreCase(it.getStatus());
            sb.append("<Status>").append(online ? "ON" : "OFF").append("</Status>\r\n");
            appendTag(sb, "Longitude", it.getLongitude() == null ? 0 : it.getLongitude());
            appendTag(sb, "Latitude", it.getLatitude() == null ? 0 : it.getLatitude());
            appendPtzInfo(sb, it);
        }
        // 变更通知专用：GB/T 28181 的 Event 是 Item 的最后一个元素；全量快照（Response / 主动推送）
        // 不带本字段——上级若在快照里读不到已删除节点也不会回删，删除必须单独发 DEL
        if (event != null && !event.isBlank()) {
            sb.append("<Event>").append(event).append("</Event>\r\n");
        }
        sb.append("</Item>\r\n");
    }

    /**
     * 目录结构摘要（供日志核对层级）：{@code 类型:名称(DeviceID)←ParentID}，依次用 {@code |} 分隔。
     * <p>类型取 20 位码第 11-13 位：215 业务分组 / 216 虚拟组织 / 118 设备 / 132 通道。</p>
     */
    public static String describe(List<GbDeviceChannel> items) {
        if (items == null || items.isEmpty()) {
            return "空";
        }
        StringBuilder sb = new StringBuilder(items.size() * 64);
        for (GbDeviceChannel it : items) {
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            int type = typeCode(it.getChannelId());
            // 分组直接用自身码；设备/通道优先暴露码（与 appendItem 的 reportId 取值一致）
            String reportId = (type == TYPE_BUSINESS_GROUP || type == TYPE_VIRTUAL_ORG)
                    ? it.getChannelId() : it.getExposedOrChannelId();
            sb.append(type).append(':').append(it.getName())
                    .append('(').append(reportId).append(")←")
                    .append(resolveParentCode(it.getParentId(), "根"));
        }
        return sb.toString();
    }

    /**
     * 解析 item.parentId → XML 中实际写入的 &lt;ParentID&gt;：
     * <ul>
     *   <li>"0"（根哨兵）→ 兜底 deviceGbId（挂到平台自身节点下）</li>
     *   <li>其他值 → 直接用 item.parentId 自身（已是父 item 的 20 位 GB 码）</li>
     * </ul>
     */
    private static String resolveParentCode(String parentId, String deviceGbId) {
        if (parentId == null || ROOT_PARENT.equals(parentId)) {
            return deviceGbId;
        }
        return parentId;
    }

    /** 根哨兵——item.parent_id == "0" 表无父 */
    private static final String ROOT_PARENT = "0";

    /** 类型段：业务分组 */
    private static final int TYPE_BUSINESS_GROUP = 215;

    /** 类型段：虚拟组织 */
    private static final int TYPE_VIRTUAL_ORG = 216;

    /** 取 20 位国标码的类型段（第 11-13 位）；非法编码返回 0 */
    private static int typeCode(String gbCode) {
        if (gbCode == null || gbCode.length() < 13) return 0;
        try {
            return Integer.parseInt(gbCode.substring(10, 13));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 写一个可选元素：值为 null 或空白时整段省略。
     * <p>空标签（如 {@code <CivilCode></CivilCode>}）会被上级当成非法节点，
     * 一条 Item 里挂多个空标签时整条可能被丢弃。</p>
     */
    private static void appendTag(StringBuilder sb, String name, Object value) {
        if (value == null) {
            return;
        }
        String text = value instanceof String s ? s : String.valueOf(value);
        if (text.isBlank()) {
            return;
        }
        sb.append('<').append(name).append('>').append(escapeXml(text)).append("</").append(name).append(">\r\n");
    }

    /**
     * 通道的视频类型：GB/T 28181 的 {@code PTZType} 属于 {@code <Info>} 子元素，写在 Item 层
     * 上级会忽略；取不到时按 1（球机/摄像机）兜底——写 0 会让部分上级把监控点当成非视频通道，
     * 点播入口直接消失。分组节点不是视频设备，不写本段。
     */
    private static void appendPtzInfo(StringBuilder sb, GbDeviceChannel it) {
        Integer ptz = it.getPtzType();
        sb.append("<Info>\r\n");
        sb.append("<PTZType>").append(ptz == null || ptz == 0 ? 1 : ptz).append("</PTZType>\r\n");
        sb.append("</Info>\r\n");
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** 最小 XML 转义：只处理会破坏 XML 结构的 5 个字符 */
    private static String escapeXml(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
