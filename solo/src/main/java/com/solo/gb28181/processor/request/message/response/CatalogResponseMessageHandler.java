package com.solo.gb28181.processor.request.message.response;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.entity.GbDevice;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.event.catalog.CatalogSyncProgressEvent;
import com.solo.gb28181.inbound.Gb28181CatalogTap;
import com.solo.gb28181.processor.request.message.MessageHandler;
import com.solo.gb28181.service.GbDeviceChannelService;
import com.solo.gb28181.service.GbDeviceService;
import com.solo.gb28181.support.CatalogSyncContext;
import com.solo.gb28181.support.CatalogSyncProgress;
import com.solo.gb28181.support.CatalogSyncStatusService;
import com.solo.gb28181.utils.SipUtils;
import com.solo.gb28181.utils.XmlUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dom4j.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.sip.RequestEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogResponseMessageHandler implements MessageHandler {

    private final CatalogDataCache cache;
    private final GbDeviceChannelService channelService;
    private final GbDeviceService deviceService;
    private final ApplicationEventPublisher eventPublisher;
    /** 本平台国标编码（接收侧身份），日志里与本下级平台的 deviceId 对照打印 */
    private final Gb28181Properties gb28181Properties;
    /** 新资源同步链路的投递口（旁路：没有活跃轮次时它是空转的） */
    private final Gb28181CatalogTap inboundCatalogTap;

    @Lazy
    @Autowired
    private CatalogSyncStatusService catalogSyncStatusService;

    @Override
    public String getRootType() {
        return "Response";
    }

    @Override
    public String getCmdType() {
        return "Catalog";
    }

    @Override
    public void handleForDevice(RequestEvent event, GbDevice device, Element root) {
        int sn = parseInt(XmlUtils.childText(root, "SN"), -1);
        int sumNum = parseInt(XmlUtils.childText(root, "SumNum"), 0);

        // 诊断：打印响应报文原文（截断），用于确认对端到底推的是目录节点还是通道节点
        if (log.isInfoEnabled() && event.getRequest().getRawContent() != null) {
            String raw = decodeRaw(event.getRequest().getRawContent());
            log.info("[通道目录] 收到响应 localId={} deviceId={} sn={} sumNum={} 报文长度={} 内容={}",
                    gb28181Properties.getId(), device.getDeviceId(), sn, sumNum, raw.length(),
                    raw.length() > 1500 ? raw.substring(0, 1500) + "..." : raw);
        }
        if (sn < 0) {
            log.warn("[通道目录] 缺失 SN，丢弃 deviceId={}", device.getDeviceId());
            return;
        }

        if (sumNum == 0) {
            log.info("[通道目录] 设备无通道 deviceId={}", device.getDeviceId());
            // 新链路（资源同步）也要知道"本轮收齐了"，否则它会一直等不到 SumNum
            inboundCatalogTap.onEmptyCatalog(device.getDeviceId());
            // 设备通告空目录：清理并记录 lastCatalogSyncTime（只更这一个字段，避免覆盖并发写入的 DeviceInfo）
            channelService.deleteByDeviceId(device.getDeviceId());
            deviceService.update(new LambdaUpdateWrapper<GbDevice>()
                    .eq(GbDevice::getId, device.getId())
                    .set(GbDevice::getLastCatalogSyncTime, java.time.LocalDateTime.now()));
            // 释放同步锁（空目录不走 finalize，手动释放避免 5 分钟死锁）
            catalogSyncStatusService.release(device.getDeviceId());
            // 直接发布 SUCCESS 收尾事件。不能构造 done=true 的 stub 调 cache.finalize：
            // finalize 对 ctx.done 会直接短路，收尾事件发不出去，前端会一直停在 RUNNING。
            long now = System.currentTimeMillis();
            CatalogSyncProgress emptyDone = new CatalogSyncProgress(
                    device.getDeviceId(), CatalogSyncProgress.STATUS_SUCCESS,
                    0, 0, 100.0, now, now,
                    LocalDateTime.now().format(FINISH_TIME_FORMATTER), null, 0);
            eventPublisher.publishEvent(new CatalogSyncProgressEvent(emptyDone));
            return;
        }

        Element deviceList = root.element("DeviceList");
        if (deviceList == null) {
            log.warn("[通道目录] 报文无 DeviceList 节点 deviceId={}", device.getDeviceId());
            return;
        }

        // 启动 / 复用同步上下文
        CatalogSyncContext ctx = cache.startSync(device.getDeviceId(), sn, sumNum);
        if (ctx == null) {
            return;
        }

        // 遍历 Item，按 ID 长度 + 类型码 双信号分流，所有 item 进同一缓冲
        List<GbDeviceChannel> chunkItems = new ArrayList<>();
        Iterator<Element> it = deviceList.elementIterator("Item");
        while (it.hasNext()) {
            Element item = it.next();
            String itemId = XmlUtils.childText(item, "DeviceID");
            if (itemId == null || itemId.isBlank()) {
                continue;
            }

            int len = itemId.length();
            // ID 长度规则：20 位走 GB 标准类型码分流；≤8 位按 GB/T 2260 行政码当目录；
            // 9-19 位既不是合法行政码也不是标准 GB ID，丢弃
            if (len != 20 && len > 8) {
                log.warn("[通道目录] 异常 ID 长度 len={} deviceId={}，丢弃", len, itemId);
                continue;
            }

            int typeCode = (len >= 13) ? SipUtils.getTypeCodeFromGbCode(itemId) : 0;
            int parental = parseParental(item);

            boolean isDirectoryType;
            if (len <= 8) {
                // 6-8 位行政码 → 强制目录
                isDirectoryType = true;
            } else {
                // 20 位标准 GB ID：200/215/216 是目录；200-214 范围内 Parental=1 也按目录
                // （200+ 是中心设备类，海康相机不会用，冲突面小）。
                // 注意：故意不收 130-198 + Parental=1：海康相机普遍把 <Parental> 错填成 65535，
                // 兜底成 1 后会被这条规则误吞进 parental=1，导致海康相机的通道完全没入库。
                isDirectoryType = (typeCode == 200 || typeCode == 215 || typeCode == 216)
                        || (typeCode >= 200 && typeCode <= 214 && parental == 1);
            }

            // ParentID：空串 → 设备自身 DeviceID（让"顶层 215/216"挂在设备节点下，而非根）
            // 部分级联平台（海康）会填 "平台编码/父节点编码" 的路径式值，直接入库会超出
            // parent_id 列宽并让整批 upsert 失败，这里先归一化再决定落库值
            String rawParentId = XmlUtils.childText(item, "ParentID");
            String parentId = normalizeParentId(rawParentId);
            String effectiveParentId = parentId == null ? device.getDeviceId() : parentId;

            // 诊断：打印每个 Item 的判定结果，确认设备/通道分流是否符合预期
            if (log.isInfoEnabled()) {
                log.info("[通道目录] item localId={} deviceId={} id={} name={} parentId={} rawParentId={} parental={} typeCode={} isDirectory={}",
                        gb28181Properties.getId(), device.getDeviceId(), itemId, XmlUtils.childText(item, "Name"),
                        effectiveParentId, rawParentId, parental, typeCode, isDirectoryType);
            }

            GbDeviceChannel itemEntity = parseItem(item, device, effectiveParentId, isDirectoryType);
            if (itemEntity != null) {
                chunkItems.add(itemEntity);
            }
        }

        // 本片 DeviceList 声明条数（收尾诊断用：声明合计 < SumNum 即设备少发分片 / UDP 丢片）
        Integer declaredNum = parseIntOrNull(deviceList.attributeValue("Num"));
        int parsedNum = chunkItems.size();
        // 诊断：报文声明 Num vs 实际解析条数，不等说明解析漏了（编码 / 字段名 / 嵌套异常，含非法 ID 被丢弃）
        if (log.isInfoEnabled() && (declaredNum == null || declaredNum != parsedNum)) {
            log.info("[通道目录] 分片计数不一致 deviceId={} sn={} 报文声明Num={} 实际解析={} sumNum={}",
                    device.getDeviceId(), sn, declaredNum, parsedNum, sumNum);
        }

        // 累加分片（内部阈值 flush；sumNum 收齐时返回 true）
        boolean complete = cache.accumulate(ctx, chunkItems, declaredNum);

        // 旁路投递到资源同步链路：放在旧链路落库之后、判定之前，旧链路行为不变；
        // 没有活跃轮次时 onBatch 直接返回（空转）
        inboundCatalogTap.onBatch(device.getDeviceId(), sumNum, declaredNum, chunkItems);

        if (complete) {
            // 收齐分支已在 accumulate 内 finalize，这里仅记录日志
            log.info("[通道目录] 本片触发收齐 deviceId={} sn={}", device.getDeviceId(), sn);
        }
    }

    private static final DateTimeFormatter FINISH_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ---------------- Item 解析（ADR-023 合并版） ----------------

    /**
     * 解析单个 Item 为 GbDeviceChannel：parental=1 表分组，parental=0 表通道。
     * 分组与通道都必填 deviceId——分组标识推送源设备（每个设备/平台有自己的 group 树）。
     * parentId 存报文 <ParentID> 字段，供级联回 XML 与共享树建层级使用。
     */
    private GbDeviceChannel parseItem(Element item, GbDevice device, String parentId, boolean isDirectory) {
        String channelId = XmlUtils.childText(item, "DeviceID");
        if (channelId == null || channelId.isBlank()) return null;

        GbDeviceChannel ch = new GbDeviceChannel();
        ch.setChannelId(channelId);
        ch.setName(emptyToNull(XmlUtils.childText(item, "Name")));
        ch.setParentId(parentId);
        ch.setParental(isDirectory ? 1 : 0);
        // 分组与通道都必填 deviceId：分组标识推送源设备；platformId 固定记接入平台（发起本次目录的注册方），
        // 将来若把 deviceId 细化为下属子设备码，仍能靠 platformId 区分是哪个下级平台推来的
        ch.setDeviceId(device.getDeviceId());
        ch.setPlatformId(device.getDeviceId());

        if (!isDirectory) {
            // 通道：完整业务字段
            ch.setManufacturer(emptyToNull(XmlUtils.childText(item, "Manufacturer")));
            ch.setModel(emptyToNull(XmlUtils.childText(item, "Model")));
            ch.setOwner(emptyToNull(XmlUtils.childText(item, "Owner")));
            ch.setCivilCode(emptyToNull(XmlUtils.childText(item, "CivilCode")));
            ch.setBlock(emptyToNull(XmlUtils.childText(item, "Block")));
            ch.setAddress(emptyToNull(XmlUtils.childText(item, "Address")));
            ch.setSafetyWay(parseInt(XmlUtils.childText(item, "SafetyWay"), 0));
            ch.setRegisterWay(parseInt(XmlUtils.childText(item, "RegisterWay"), 1));
            ch.setCertNum(emptyToNull(XmlUtils.childText(item, "CertNum")));
            ch.setCertifiable(parseInt(XmlUtils.childText(item, "Certifiable"), 0));
            ch.setErrCode(parseIntOrNull(XmlUtils.childText(item, "ErrCode")));
            ch.setSecrecy(parseInt(XmlUtils.childText(item, "Secrecy"), 0));
            ch.setIpAddress(emptyToNull(XmlUtils.childText(item, "IPAddress")));
            ch.setPort(parseIntOrNull(XmlUtils.childText(item, "Port")));
            ch.setPassword(emptyToNull(XmlUtils.childText(item, "Password")));
            String status = XmlUtils.childText(item, "Status");
            ch.setStatus(status == null || status.isBlank() ? "OFF" : status.toUpperCase());
            ch.setLongitude(parseDouble(XmlUtils.childText(item, "Longitude")));
            ch.setLatitude(parseDouble(XmlUtils.childText(item, "Latitude")));

            Element info = item.element("Info");
            if (info != null) {
                ch.setPtzType(parseInt(XmlUtils.childText(info, "PTZType"), 0));
            }
            // 全局身份（3.9.3）：本项目把扩展元素放在 <Info> 内，个别厂商会写在外层，两层都读。
            // 解析不出就保持空——由投递口按"直接来源"降级，绝不猜测上游身份。
            ch.setOriginPlatformCode(infoText(item, info, "OriginPlatformId"));
            ch.setOriginCode(infoText(item, info, "OriginCode"));
            ch.setOriginChain(infoText(item, info, "OriginChain"));
            ch.setHopCount(parseIntOrNull(infoText(item, info, "HopCount")));
        } else {
            // 分组：只保留 name / parent_id + status="ON"（分组通常"在线"）
            ch.setStatus("ON");
        }
        return ch;
    }

    // ---------------- 辅助 ----------------

    /**
     * 归一化 ParentID：兼容 {@code "平台编码/父节点编码"} 的路径式写法（取最后一段非空段）。
     * <p>海康等平台在部分分组下会把 ParentID 填成 41 位路径串，直接落 varchar(20) 的
     * parent_id 会让整批 upsert 抛 Data too long、整片数据全部丢失。归一化后仍超长的
     * 视为非法值返回 null，由调用方回落到设备自身节点。</p>
     */
    private static String normalizeParentId(String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return null;
        }
        String normalized = parentId.trim();
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1).trim();
        }
        if (normalized.isEmpty() || normalized.length() > MAX_PARENT_ID_LENGTH) {
            return null;
        }
        return normalized;
    }

    /** parent_id 列宽上限（varchar(64)），超长视为非法值 */
    private static final int MAX_PARENT_ID_LENGTH = 64;

    /** 按报文 XML 声明里的 encoding 解码原始字节；探测失败时回落 GB18030（GB2312 超集）。 */
    private static String decodeRaw(byte[] raw) {
        String head = new String(raw, 0, Math.min(raw.length, 200), java.nio.charset.StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("encoding\\s*=\\s*\"([^\"]+)\"", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(head);
        String charsetName = m.find() ? m.group(1) : "GB18030";
        try {
            return new String(raw, charsetName);
        } catch (Exception e) {
            return new String(raw, java.nio.charset.Charset.forName("GB18030"));
        }
    }

    /**
     * 解析 {@code Parental} 字段，对异常值做兜底。
     * <p>部分 NVR 厂商会把 {@code Parental} 错填成 65535（应填 0/1），按"非 '0' 一律算 1"处理。
     * 路由判定（{@link #handleForDevice} 的目录/通道分流）和字段存储（{@link #parseItem}）
     * 共用此方法，保证两者行为一致。</p>
     */
    private static int parseParental(Element item) {
        String s = XmlUtils.childText(item, "Parental");
        if (s == null || s.isBlank()) return 0;
        try {
            return Integer.parseInt(s.trim()) == 0 ? 0 : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 读一个扩展元素：优先 {@code <Info>} 内，其次 Item 层（厂商写法不一）。
     * 显式允许 {@code info == null}：不带 Info 的报文也要能读出外层扩展。
     */
    private static String infoText(Element item, Element info, String tag) {
        String value = info == null ? null : emptyToNull(XmlUtils.childText(info, tag));
        return value != null ? value : emptyToNull(XmlUtils.childText(item, tag));
    }
}
