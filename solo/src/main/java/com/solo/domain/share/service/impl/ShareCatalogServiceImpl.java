package com.solo.domain.share.service.impl;

import com.solo.domain.platform.model.Platform;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResDevice;
import com.solo.domain.resource.model.ResourceStatus;
import com.solo.domain.share.model.OutCodeMap;
import com.solo.domain.share.model.OutGroup;
import com.solo.domain.share.model.OutShare;
import com.solo.domain.share.model.ShareRefType;
import com.solo.domain.share.service.OutCodeService;
import com.solo.domain.share.service.ShareCatalogService;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.infra.mapper.OutGroupMapper;
import com.solo.infra.mapper.OutShareMapper;
import com.solo.infra.mapper.PlatformMapper;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.ResDeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上级视角的目录组装实现。
 *
 * <p><b>为什么必须先取暴露码再组报文</b>：报文里的 {@code DeviceID} 不是资源原始码，
 * 而是 {@code out_code_map} 里对这个上级分配的那一个。缺失映射的通道<b>不推</b>——
 * 用原始码顶上会让入站资源以"别人的域"出现在上级，比不推更难收拾。</p>
 *
 * <p>分组节点按 {@code path} 排序（父一定在子之前），通道统一排在分组之后：
 * 上级按顺序处理 Item，子节点先于父节点出现会被当成孤儿丢弃。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareCatalogServiceImpl implements ShareCatalogService {

    /** 类型段：业务分组 */
    private static final int TYPE_BUSINESS_GROUP = 215;
    /** 类型段：虚拟组织 */
    private static final int TYPE_VIRTUAL_ORG = 216;

    private final OutGroupMapper outGroupMapper;
    private final OutShareMapper outShareMapper;
    private final OutCodeService outCodeService;
    private final ResChannelMapper channelMapper;
    private final ResDeviceMapper deviceMapper;
    /** 最上游平台 id → 国标码：出站报文里的 OriginPlatformId 必须是国标码，对方不认识我们的库 id */
    private final PlatformMapper platformMapper;

    @Override
    public List<GbDeviceChannel> loadCatalogItems(Long platformId) {
        if (platformId == null) {
            return List.of();
        }
        List<OutShare> channelRows = visibleChannelRows(platformId);
        if (channelRows.isEmpty()) {
            return List.of();
        }

        Map<Long, ResChannel> channels = loadChannels(channelRows);
        Map<String, String> codeByNative = loadCodes(platformId, channelRows);
        Map<Long, ResDevice> devices = loadDevices(channels.values());
        // 全局身份（3.9.3）：出站恒带，本地资源的 originPlatformCode 留空由 XML 兜底本平台国标码
        Map<Long, String> originCodes = loadOriginCodes(channels.values());

        // 只有"有通道落位"的分组及其祖先才进报文：空分组在上级就是一条永远填不上的空目录
        Map<Long, OutGroup> groupById = new LinkedHashMap<>();
        for (OutGroup group : outGroupMapper.selectByPlatform(platformId)) {
            groupById.put(group.getId(), group);
        }
        Set<Long> needed = new HashSet<>();
        for (OutShare row : channelRows) {
            Long gid = row.getGroupId();
            int guard = 0;
            while (gid != null && gid > 0 && guard++ < 64) {
                if (!needed.add(gid)) {
                    break;
                }
                OutGroup group = groupById.get(gid);
                gid = group == null ? null : group.getParentId();
            }
        }

        List<GbDeviceChannel> result = new ArrayList<>(needed.size() + channelRows.size());
        for (OutGroup group : groupById.values()) {
            if (needed.contains(group.getId())) {
                result.add(groupItem(group, groupById));
            }
        }
        result.sort(Comparator
                .comparingInt((GbDeviceChannel c) -> c.getParental() != null && c.getParental() == 1 ? 0 : 1)
                .thenComparingInt(c -> c.getParental() != null && c.getParental() == 1
                        ? levelOf(c) : 0)
                .thenComparing(GbDeviceChannel::getChannelId, Comparator.nullsLast(String::compareTo)));

        for (OutShare row : channelRows) {
            ResChannel channel = channels.get(row.getRefId());
            String exposed = codeByNative.get(row.getRefCode());
            if (channel == null || !StringUtils.hasText(exposed)) {
                continue;
            }
            OutGroup mount = row.getGroupId() == null ? null : groupById.get(row.getGroupId());
            result.add(channelItem(channel, devices.get(channel.getDeviceId()), mount, exposed, originCodes));
        }
        return result;
    }

    @Override
    public List<GbDeviceChannel> loadExposedChannels(Long platformId) {
        if (platformId == null) {
            return List.of();
        }
        List<OutShare> rows = visibleChannelRows(platformId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, ResChannel> channels = loadChannels(rows);
        Map<String, String> codeByNative = loadCodes(platformId, rows);
        List<GbDeviceChannel> out = new ArrayList<>(rows.size());
        for (OutShare row : rows) {
            ResChannel channel = channels.get(row.getRefId());
            String exposed = codeByNative.get(row.getRefCode());
            if (channel == null || !StringUtils.hasText(exposed)) {
                continue;
            }
            GbDeviceChannel item = new GbDeviceChannel();
            item.setId(channel.getId());
            item.setChannelId(channel.getCode());
            item.setExposedCode(exposed);
            item.setName(displayName(channel));
            item.setParental(0);
            item.setStatus(channel.getOnlineStatus());
            item.setDeviceId(channel.getDeviceId() == null ? null : String.valueOf(channel.getDeviceId()));
            out.add(item);
        }
        return out;
    }

    @Override
    public ExposedChannel resolveForPlay(Long platformId, String upstreamCode) {
        if (platformId == null || !StringUtils.hasText(upstreamCode)) {
            return null;
        }
        String code = upstreamCode.trim();
        OutCodeMap mapping = outCodeService.resolve(platformId, code);
        if (mapping == null || !ShareRefType.CHANNEL.equals(mapping.getRefType())
                || mapping.isTombstone()) {
            return null;
        }
        OutShare row = outShareMapper.selectVisibleByCode(platformId, mapping.getNativeCode());
        if (row == null || row.getRefId() == null) {
            return null;
        }
        ResChannel channel = channelMapper.selectById(row.getRefId());
        if (channel == null || ResourceStatus.REMOVED.equals(channel.getStatus())) {
            return null;
        }
        ResDevice device = channel.getDeviceId() == null ? null : deviceMapper.selectById(channel.getDeviceId());
        return new ExposedChannel(channel, device, code);
    }

    // ------------------------------------------------------------------

    private List<OutShare> visibleChannelRows(Long platformId) {
        List<OutShare> usable = outShareMapper.selectUsable(platformId);
        List<OutShare> rows = new ArrayList<>(usable.size());
        for (OutShare row : usable) {
            if (ShareRefType.CHANNEL.equals(row.getRefType())) {
                rows.add(row);
            }
        }
        return rows;
    }

    private Map<Long, ResChannel> loadChannels(List<OutShare> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (OutShare row : rows) {
            if (row.getRefId() != null) {
                ids.add(row.getRefId());
            }
        }
        Map<Long, ResChannel> out = new HashMap<>(ids.size());
        if (ids.isEmpty()) {
            return out;
        }
        for (ResChannel channel : channelMapper.selectBatchIds(ids)) {
            if (!ResourceStatus.REMOVED.equals(channel.getStatus())) {
                out.put(channel.getId(), channel);
            }
        }
        return out;
    }

    private Map<Long, ResDevice> loadDevices(Iterable<ResChannel> channels) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ResChannel channel : channels) {
            if (channel.getDeviceId() != null) {
                ids.add(channel.getDeviceId());
            }
        }
        Map<Long, ResDevice> out = new HashMap<>(ids.size());
        if (ids.isEmpty()) {
            return out;
        }
        for (ResDevice device : deviceMapper.selectBatchIds(ids)) {
            out.put(device.getId(), device);
        }
        return out;
    }

    private Map<String, String> loadCodes(Long platformId, List<OutShare> rows) {
        Set<String> natives = new LinkedHashSet<>();
        for (OutShare row : rows) {
            if (StringUtils.hasText(row.getRefCode())) {
                natives.add(row.getRefCode().trim());
            }
        }
        Map<String, String> out = new HashMap<>(natives.size());
        if (natives.isEmpty()) {
            return out;
        }
        Map<String, String> mapped = outCodeService.exposedCodesOf(platformId, ShareRefType.CHANNEL, natives);
        for (Map.Entry<String, String> entry : mapped.entrySet()) {
            if (StringUtils.hasText(entry.getValue())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private GbDeviceChannel groupItem(OutGroup group, Map<Long, OutGroup> groupById) {
        GbDeviceChannel item = new GbDeviceChannel();
        item.setChannelId(group.getCode());
        item.setName(group.getName());
        item.setParentId(parentCodeOf(group));
        item.setParental(1);
        item.setStatus("ON");
        item.setTreePath(group.getPath());
        if (typeCode(group.getCode()) == TYPE_VIRTUAL_ORG) {
            item.setBusinessGroupId(businessGroupCode(group, groupById));
        }
        return item;
    }

    /**
     * 批载来源平台国标码：{@code origin_platform_id}（最上游）与 {@code source_platform_id}
     * （直接来源）一起取，后者用在"上游没告诉我们最上游是谁"的降级场景。
     */
    private Map<Long, String> loadOriginCodes(Iterable<ResChannel> channels) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ResChannel channel : channels) {
            addPlatformId(ids, channel.getOriginPlatformId());
            addPlatformId(ids, channel.getSourcePlatformId());
        }
        Map<Long, String> out = new HashMap<>(ids.size());
        if (ids.isEmpty()) {
            return out;
        }
        for (Platform platform : platformMapper.selectBatchIds(ids)) {
            if (platform != null && StringUtils.hasText(platform.getCode())) {
                out.put(platform.getId(), platform.getCode().trim());
            }
        }
        return out;
    }

    private static void addPlatformId(Set<Long> ids, Long id) {
        if (id != null && id > 0) {
            ids.add(id);
        }
    }

    private GbDeviceChannel channelItem(ResChannel channel, ResDevice device,
                                        OutGroup mount, String exposed, Map<Long, String> originCodes) {
        GbDeviceChannel item = new GbDeviceChannel();
        item.setId(channel.getId());
        item.setChannelId(channel.getCode());
        item.setExposedCode(exposed);
        item.setName(displayName(channel));
        item.setParental(0);
        item.setParentId(mount == null ? null : mount.getCode());
        item.setStatus(channel.getOnlineStatus() == null ? "OFF" : channel.getOnlineStatus());
        item.setSafetyWay(0);
        item.setRegisterWay(1);
        item.setSecrecy(0);
        item.setPtzType(1);
        if (device != null) {
            item.setManufacturer(device.getVendor());
            item.setModel(device.getModel());
            item.setOwner(device.getName());
            item.setIpAddress(device.getDeviceIp());
        }
        // CivilCode 取父节点编码：部分上级按它归属区域，空值会让整条 Item 被判无效
        item.setCivilCode(mount == null ? null : mount.getCode());
        // 全局身份（3.9.3）：最上游平台码优先，缺失时退到直接来源平台码（诚实降级）；
        // 本地资源两者都是 0，留空由 XML 兜底本平台国标码 + 自身码。
        Long originId = channel.getOriginPlatformId() == null || channel.getOriginPlatformId() <= 0
                ? channel.getSourcePlatformId() : channel.getOriginPlatformId();
        item.setOriginPlatformCode(originId == null ? null : originCodes.get(originId));
        item.setOriginCode(channel.getOriginCode());
        item.setOriginChain(channel.getOriginChain());
        item.setHopCount(channel.getHopCount());
        return item;
    }

    private static String displayName(ResChannel channel) {
        if (!StringUtils.hasText(channel.getLocalName())) {
            return channel.getName();
        }
        return channel.getLocalName();
    }

    /** 父分组码；根节点返回 null（XML 层会兜底到本平台国标码） */
    private static String parentCodeOf(OutGroup group) {
        return StringUtils.hasText(group.getParentCode()) ? group.getParentCode() : null;
    }

    /** 216 虚拟组织必须带所属 215 业务分组编码：沿父链上溯第一个 215 */
    private static String businessGroupCode(OutGroup self, Map<Long, OutGroup> groupById) {
        Long cursor = self.getParentId();
        int guard = 0;
        while (cursor != null && cursor > 0 && guard++ < 64) {
            OutGroup parent = groupById.get(cursor);
            if (parent == null) {
                return null;
            }
            if (typeCode(parent.getCode()) == TYPE_BUSINESS_GROUP) {
                return parent.getCode();
            }
            cursor = parent.getParentId();
        }
        return null;
    }

    private static int levelOf(GbDeviceChannel item) {
        String path = item.getTreePath();
        if (path == null) {
            return 0;
        }
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    private static int typeCode(String code) {
        if (code == null || code.length() < 13) {
            return 0;
        }
        try {
            return Integer.parseInt(code.substring(10, 13));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
