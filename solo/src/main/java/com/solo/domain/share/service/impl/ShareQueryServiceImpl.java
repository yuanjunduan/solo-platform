package com.solo.domain.share.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solo.api.resource.dto.ChannelListItem;
import com.solo.api.resource.dto.ResourcePageRequest;
import com.solo.api.share.dto.ShareQueryRequest;
import com.solo.api.share.dto.ShareView;
import com.solo.common.PageResult;
import com.solo.common.ServiceException;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.service.ChannelService;
import com.solo.domain.share.model.OutGroup;
import com.solo.domain.share.model.OutShare;
import com.solo.domain.share.model.OutboxEvent;
import com.solo.domain.share.model.PublishRound;
import com.solo.domain.share.model.ShareRefType;
import com.solo.domain.share.service.OutCodeService;
import com.solo.domain.share.service.ShareQueryService;
import com.solo.domain.share.support.ShareStatusMerge;
import com.solo.gb28181.entity.GbParentPlatform;
import com.solo.gb28181.mapper.GbParentPlatformMapper;
import com.solo.infra.mapper.OutGroupMapper;
import com.solo.infra.mapper.OutShareMapper;
import com.solo.infra.mapper.OutboxMapper;
import com.solo.infra.mapper.PublishRoundMapper;
import com.solo.infra.mapper.projection.OutShareCount;
import com.solo.infra.mapper.projection.OutboxStatusCount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 共享三页的只读实现。
 *
 * <p>汇总卡片的 N 个平台各 4 次查询是刻意的：上级平台数量是"配置级"（通常个位数到几十），
 * 而把 {@code out_share} 全表拉进内存再分组在万级通道时更慢、也更占内存。
 * 真要优化也应该加按平台的聚合 SQL，而不是把读路径搬进一个巨型 {@code @Select}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareQueryServiceImpl implements ShareQueryService {

    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 200L;

    private final GbParentPlatformMapper parentPlatformMapper;
    private final OutGroupMapper outGroupMapper;
    private final OutShareMapper outShareMapper;
    private final OutboxMapper outboxMapper;
    private final PublishRoundMapper publishRoundMapper;
    private final OutCodeService outCodeService;
    private final ChannelService channelService;

    @Override
    public List<ShareView.Target> listTargets() {
        List<GbParentPlatform> platforms = parentPlatformMapper.selectList(
                new LambdaQueryWrapper<GbParentPlatform>().orderByAsc(GbParentPlatform::getId));
        if (platforms == null || platforms.isEmpty()) {
            return List.of();
        }
        List<ShareView.Target> out = new ArrayList<>(platforms.size());
        for (GbParentPlatform platform : platforms) {
            ShareView.Target item = new ShareView.Target();
            item.setId(platform.getId());
            item.setName(platform.getName());
            item.setServerGbId(platform.getServerGbId());
            item.setEnabled(platform.getEnabled());
            item.setStatus(platform.getStatus());

            OutShareCount counts = outShareMapper.countByPlatform(platform.getId());
            if (counts != null) {
                item.setChannelCount(counts.getChannelCount());
                item.setRuleCount(counts.getRuleCount());
                item.setExcludedCount(counts.getExcludedCount());
            }
            item.setSummaryStatus(ShareStatusMerge.worst(outShareMapper.selectStatuses(platform.getId())));
            item.setPendingCount(outboxMapper.countPending(platform.getId()));

            PublishRound latest = publishRoundMapper.selectLatest(platform.getId());
            if (latest != null) {
                item.setLastRoundStatus(latest.getStatus());
                item.setLastRoundSent(latest.getSent());
                item.setLastRoundFailed(latest.getFailed());
                item.setLastPushedAt(latest.getEndTime() != null ? latest.getEndTime() : latest.getBeginTime());
            }
            out.add(item);
        }
        return out;
    }

    @Override
    public List<OutGroup> listGroups(Long platformId) {
        if (platformId == null) {
            throw new ServiceException("上级平台不能为空");
        }
        List<OutGroup> groups = outGroupMapper.selectByPlatform(platformId);
        return groups == null ? List.of() : groups;
    }

    @Override
    public PageResult<OutShare> pageItems(ShareQueryRequest.Item query) {
        ShareQueryRequest.Item q = query == null ? new ShareQueryRequest.Item() : query;
        int offset = offset(q.getPage(), q.getPageSize());
        int limit = size(q.getPageSize());
        List<OutShare> rows = outShareMapper.pageWithRef(q.getPlatformId(), q.getRefType(), q.getScopeMode(),
                q.getStatus(), q.getEnabled(), q.getRefAlive(), q.getKeyword(), offset, limit);
        long total = outShareMapper.countByFilter(q.getPlatformId(), q.getRefType(), q.getScopeMode(),
                q.getStatus(), q.getEnabled(), q.getRefAlive(), q.getKeyword());
        return new PageResult<>(total, rows == null ? List.of() : rows);
    }

    @Override
    public PageResult<ShareView.ChannelOption> pageChannelOptions(Long platformId, ResourcePageRequest query) {
        if (platformId == null) {
            throw new ServiceException("上级平台不能为空");
        }
        ResourcePageRequest q = query == null ? new ResourcePageRequest() : query;
        Page<ChannelListItem> page = channelService.pageList(q);
        List<ChannelListItem> rows = page.getRecords();
        if (rows == null || rows.isEmpty()) {
            return new PageResult<>(page.getTotal(), List.of());
        }
        List<ResChannel> channels = rows.stream()
                .map(ChannelListItem::getChannel)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<Long> ids = channels.stream().map(ResChannel::getId).toList();
        Map<Long, List<OutShare>> shareRows = loadShareRows(platformId, ids);
        Map<String, String> exposed = loadExposedCodes(platformId, channels);

        List<ShareView.ChannelOption> out = new ArrayList<>(rows.size());
        for (ChannelListItem row : rows) {
            ResChannel channel = row.getChannel();
            if (channel == null) {
                continue;
            }
            ShareView.ChannelOption option = new ShareView.ChannelOption();
            option.setChannel(channel);
            option.setDeviceName(row.getDeviceName());
            option.setOrgName(row.getOrgName());
            List<OutShare> mine = shareRows.getOrDefault(channel.getId(), List.of());
            option.setShared(mine.stream().anyMatch(OutShare::isUsable));
            option.setExcluded(mine.stream().anyMatch(r -> r.getEnabled() != null && r.getEnabled() == 0));
            option.setExposedCode(exposed.get(channel.getCode()));
            out.add(option);
        }
        return new PageResult<>(page.getTotal(), out);
    }

    @Override
    public PageResult<OutboxEvent> pageOutbox(ShareQueryRequest.Outbox query) {
        ShareQueryRequest.Outbox q = query == null ? new ShareQueryRequest.Outbox() : query;
        int offset = offset(q.getPage(), q.getPageSize());
        int limit = size(q.getPageSize());
        List<OutboxEvent> rows = outboxMapper.pageWithPlatform(q.getPlatformId(), q.getStatus(),
                q.getEventType(), q.getKeyword(), offset, limit);
        long total = outboxMapper.countByFilter(q.getPlatformId(), q.getStatus(), q.getEventType(), q.getKeyword());
        return new PageResult<>(total, rows == null ? List.of() : rows);
    }

    @Override
    public List<OutboxStatusCount> outboxCounts(Long platformId) {
        List<OutboxStatusCount> rows = outboxMapper.countGroupByStatus(platformId);
        return rows == null ? List.of() : rows;
    }

    @Override
    public PageResult<PublishRound> pageRounds(ShareQueryRequest.Round query) {
        ShareQueryRequest.Round q = query == null ? new ShareQueryRequest.Round() : query;
        int offset = offset(q.getPage(), q.getPageSize());
        int limit = size(q.getPageSize());
        List<PublishRound> rows = publishRoundMapper.pageByFilter(q.getPlatformId(), q.getStatus(), offset, limit);
        long total = publishRoundMapper.countByFilter(q.getPlatformId(), q.getStatus());
        return new PageResult<>(total, rows == null ? List.of() : rows);
    }

    @Override
    public PublishRound roundDetail(Long id) {
        if (id == null) {
            throw new ServiceException("轮次 id 不能为空");
        }
        PublishRound round = publishRoundMapper.selectById(id);
        if (round == null) {
            throw new ServiceException(404, "推送轮次不存在：" + id);
        }
        return round;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private Map<Long, List<OutShare>> loadShareRows(Long platformId, List<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return Map.of();
        }
        List<OutShare> rows = outShareMapper.selectByRefIds(platformId, ShareRefType.CHANNEL, channelIds);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        return rows.stream().collect(Collectors.groupingBy(OutShare::getRefId));
    }

    private Map<String, String> loadExposedCodes(Long platformId, List<ResChannel> channels) {
        List<String> codes = channels.stream()
                .map(ResChannel::getCode)
                .filter(code -> code != null && !code.isBlank())
                .toList();
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> exposed = outCodeService.exposedCodesOf(platformId, ShareRefType.CHANNEL, codes);
        return exposed == null ? new HashMap<>() : exposed;
    }

    private static long normalizedPage(Long page) {
        return page == null || page < 1 ? 1L : page;
    }

    private static int size(Long pageSize) {
        long size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : pageSize;
        return (int) Math.min(size, MAX_PAGE_SIZE);
    }

    private static int offset(Long page, Long pageSize) {
        return (int) ((normalizedPage(page) - 1) * size(pageSize));
    }
}
