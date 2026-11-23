package com.solo.domain.resource.service.impl;

import com.solo.common.ServiceException;
import com.solo.domain.audit.model.AuditLog;
import com.solo.domain.audit.service.AuditService;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResourceStatus;
import com.solo.domain.resource.service.CanonicalChannelService;
import com.solo.domain.resource.support.CanonicalRouteOrder;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.projection.CanonicalAnomaly;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 同源组维护与播放选路（3.10.3）。
 *
 * <p><b>为什么只允许"新建组"</b>：并入别的组意味着把另一条链路从它原来的组里拽出来，
 * 而"系统不允许自动解除"是文档的硬规则；让人先解除再绑定，代价只多一次点击，
 * 换来的是任何一次绑定都可以用人眼复核。</p>
 *
 * <p><b>为什么自动关联只补空</b>：L1（全局身份完全相同）置信度 100%，自动关联是文档要求；
 * 但只要组里已经有人工指针，就整体跳过——自动关联绝不能拆散人工组。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanonicalChannelServiceImpl implements CanonicalChannelService {

    private static final String LOG_TAG = "同源组";
    private static final String ACTION_BIND = "BIND_CANONICAL";
    private static final String ACTION_UNBIND = "UNBIND_CANONICAL";
    private static final String ACTION_AUTO = "AUTO_LINK_CANONICAL";

    private final ResChannelMapper channelMapper;
    private final AuditService auditService;

    @Override
    public SourceGroup group(Long channelId) {
        ResChannel row = requireChannel(channelId);
        Long leaderId = leaderOf(row);
        List<ResChannel> members = membersOf(leaderId);
        if (members.isEmpty()) {
            members = List.of(row);
        }
        return new SourceGroup(leaderId, members);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SourceGroup bind(List<Long> channelIds) {
        List<Long> ids = distinctIds(channelIds);
        if (ids.size() < 2) {
            throw new ServiceException(400, "同源绑定至少选择 2 条通道");
        }
        Map<Long, ResChannel> rows = loadRows(ids);

        for (ResChannel row : rows.values()) {
            if (ResourceStatus.REMOVED.equals(row.getStatus())) {
                throw new ServiceException(400, "已消失的通道不能参与同源绑定：" + row.getCode());
            }
            if (isLinked(row)) {
                throw new ServiceException(400, "通道 " + row.getCode()
                        + " 已属于其它同源组，请先在「来源组」里解除关联");
            }
        }
        // 已是组主的行不能直接并入他组：它的子记录仍指向它，并入后会形成"子指向子"的两级指针
        List<Long> leaders = channelMapper.selectReferencedLeaderIds(ids);
        if (!leaders.isEmpty()) {
            throw new ServiceException(400, "通道 " + rows.get(leaders.get(0)).getCode()
                    + " 已是同源组主，请先解除整组关联再重新绑定");
        }

        ResChannel leader = pickLeader(rows.values());
        int changed = 0;
        for (ResChannel row : rows.values()) {
            if (row.getId().equals(leader.getId())) {
                continue;
            }
            // 期望值 0（未关联）：读到这里之后若已被别人绑走，CAS 会失败而不是把别人的组覆盖掉
            changed += casOrConflict(row, 0L, leader.getId());
        }

        auditService.record(ACTION_BIND, AuditLog.TARGET_CHANNEL, String.valueOf(leader.getId()),
                "绑定同源组 " + rows.size() + " 条，组主 code=" + leader.getCode() + " ids=" + ids);
        log.info("[{}] 绑定 id 集合={} 组主={} 实际改动={}", LOG_TAG, ids, leader.getId(), changed);
        return group(leader.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbind(Long channelId) {
        ResChannel row = requireChannel(channelId);
        if (isLinked(row)) {
            // 普通成员：只把自己摘出来，组主与其它成员不动
            Long previousLeader = row.getCanonicalId();
            casOrConflict(row, previousLeader, 0L);
            auditService.record(ACTION_UNBIND, AuditLog.TARGET_CHANNEL, String.valueOf(row.getId()),
                    "退出同源组，原组主=" + previousLeader + " code=" + row.getCode());
            log.info("[{}] 成员退出 通道={} 原组主={}", LOG_TAG, row.getId(), previousLeader);
            return;
        }

        List<ResChannel> members = membersOf(row.getId());
        List<ResChannel> rest = new ArrayList<>();
        for (ResChannel member : members) {
            if (!member.getId().equals(row.getId())) {
                rest.add(member);
            }
        }
        if (rest.isEmpty()) {
            throw new ServiceException(400, "该通道不在同源组中");
        }

        ResChannel newLeader = pickLeader(rest);
        // 组主换人也是一串 CAS：任何一条成员在此期间被人重新绑走，整组回滚而不是留下半新半旧的指针
        casOrConflict(newLeader, newLeader.getCanonicalId(), 0L);
        for (ResChannel member : rest) {
            if (!member.getId().equals(newLeader.getId())) {
                casOrConflict(member, member.getCanonicalId(), newLeader.getId());
            }
        }
        auditService.record(ACTION_UNBIND, AuditLog.TARGET_CHANNEL, String.valueOf(row.getId()),
                "组主退出同源组，新组主=" + newLeader.getId() + " code=" + row.getCode());
        log.info("[{}] 组主退出 通道={} 新组主={}", LOG_TAG, row.getId(), newLeader.getId());
    }

    @Override
    public List<ResChannel> playCandidates(Long channelId) {
        ResChannel row = requireChannel(channelId);
        List<ResChannel> members = membersOf(leaderOf(row));
        if (members.isEmpty()) {
            members = List.of(row);
        }
        return CanonicalRouteOrder.order(members, row.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int afterMerge(Long sourcePlatformId) {
        if (sourcePlatformId == null) {
            return 0;
        }
        List<ResChannel> rows = channelMapper.selectByOriginPairsOf(sourcePlatformId);
        if (rows == null || rows.size() < 2) {
            return 0;
        }

        Map<String, List<ResChannel>> groups = new LinkedHashMap<>();
        for (ResChannel row : rows) {
            String key = row.getOriginPlatformId() + "|" + row.getOriginCode();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        int linked = 0;
        for (Map.Entry<String, List<ResChannel>> entry : groups.entrySet()) {
            List<ResChannel> group = entry.getValue();
            if (group.size() < 2) {
                continue;
            }
            // 只处理"全组都还没有指针"的组；任何一条已被人工关联过，整组跳过（系统不自动解除/不拆组）
            boolean allFree = group.stream().noneMatch(CanonicalChannelServiceImpl::isLinked);
            if (!allFree) {
                continue;
            }
            List<Long> ids = group.stream().map(ResChannel::getId).toList();
            if (!channelMapper.selectReferencedLeaderIds(ids).isEmpty()) {
                log.info("[{}] L1 命中但组内已有组主，跳过自动关联 origin={} 行数={}",
                        LOG_TAG, entry.getKey(), group.size());
                continue;
            }

            ResChannel leader = pickLeader(group);
            for (ResChannel row : group) {
                if (!row.getId().equals(leader.getId())) {
                    casOrConflict(row, 0L, leader.getId());
                    linked++;
                }
            }
            auditService.record(ACTION_AUTO, AuditLog.TARGET_CHANNEL, String.valueOf(leader.getId()),
                    "L1 全局身份相同自动关联 " + group.size() + " 条，origin=" + entry.getKey());
            log.info("[{}] L1 自动关联 origin={} 行数={} 组主={}", LOG_TAG, entry.getKey(), group.size(), leader.getId());
        }
        // 本轮写完立刻核对一次：异常只告警（核对只读），不让关联本身影响同步结果
        checkIntegrity();
        return linked;
    }

    @Override
    public Integrity checkIntegrity() {
        CanonicalAnomaly anomaly = channelMapper.countCanonicalAnomalies();
        Integrity integrity = anomaly == null
                ? new Integrity(0, 0, 0, 0)
                : new Integrity(anomaly.selfPointerCount(), anomaly.orphanCount(),
                        anomaly.twoLevelCount(), anomaly.leaderRemovedCount());
        if (!integrity.clean()) {
            log.warn("[{}] 一致性核对发现异常 自指={} 孤儿={} 两级指针={} 组主已消失={}",
                    LOG_TAG, integrity.selfPointer(), integrity.orphan(),
                    integrity.twoLevel(), integrity.leaderRemoved());
        }
        return integrity;
    }

    /* ==================== 内部 ==================== */

    private ResChannel requireChannel(Long id) {
        if (id == null) {
            throw new ServiceException(400, "通道 id 不能为空");
        }
        ResChannel row = channelMapper.selectById(id);
        if (row == null) {
            throw new ServiceException("通道不存在：" + id);
        }
        return row;
    }

    /** 组主 id：未关联返回自身 */
    private static Long leaderOf(ResChannel row) {
        Long canonical = row.getCanonicalId();
        return canonical == null || canonical <= 0 ? row.getId() : canonical;
    }

    private static boolean isLinked(ResChannel row) {
        Long canonical = row.getCanonicalId();
        return canonical != null && canonical > 0;
    }

    private List<ResChannel> membersOf(Long leaderId) {
        if (leaderId == null) {
            return List.of();
        }
        List<ResChannel> members = channelMapper.selectByCanonicalGroup(leaderId);
        return members == null ? List.of() : members;
    }

    /**
     * 组主选取（3.10.3）：{@code source_platform_id=0}（本地）优先 > 创建时间最早 > id 最小。
     * 本地行优先是因为它不依赖上级平台，上级抖动时组里仍有一条自己能支配的链路。
     */
    private static ResChannel pickLeader(Collection<ResChannel> rows) {
        return rows.stream()
                .min(Comparator
                        .comparingInt((ResChannel c) -> isLocal(c) ? 0 : 1)
                        .thenComparing(ResChannel::getCreateTime,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ResChannel::getId, Comparator.nullsLast(Long::compareTo)))
                .orElseThrow(() -> new ServiceException(400, "同源组至少需要一条通道"));
    }

    private static boolean isLocal(ResChannel row) {
        Long platformId = row.getSourcePlatformId();
        return platformId == null || platformId == 0L;
    }

    /**
     * CAS 写指针：库里的当前值与读到的期望值不一致，说明有人在这期间先改了，
     * 抛 409 让整次操作回滚并让用户刷新——绝不覆盖别人的关联结果。
     */
    private int casOrConflict(ResChannel row, Long expected, Long target) {
        int changed = channelMapper.updateCanonical(row.getId(), row.getSourceType(),
                expected == null ? 0L : expected, target == null ? 0L : target, LocalDateTime.now());
        if (changed == 0) {
            throw new ServiceException(409,
                    "通道 " + row.getCode() + " 的关联已被其它操作修改，请刷新后重试");
        }
        row.setCanonicalId(target == null ? 0L : target);
        return changed;
    }

    private Map<Long, ResChannel> loadRows(List<Long> ids) {
        Map<Long, ResChannel> rows = new LinkedHashMap<>();
        for (ResChannel row : channelMapper.selectBatchIds(ids)) {
            rows.put(row.getId(), row);
        }
        List<Long> missing = ids.stream().filter(id -> !rows.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new ServiceException(400, "通道不存在：" + missing);
        }
        return rows;
    }

    private static List<Long> distinctIds(List<Long> channelIds) {
        Set<Long> ids = new LinkedHashSet<>();
        if (channelIds != null) {
            for (Long id : channelIds) {
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return new ArrayList<>(ids);
    }
}
