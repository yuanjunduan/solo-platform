package com.solo.domain.share.service.impl;

import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResDevice;
import com.solo.domain.resource.model.ResourceStatus;
import com.solo.domain.share.model.OutGroup;
import com.solo.domain.share.model.OutShare;
import com.solo.domain.share.model.PublishEventType;
import com.solo.domain.share.model.ShareRefType;
import com.solo.domain.share.model.ShareStatus;
import com.solo.domain.share.service.OutCodeService;
import com.solo.domain.share.service.ShareComposeService;
import com.solo.domain.share.service.ShareOutboxWriter;
import com.solo.domain.share.service.ShareReconcileService;
import com.solo.domain.share.support.ShareEvent;
import com.solo.domain.share.support.SharePayloads;
import com.solo.domain.share.support.ShareSettings;
import com.solo.infra.mapper.OutGroupMapper;
import com.solo.infra.mapper.OutShareMapper;
import com.solo.infra.mapper.OutboxMapper;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.ResDeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出站对账实现（6.5 验收 ⑤）。
 *
 * <p>对账的失败模式必须安全：宁可不补，也不能补错。所以每一条补充都过两道筛——
 * 行状态不是 {@code SYNCED}（确实没同步），且队列里没有在途事件（确实没人管它）。</p>
 *
 * <p>补删除同样按平台聚合成一条：与 C12 第 3 步同口径，避免"上万条撤销各发一条 NOTIFY"。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareReconcileServiceImpl implements ShareReconcileService {

    private static final String LOG_TAG = "[上级共享]";

    private final OutShareMapper outShareMapper;
    private final OutGroupMapper outGroupMapper;
    private final OutboxMapper outboxMapper;
    private final ResChannelMapper channelMapper;
    private final ResDeviceMapper deviceMapper;
    private final OutCodeService outCodeService;
    private final ShareOutboxWriter outboxWriter;
    private final ShareComposeService shareComposeService;
    private final ShareSettings settings;

    @Override
    @Transactional
    public ReconcileResult reconcile(Long platformId) {
        if (platformId == null) {
            return ReconcileResult.empty();
        }
        // 先重算派生：规则/组织/通道变化没来得及派生时，这一步就能补齐大部分偏差
        ShareComposeService.DeriveResult derived = shareComposeService.rebuildDerived(platformId);

        // ---- 补 ADD：可见、没同步、队列里没人管 ----
        List<ShareEvent> adds = new ArrayList<>();
        for (OutShare row : outShareMapper.selectUsable(platformId)) {
            if (!ShareRefType.CHANNEL.equals(row.getRefType())) {
                continue;
            }
            if (ShareStatus.SYNCED.equals(row.getStatus())) {
                continue;
            }
            if (!outboxMapper.selectInFlight(platformId, ShareRefType.CHANNEL, row.getRefCode()).isEmpty()) {
                continue;
            }
            ShareEvent event = addEvent(row);
            if (event != null) {
                adds.add(event);
            }
        }
        int readded = 0;
        boolean refreshed = false;
        if (!adds.isEmpty()) {
            int detailMax = settings.getDetailMax();
            if (detailMax > 0 && adds.size() > detailMax) {
                outboxWriter.submit(platformId, List.of(ShareEvent.refresh("对账：待补明细超过上限")), false);
                refreshed = true;
            } else {
                outboxWriter.submit(platformId, adds, false);
                readded = adds.size();
            }
        }

        // ---- 补 DELETE：撤销中、队列里没有在途 DELETE ----
        List<Map<String, Object>> items = new ArrayList<>();
        for (OutShare row : outShareMapper.selectRevoking(platformId)) {
            if (hasInFlightDelete(platformId, row.getRefCode())) {
                continue;
            }
            Map<String, Object> item = deleteItem(row);
            if (item != null) {
                items.add(item);
            }
        }
        if (!items.isEmpty()) {
            outboxWriter.submit(platformId, List.of(ShareEvent.batchDelete(items)), false);
        }

        ReconcileResult result = new ReconcileResult(derived.added(), derived.restored(),
                derived.removed(), readded, items.size(), refreshed);
        if (result.changed()) {
            log.info("{} 对账补差 platform={} 派生新增={} 派生恢复={} 派生撤销={} 补ADD={} 补DEL={} 降级={}",
                    LOG_TAG, platformId, derived.added(), derived.restored(), derived.removed(),
                    readded, items.size(), refreshed);
        }
        return result;
    }

    private boolean hasInFlightDelete(Long platformId, String refCode) {
        for (var event : outboxMapper.selectInFlight(platformId, ShareRefType.CHANNEL, refCode)) {
            if (PublishEventType.DELETE.equals(event.getEventType())) {
                return true;
            }
        }
        return false;
    }

    private ShareEvent addEvent(OutShare row) {
        ResChannel channel = row.getRefId() == null ? null : channelMapper.selectById(row.getRefId());
        if (channel == null || ResourceStatus.REMOVED.equals(channel.getStatus())) {
            return null;
        }
        String exposed = outCodeService.exposedCodeOf(
                row.getPlatformId(), ShareRefType.CHANNEL, row.getRefCode());
        if (!StringUtils.hasText(exposed)) {
            exposed = outCodeService.ensureChannelCode(row.getPlatformId(), channel);
        }
        OutGroup mount = row.getGroupId() == null ? null : outGroupMapper.selectById(row.getGroupId());
        ResDevice device = channel.getDeviceId() == null
                ? null : deviceMapper.selectById(channel.getDeviceId());
        return new ShareEvent(PublishEventType.ADD, ShareRefType.CHANNEL, channel.getId(),
                channel.getCode(), exposed, SharePayloads.channel(channel, device, mount, exposed));
    }

    private Map<String, Object> deleteItem(OutShare row) {
        String exposed = outCodeService.exposedCodeOf(
                row.getPlatformId(), ShareRefType.CHANNEL, row.getRefCode());
        if (!StringUtils.hasText(exposed)) {
            return null;
        }
        OutGroup mount = row.getGroupId() == null ? null : outGroupMapper.selectById(row.getGroupId());
        ResChannel channel = row.getRefId() == null ? null : channelMapper.selectById(row.getRefId());
        if (channel != null) {
            ResDevice device = channel.getDeviceId() == null
                    ? null : deviceMapper.selectById(channel.getDeviceId());
            return SharePayloads.channel(channel, device, mount, exposed);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "CHANNEL");
        payload.put("exposedCode", exposed);
        payload.put("nativeCode", row.getRefCode());
        payload.put("name", null);
        payload.put("parentCode", mount == null ? null : mount.getCode());
        payload.put("parentId", mount == null ? null : String.valueOf(mount.getId()));
        payload.put("orgPath", row.getOrgPath());
        return payload;
    }
}
