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
import com.solo.domain.share.service.ShareDisappearService;
import com.solo.domain.share.service.ShareOutboxWriter;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资源消失的共享联动实现（C12 / 3.8.4）。
 *
 * <p><b>为什么按平台聚合而不是按通道发散</b>：一次同步可能让同一台设备的 20 个通道同时消失，
 * 给同一上级发 20 条 NOTIFY 与发 1 条带 20 个节点的 NOTIFY，对端处理成本差 20 倍，
 * 而后者失败时重试的粒度反而更小（C12 第 3 步）。</p>
 *
 * <p><b>墓碑不在这里置</b>：{@code out_code_map} 要等 DELETE 真正 2xx 后才置 TOMBSTONE
 * （3.9.5）。置早了上级漏收 DELETE 就再也补不出来——行已死、码已亡，双向都找不到线索。</p>
 *
 * <p><b>恢复只认 {@code operator=SYSTEM_REMOVED}</b>：用户主动撤销的共享，
 * 上游资源重新出现也不该自动复活（那是用户的决定，不是系统状态）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareDisappearServiceImpl implements ShareDisappearService {

    private static final String LOG_TAG = "[上级共享]";
    /** 上溯分组防环（物化路径异常时也不会死循环） */
    private static final int MAX_ANCESTOR_WALK = 64;

    private final OutShareMapper outShareMapper;
    private final OutGroupMapper outGroupMapper;
    private final ResChannelMapper channelMapper;
    private final ResDeviceMapper deviceMapper;
    private final OutCodeService outCodeService;
    private final ShareOutboxWriter outboxWriter;
    private final ShareComposeService shareComposeService;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DisappearResult afterMerge(Long sourcePlatformId) {
        if (sourcePlatformId == null || sourcePlatformId <= 0) {
            return DisappearResult.empty();
        }
        LocalDateTime now = LocalDateTime.now();

        // ---- 第 1、2 步：通道行置死 + 分组摘除，按平台聚合 ----
        Map<Long, Batch> batches = new LinkedHashMap<>();
        int broken = 0;
        for (OutShare row : outShareMapper.selectBrokenBySource(sourcePlatformId)) {
            outShareMapper.markRefDeadById(row.getId(), ShareStatus.REVOKING,
                    OPERATOR_SYSTEM_REMOVED, now);
            broken++;
            Map<String, Object> item = deleteItem(row);
            if (item != null) {
                byPlatform(batches, row.getPlatformId()).channels.add(item);
            }
        }
        int removedGroups = collectDeadGroups(sourcePlatformId, batches);

        // ---- 第 3 步：每个受影响平台各一条 DELETE（通道在前、分组深 → 浅） ----
        for (Map.Entry<Long, Batch> entry : batches.entrySet()) {
            Batch batch = entry.getValue();
            List<Map<String, Object>> items = new ArrayList<>(batch.channels.size() + batch.groups.size());
            items.addAll(batch.channels);
            items.addAll(batch.groups);
            if (items.isEmpty()) {
                continue;
            }
            outboxWriter.submit(entry.getKey(), List.of(ShareEvent.batchDelete(items)), false);
            log.info("{} 资源消失联动 platform={} 通道={} 分组={}", LOG_TAG, entry.getKey(),
                    batch.channels.size(), batch.groups.size());
            // 分组的 DELETE 报文素材已在 payload 里：行删掉不影响重试，也不会留下"上级看得见、本地无记录"的悬空节点
            if (!batch.groupIds.isEmpty()) {
                outGroupMapper.deleteByIds(batch.groupIds);
            }
        }

        // ---- 规则失效 / 恢复：交给派生重算清理与放回派生物 ----
        Set<Long> rebuild = new LinkedHashSet<>();
        int deadRules = 0;
        for (OutShare rule : outShareMapper.selectDeadRulesBySource(sourcePlatformId)) {
            outShareMapper.markRefDeadById(rule.getId(), ShareStatus.REVOKING,
                    OPERATOR_SYSTEM_REMOVED, now);
            rebuild.add(rule.getPlatformId());
            deadRules++;
        }
        int restoredRules = 0;
        for (OutShare rule : outShareMapper.selectRestorableRulesBySource(
                sourcePlatformId, OPERATOR_SYSTEM_REMOVED)) {
            outShareMapper.markRestored(rule.getId(), now);
            rebuild.add(rule.getPlatformId());
            restoredRules++;
        }
        // 不吞异常：本方法在同步合并的事务里（MANDATORY），吞掉只会把事务标成 rollback-only，
        // 反而在提交时抛一个更难查的 UnexpectedRollbackException（C12：四步同生同灭）
        for (Long platformId : rebuild) {
            shareComposeService.rebuildDerived(platformId);
        }

        // ---- 恢复通道：上游重新出现 → ref_alive 置回 1 + ADD 事件（复用自身墓碑码） ----
        Map<Long, List<ShareEvent>> restoreEvents = new LinkedHashMap<>();
        int restoredChannels = 0;
        for (OutShare row : outShareMapper.selectRestorableBySource(
                sourcePlatformId, OPERATOR_SYSTEM_REMOVED)) {
            ShareEvent event = restoreEvent(row);
            if (event == null) {
                // 资源这一轮又不在了：留给下一轮，不提前把行放活
                continue;
            }
            outShareMapper.markRestored(row.getId(), now);
            outCodeService.restore(row.getPlatformId(), ShareRefType.CHANNEL, row.getRefCode());
            restoreEvents.computeIfAbsent(row.getPlatformId(), k -> new ArrayList<>()).add(event);
            restoredChannels++;
        }
        for (Map.Entry<Long, List<ShareEvent>> entry : restoreEvents.entrySet()) {
            outboxWriter.submit(entry.getKey(), entry.getValue(), false);
            log.info("{} 资源恢复联动 platform={} 通道={}", LOG_TAG, entry.getKey(),
                    entry.getValue().size());
        }

        return new DisappearResult(broken, deadRules, removedGroups, restoredChannels, restoredRules);
    }

    // ------------------------------------------------------------------
    // 报文素材
    // ------------------------------------------------------------------

    /**
     * 删除条目：优先用资源快照（能带全全局身份），资源已物理消失时退化为行上的定位字段。
     * <p>没分配过暴露码（{@code out_code_map} 无记录）的通道上级从未见过，不必发 DELETE。</p>
     */
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

    private ShareEvent restoreEvent(OutShare row) {
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

    // ------------------------------------------------------------------
    // 分组摘除
    // ------------------------------------------------------------------

    /**
     * 镜像来源组织已被删除的虚拟分组，收集待删条目。
     * <p>只摘"子树里已经没有可见通道"的分组：同一棵镜像树下可能还挂着其它来源的通道
     * （例如本地通道被手工挂到了这一层），先删父节点会把它变成孤儿。</p>
     */
    private int collectDeadGroups(Long sourcePlatformId, Map<Long, Batch> batches) {
        List<OutGroup> dead = outGroupMapper.selectByDeadSourceOrg(sourcePlatformId);
        if (dead.isEmpty()) {
            return 0;
        }
        Map<Long, Set<String>> usedCache = new HashMap<>();
        int count = 0;
        for (OutGroup group : dead) {
            Set<String> used = usedCache.computeIfAbsent(group.getPlatformId(), this::codesInUse);
            if (used.contains(group.getCode())) {
                continue;
            }
            Batch batch = byPlatform(batches, group.getPlatformId());
            batch.groups.add(SharePayloads.group(group));
            batch.groupIds.add(group.getId());
            count++;
        }
        return count;
    }

    /** 该平台下"仍有可见通道落在其子树里"的分组码（含祖先链） */
    private Set<String> codesInUse(Long platformId) {
        Map<Long, OutGroup> byId = new HashMap<>();
        for (OutGroup group : outGroupMapper.selectByPlatform(platformId)) {
            byId.put(group.getId(), group);
        }
        Set<String> used = new HashSet<>();
        for (OutShare row : outShareMapper.selectUsable(platformId)) {
            if (!ShareRefType.CHANNEL.equals(row.getRefType()) || row.getGroupId() == null) {
                continue;
            }
            OutGroup node = byId.get(row.getGroupId());
            int guard = 0;
            while (node != null && guard++ < MAX_ANCESTOR_WALK) {
                used.add(node.getCode());
                node = node.getParentId() == null ? null : byId.get(node.getParentId());
            }
        }
        return used;
    }

    private static Batch byPlatform(Map<Long, Batch> batches, Long platformId) {
        return batches.computeIfAbsent(platformId, k -> new Batch());
    }

    /** 一个平台的待删条目（先通道、后分组，深 → 浅已在查询里排好） */
    private static final class Batch {
        private final List<Map<String, Object>> channels = new ArrayList<>();
        private final List<Map<String, Object>> groups = new ArrayList<>();
        private final List<Long> groupIds = new ArrayList<>();
    }
}
