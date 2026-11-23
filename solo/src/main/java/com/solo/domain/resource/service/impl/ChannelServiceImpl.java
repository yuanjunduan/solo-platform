package com.solo.domain.resource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solo.api.resource.dto.ChannelListItem;
import com.solo.api.resource.dto.ChannelSaveRequest;
import com.solo.api.resource.dto.ResourcePageRequest;
import com.solo.common.ServiceException;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResDevice;
import com.solo.domain.resource.model.ResOrg;
import com.solo.domain.resource.model.ResourceStatus;
import com.solo.domain.resource.model.SourceType;
import com.solo.domain.resource.service.ChannelService;
import com.solo.domain.resource.support.ReadOnlyGuard;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.ResDeviceMapper;
import com.solo.infra.mapper.ResOrgMapper;
import com.solo.infra.mapper.projection.CanonicalGroupCount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通道管理实现。
 *
 * <p>位置（org）与载体（device）都必填且都校验存在性：通道少任何一个都无法定位，
 * 也就无法播放、无法在树上展示（架构约束 C4）。</p>
 *
 * <p>{@code org_path} 跟着所属组织走，不跟着设备走——设备换组时通道的位置不变，
 * 这是刻意的：位置是用户摆的，载体是技术事实，两者不该被绑死。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelServiceImpl implements ChannelService {

    private static final String LOG_TAG = "资源通道";
    private static final Long NATIVE_PLATFORM_ID = 0L;
    private static final List<String> STREAM_TYPES = List.of("MAIN", "SUB", "THIRD");

    private final ResChannelMapper channelMapper;
    private final ResDeviceMapper deviceMapper;
    private final ResOrgMapper orgMapper;
    private final ReadOnlyGuard readOnlyGuard;

    @Override
    public Page<ResChannel> pageQuery(ResourcePageRequest req) {
        long pageNo = req.getPage() == null || req.getPage() < 1 ? 1L : req.getPage();
        long pageSize = req.getPageSize() == null || req.getPageSize() < 1 ? 20L : req.getPageSize();

        LambdaQueryWrapper<ResChannel> w = new LambdaQueryWrapper<>();
        String keyword = trimToNull(req.getKeyword());
        if (keyword != null) {
            w.and(q -> q.like(ResChannel::getCode, keyword)
                    .or().like(ResChannel::getName, keyword));
        }
        if (req.getOrgId() != null) {
            ResOrg org = orgMapper.selectById(req.getOrgId());
            if (org == null) {
                throw new ServiceException("组织不存在");
            }
            if (req.isIncludeSubtree()) {
                w.likeRight(ResChannel::getOrgPath, org.getPath());
            } else {
                w.eq(ResChannel::getOrgId, org.getId());
            }
        }
        if (req.getDeviceId() != null) {
            w.eq(ResChannel::getDeviceId, req.getDeviceId());
        }
        if (trimToNull(req.getSourceType()) != null) {
            w.eq(ResChannel::getSourceType, req.getSourceType().trim());
        }
        if (trimToNull(req.getStatus()) != null) {
            w.eq(ResChannel::getStatus, req.getStatus().trim());
        } else {
            w.ne(ResChannel::getStatus, ResourceStatus.REMOVED);
        }
        w.orderByAsc(ResChannel::getOrgPath).orderByAsc(ResChannel::getCode);

        return channelMapper.selectPage(new Page<>(pageNo, pageSize), w);
    }

    @Override
    public ResChannel detail(Long id) {
        return requireChannel(id);
    }

    @Override
    public Page<ChannelListItem> pageList(ResourcePageRequest req) {
        Page<ResChannel> page = pageQuery(req);
        List<ResChannel> records = page.getRecords();

        // 只按本页出现的 id 批量查名字：一页 50 条最多两次 IN，不会随总量放大
        Set<Long> deviceIds = records.stream()
                .map(ResChannel::getDeviceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> deviceNames = deviceIds.isEmpty() ? Map.of()
                : deviceMapper.selectBatchIds(deviceIds).stream()
                        .collect(Collectors.toMap(ResDevice::getId,
                                d -> displayName(d.getLocalName(), d.getName()),
                                (a, b) -> a));

        Set<Long> orgIds = records.stream()
                .map(ResChannel::getOrgId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> orgNames = orgIds.isEmpty() ? Map.of()
                : orgMapper.selectBatchIds(orgIds).stream()
                        .collect(Collectors.toMap(ResOrg::getId,
                                o -> displayName(o.getLocalName(), o.getName()),
                                (a, b) -> a));

        // 同源组计数（3.10.3）：按本页出现的组主批量数一次，前端用它渲染"同源 N"徽标
        Set<Long> leaderIds = records.stream()
                .map(ChannelServiceImpl::leaderIdOf)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Long> sourceCounts = leaderIds.isEmpty() ? Map.of()
                : channelMapper.countByGroupLeaders(leaderIds).stream()
                        .collect(Collectors.toMap(CanonicalGroupCount::getGroupId,
                                c -> c.getTotal() == null ? 1L : c.getTotal(),
                                (a, b) -> a));

        List<ChannelListItem> rows = records.stream().map(c -> {
            ChannelListItem item = new ChannelListItem();
            item.setChannel(c);
            item.setDeviceName(c.getDeviceId() == null ? null : deviceNames.get(c.getDeviceId()));
            item.setOrgName(c.getOrgId() == null ? null : orgNames.get(c.getOrgId()));
            Long leaderId = leaderIdOf(c);
            item.setSourceGroupLeaderId(leaderId);
            item.setSourceCount(sourceCounts.getOrDefault(leaderId, 1L));
            return item;
        }).toList();

        Page<ChannelListItem> out = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        out.setRecords(rows);
        return out;
    }

    @Override
    @Transactional
    public ResChannel create(ChannelSaveRequest req) {
        String code = req.getCode().trim();
        if (channelMapper.selectByBizKey(SourceType.NATIVE, NATIVE_PLATFORM_ID, code) != null) {
            throw new ServiceException(400, "通道编码已存在：" + code);
        }
        ResOrg org = requireWritableOrg(req.getOrgId(), "在该组织下新增通道");
        ResDevice device = requireWritableDevice(req.getDeviceId(), "把通道挂到该设备");

        ResChannel channel = new ResChannel();
        channel.setId(IdWorker.getId());
        channel.setCode(code);
        channel.setName(req.getName().trim());
        channel.setLocalName(trimToNull(req.getLocalName()));
        channel.setOrgId(org.getId());
        channel.setOrgPath(org.getPath());
        channel.setDeviceId(device.getId());
        channel.setStreamType(normalizeStreamType(req.getStreamType()));
        channel.setOnlineStatus("OFF");
        channel.setCapability(req.getCapability() == null ? "" : req.getCapability().trim());
        channel.setMediaHint(trimToNull(req.getMediaHint()));
        channel.setSourceType(SourceType.NATIVE);
        channel.setSourcePlatformId(NATIVE_PLATFORM_ID);
        channel.setOriginPlatformId(NATIVE_PLATFORM_ID);
        channel.setOriginCode(code);
        channel.setOriginChain("");
        channel.setHopCount(0);
        channel.setCanonicalId(0L);
        channel.setStatus(ResourceStatus.ACTIVE);
        channelMapper.insert(channel);

        log.info("[{}] 新建通道 id={} code={} name={} orgId={} deviceId={}",
                LOG_TAG, channel.getId(), code, channel.getName(), org.getId(), device.getId());
        return channel;
    }

    @Override
    @Transactional
    public void update(Long id, ChannelSaveRequest req) {
        ResChannel existing = requireChannel(id);
        readOnlyGuard.requireWritable(existing.getSourceType(), "修改通道");

        String code = req.getCode().trim();
        if (!code.equals(existing.getCode())) {
            throw new ServiceException(400, "通道编码建立后不可修改");
        }
        ResOrg org = requireWritableOrg(req.getOrgId(), "把通道挂到该组织");
        ResDevice device = requireWritableDevice(req.getDeviceId(), "把通道挂到该设备");

        channelMapper.update(null, new LambdaUpdateWrapper<ResChannel>()
                .eq(ResChannel::getId, id)
                .set(ResChannel::getName, req.getName().trim())
                .set(ResChannel::getLocalName, trimToNull(req.getLocalName()))
                .set(ResChannel::getOrgId, org.getId())
                .set(ResChannel::getOrgPath, org.getPath())
                .set(ResChannel::getDeviceId, device.getId())
                .set(ResChannel::getStreamType, normalizeStreamType(req.getStreamType()))
                .set(ResChannel::getCapability, req.getCapability() == null ? "" : req.getCapability().trim())
                .set(ResChannel::getMediaHint, trimToNull(req.getMediaHint()))
                .set(ResChannel::getUpdateTime, LocalDateTime.now()));

        log.info("[{}] 编辑通道 id={} name={} orgId={} deviceId={}",
                LOG_TAG, id, req.getName(), org.getId(), device.getId());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ResChannel channel = requireChannel(id);
        readOnlyGuard.requireWritable(channel.getSourceType(), "删除通道");

        channelMapper.deleteById(id);
        log.info("[{}] 删除通道 id={} code={}", LOG_TAG, id, channel.getCode());
    }

    private ResChannel requireChannel(Long id) {
        ResChannel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw new ServiceException("通道不存在");
        }
        return channel;
    }

    private ResOrg requireWritableOrg(Long orgId, String scene) {
        if (orgId == null) {
            throw new ServiceException(400, "所属组织不能为空");
        }
        ResOrg org = orgMapper.selectById(orgId);
        if (org == null) {
            throw new ServiceException(400, "所属组织不存在");
        }
        readOnlyGuard.requireWritable(org.getSourceType(), scene);
        return org;
    }

    private ResDevice requireWritableDevice(Long deviceId, String scene) {
        if (deviceId == null) {
            throw new ServiceException(400, "所属设备不能为空");
        }
        ResDevice device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new ServiceException(400, "所属设备不存在");
        }
        readOnlyGuard.requireWritable(device.getSourceType(), scene);
        return device;
    }

    /** 码流类型只接受三种写法，空值落 MAIN；非法值直接拒，避免脏值进库后取流分支匹配不上 */
    private static String normalizeStreamType(String streamType) {
        String s = trimToNull(streamType);
        if (s == null) {
            return "MAIN";
        }
        String upper = s.toUpperCase();
        if (!STREAM_TYPES.contains(upper)) {
            throw new ServiceException(400, "码流类型只能是 MAIN / SUB / THIRD");
        }
        return upper;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 展示名：本地别名优先，其次正式名称；两者都空时给空串（避免 toMap 遇到 null 值抛 NPE） */
    private static String displayName(String localName, String name) {
        if (localName != null && !localName.isBlank()) {
            return localName;
        }
        return name == null ? "" : name;
    }

    /** 同源组主 id：未关联返回自身（与 3.10.3 的 {@code COALESCE(NULLIF(canonical_id,0), id)} 同口径） */
    private static Long leaderIdOf(ResChannel channel) {
        Long canonical = channel.getCanonicalId();
        return canonical == null || canonical <= 0 ? channel.getId() : canonical;
    }
}
