package com.solo.domain.share.support;

import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResDevice;
import com.solo.domain.share.model.OutGroup;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 出站事件的报文快照（3.9.3 的"出站必须携带全局身份"）。
 *
 * <p><b>为什么在写事件时就把字段快照下来</b>：撤销/删除之后 {@code out_share} 行还在，
 * 但资源可能已经 REMOVED、组也可能被清理；等投递时再回查会拿到空值，
 * 组装出的 DEL 报文因缺 {@code DeviceID}/{@code ParentID} 被上级忽略。</p>
 *
 * <p>字段只带"定位 + 名称 + 全局身份"，不带全量资源：payload 是报文素材，不是数据副本。</p>
 */
public final class SharePayloads {

    private SharePayloads() {
    }

    public static Map<String, Object> group(OutGroup group) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", "GROUP");
        map.put("code", group.getCode());
        map.put("name", group.getName());
        map.put("parentCode", group.getParentCode());
        map.put("groupType", group.getGroupType());
        map.put("level", group.getLevel());
        return map;
    }

    /**
     * 通道快照。
     *
     * @param mount       挂载的虚拟组织（决定 {@code ParentID}），可为 null（挂根）
     * @param exposedCode 对该上级的暴露码（报文里的 {@code DeviceID}）
     */
    public static Map<String, Object> channel(ResChannel channel, ResDevice device,
                                             OutGroup mount, String exposedCode) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", "CHANNEL");
        map.put("exposedCode", exposedCode);
        map.put("nativeCode", channel.getCode());
        map.put("name", channel.getName());
        map.put("parentCode", mount == null ? null : mount.getCode());
        map.put("parentId", mount == null ? null : String.valueOf(mount.getId()));
        map.put("orgPath", channel.getOrgPath());
        map.put("deviceCode", device == null ? null : device.getCode());
        map.put("deviceName", device == null ? null : device.getName());
        map.put("vendor", device == null ? null : device.getVendor());
        map.put("model", device == null ? null : device.getModel());
        map.put("deviceIp", device == null ? null : device.getDeviceIp());
        // 报文里的 Manufacturer/Model/Owner：缺失时上级会把整条 Item 判为非法，
        // 所以写事件时就把当时的值快照下来，投递阶段不再回查（资源可能已 REMOVED）
        map.put("manufacturer", device == null ? null : device.getVendor());
        map.put("owner", device == null ? null : device.getName());
        map.put("status", channel.getOnlineStatus() == null ? "ON" : channel.getOnlineStatus());
        // 全局身份（3.9.3）：恒带，只增不改
        map.put("originPlatformId", channel.getOriginPlatformId());
        map.put("originCode", channel.getOriginCode() == null ? channel.getCode() : channel.getOriginCode());
        map.put("originChain", channel.getOriginChain());
        map.put("sourcePlatformId", channel.getSourcePlatformId());
        map.put("hopCount", channel.getHopCount());
        return map;
    }
}
