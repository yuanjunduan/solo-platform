package com.solo.domain.share.service.impl;

import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResDevice;
import com.solo.domain.share.model.OutGroup;
import com.solo.domain.share.model.OutShare;
import com.solo.domain.share.model.PublishEventType;
import com.solo.domain.share.model.ShareRefType;
import com.solo.domain.share.service.OutCodeService;
import com.solo.domain.share.service.ShareOutboxWriter;
import com.solo.domain.share.service.ShareStatusFollowService;
import com.solo.domain.share.support.ShareEvent;
import com.solo.domain.share.support.SharePayloads;
import com.solo.infra.mapper.OutGroupMapper;
import com.solo.infra.mapper.OutShareMapper;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.ResDeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态跟随实现（6.6）。
 *
 * <p>与 {@link com.solo.domain.share.service.ShareDisappearService} 同一事务纪律：
 * {@code Propagation.MANDATORY}，资源状态与出站事件必须同生共死。</p>
 *
 * <p><b>单轮扫描上限</b>：一次全量同步最多可能有十万条通道同时变状态。这里只取前
 * {@link #SCAN_LIMIT} 条——超过的部分不是丢掉，而是本轮先落基线、下一轮继续。
 * 若真的一次变了这么多，{@code ShareOutboxWriter} 会按 {@code publish.detail-max}
 * 自动降级成一条 REFRESH，上级重拉一次快照即可对齐。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareStatusFollowServiceImpl implements ShareStatusFollowService {

    /** 单轮最多处理多少条状态变化；超出的留给下一轮（基线已推进，不会重复） */
    private static final int SCAN_LIMIT = 5_000;

    private final OutShareMapper outShareMapper;
    private final ResChannelMapper channelMapper;
    private final ResDeviceMapper deviceMapper;
    private final OutGroupMapper groupMapper;
    private final OutCodeService outCodeService;
    private final ShareOutboxWriter outboxWriter;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int afterMerge(Long sourcePlatformId) {
        if (sourcePlatformId == null) {
            return 0;
        }
        List<OutShare> changed = outShareMapper.selectStatusChangedBySource(sourcePlatformId, SCAN_LIMIT);
        if (changed.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        Map<Long, List<ShareEvent>> byPlatform = new LinkedHashMap<>();
        int baseline = 0;
        int skipped = 0;
        for (OutShare row : changed) {
            String current = normalize(row.getRefOnlineStatus());
            // 先推进"上次推给上级的值"：即使下面组装报文失败，也不会下一轮重复判定
            outShareMapper.updateRefOnlineStatus(row.getId(), current, now);
            if (row.getStoredOnlineStatus() == null) {
                // 基线：这条共享的 ADD 事件里已带过状态，不补发
                baseline++;
                continue;
            }
            ShareEvent event = buildEvent(row, current);
            if (event == null) {
                skipped++;
                continue;
            }
            byPlatform.computeIfAbsent(row.getPlatformId(), k -> new ArrayList<>()).add(event);
        }

        int written = 0;
        for (Map.Entry<Long, List<ShareEvent>> entry : byPlatform.entrySet()) {
            written += outboxWriter.submit(entry.getKey(), entry.getValue(), false);
        }
        if (written > 0 || baseline > 0 || skipped > 0) {
            log.info("[上级共享] 状态跟随 platformId={} 变化={} 落基线={} 出站事件={} 跳过={}",
                    sourcePlatformId, changed.size(), baseline, written, skipped);
        }
        return written;
    }

    private ShareEvent buildEvent(OutShare row, String current) {
        if (row.getRefId() == null || !StringUtils.hasText(row.getRefCode())) {
            return null;
        }
        String exposed = outCodeService.exposedCodeOf(row.getPlatformId(),
                ShareRefType.CHANNEL, row.getRefCode());
        if (!StringUtils.hasText(exposed)) {
            // 没有暴露码说明这条从未成功推给该上级；状态事件发出去上级也定位不到节点
            return null;
        }
        ResChannel channel = channelMapper.selectById(row.getRefId());
        if (channel == null) {
            return null;
        }
        ResDevice device = channel.getDeviceId() == null ? null : deviceMapper.selectById(channel.getDeviceId());
        OutGroup mount = row.getGroupId() == null ? null : groupMapper.selectById(row.getGroupId());
        String type = PublishEventType.ON.equals(current) ? PublishEventType.ON : PublishEventType.OFF;
        return new ShareEvent(type, ShareRefType.CHANNEL, row.getRefId(), row.getRefCode(), exposed,
                SharePayloads.channel(channel, device, mount, exposed));
    }

    /** 只有明确的 OFF 才算离线；空值与历史脏值一律按 ON（宁可少发 OFF，不可误报离线） */
    private static String normalize(String status) {
        return "OFF".equalsIgnoreCase(status) ? "OFF" : "ON";
    }
}
