package com.solo.domain.sync.service.impl;

import com.solo.common.ServiceException;
import com.solo.domain.audit.model.AuditLog;
import com.solo.domain.audit.service.AuditService;
import com.solo.domain.event.service.SyncEventEmitter;
import com.solo.domain.platform.model.Platform;
import com.solo.domain.resource.model.ResourceStatus;
import com.solo.domain.share.service.ShareDisappearService;
import com.solo.domain.share.service.ShareStatusFollowService;
import com.solo.domain.sync.model.PlatformCatalogVer;
import com.solo.domain.sync.model.SyncColumns;
import com.solo.domain.sync.model.SyncItemType;
import com.solo.domain.sync.model.SyncRound;
import com.solo.domain.sync.model.SyncTask;
import com.solo.domain.sync.model.SyncTaskStatus;
import com.solo.domain.sync.support.SyncGuards;
import com.solo.domain.sync.support.SyncSettings;
import com.solo.infra.mapper.PlatformCatalogVerMapper;
import com.solo.infra.mapper.PlatformMapper;
import com.solo.infra.mapper.SyncChannelAlignmentMapper;
import com.solo.infra.mapper.SyncDeviceAlignmentMapper;
import com.solo.infra.mapper.SyncGroupAlignmentMapper;
import com.solo.infra.mapper.SyncOrgAlignmentMapper;
import com.solo.infra.mapper.SyncOrgPendingMapper;
import com.solo.infra.mapper.SyncRecordMapper;
import com.solo.infra.mapper.SyncStagingMapper;
import com.solo.infra.mapper.SyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * finish 阶段：单事务把暂存合并进资源表（3.8.2 第 3 步）。
 *
 * <p>顺序是硬的，不能调换：</p>
 * <ol>
 *   <li><b>组织 → 分组</b>：先建树，设备/通道的归属才 JOIN 得到父组织；</li>
 *   <li><b>设备 → 通道</b>：通道的内连接要求载体设备已存在；</li>
 *   <li><b>新增/更新 → 消失标记</b>：消失判断按"暂存里有没有"算，先插后判不会把新行判没；</li>
 *   <li><b>两道闸门</b>：G1 在合并前（整轮失败），G2 在每条消失语句前（只跳过删除）。</li>
 * </ol>
 *
 * <p><b>G2 的分子是精确值</b>：先用与 markMissing 完全同一谓词的 COUNT 算出"预计消失数"，
 * 再决定要不要标记。用"本地数 − 暂存数"当近似值会把"大量新增 + 大量删除"误判成批量删除。</p>
 *
 * <p><b>事务边界</b>：整个合并是一个事务，任何一步抛异常都整体回滚，资源保持旧状态
 * （3.8.2 "不会半截"）。代价是百万级目录会有一个较长事务——这是 M2b 规模测试要量的东西，
 * M2a 先保证语义正确。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncMergeService {

    private final SyncTaskMapper taskMapper;
    private final SyncStagingMapper stagingMapper;
    private final PlatformMapper platformMapper;
    private final PlatformCatalogVerMapper catalogVerMapper;
    private final SyncOrgAlignmentMapper orgAlignment;
    private final SyncGroupAlignmentMapper groupAlignment;
    private final SyncDeviceAlignmentMapper deviceAlignment;
    private final SyncChannelAlignmentMapper channelAlignment;
    private final SyncRecordMapper recordMapper;
    private final SyncOrgPendingMapper pendingMapper;
    private final SyncSnapshotWriter snapshotWriter;
    private final SyncRoundRecorder roundRecorder;
    private final AuditService auditService;
    private final SyncSettings settings;
    private final SyncEventEmitter eventEmitter;
    private final ShareDisappearService shareDisappearService;
    private final ShareStatusFollowService shareStatusFollowService;

    @Transactional(rollbackFor = Exception.class)
    public void finish(Long taskId) {
        SyncTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("同步任务不存在：" + taskId);
        }
        if (!SyncTaskStatus.isActive(task.getStatus())) {
            log.info("[资源同步] 轮次已不活跃，忽略收尾 taskId={} status={}", taskId, task.getStatus());
            return;
        }
        Platform platform = platformMapper.selectById(task.getPlatformId());
        if (platform == null) {
            throw new ServiceException("平台不存在：" + task.getPlatformId());
        }

        Long platformId = task.getPlatformId();
        long tenantId = task.getTenantId() == null ? 1L : task.getTenantId();
        String platformCode = platformCode(platform);
        LocalDateTime now = LocalDateTime.now();

        // 先置 SYNCHING（守卫活跃态）：并发收尾只有一个能拿到
        SyncTask synching = new SyncTask();
        synching.setId(taskId);
        synching.setStatus(SyncTaskStatus.SYNCHING);
        if (taskMapper.updateIfActive(synching) == 0) {
            log.info("[资源同步] 轮次在收尾前已结束，忽略 taskId={}", taskId);
            return;
        }

        long staged = stagingMapper.countByTask(taskId);

        // 本地基数只查一次：G1 的"本地有没有数据"和 G2 的分母用的都是它
        long localOrg = stagingMapper.countInboundOrg(platformId);
        long localGroup = stagingMapper.countInboundGroup(platformId);
        long localDevice = stagingMapper.countInboundDevice(platformId);
        long localChannel = stagingMapper.countInboundChannel(platformId);

        // ---- G1 空批保护：暂存 0 行但本地有数据 → 整轮失败，绝不按空集把本地标成消失 ----
        if (SyncGuards.emptyStagingIsFatal(staged, localOrg + localDevice + localChannel)) {
            throw new ServiceException("SYNC_EMPTY_STAGING: 本轮收到 0 条，本地已有 "
                    + (localOrg + localDevice + localChannel) + " 条，判整轮失败以保护数据");
        }

        // ---- 重现恢复（3.8.4 规则 3）：必须排在所有插入/更新之前 ----
        // 顺序反了就会永久卡死：REMOVED 行让 insertNew 的 NOT EXISTS 判成"已存在"而跳过，
        // updateDiff 只比目录字段、不改 status，于是上游明明回来了，本地仍旧 REMOVED。
        // 留痕紧贴恢复语句（靠 status + update_time 选行），中间插别的语句就会多算。
        int restoredOrg = restoreAndRecord(orgAlignment.restoreActive(taskId, platformId, now),
                () -> recordMapper.recordOrg(taskId, platformId, ResourceStatus.ACTIVE, "RESTORE", now));
        int restoredGroup = restoreAndRecord(groupAlignment.restoreActive(taskId, platformId, now),
                () -> recordMapper.recordGroup(taskId, platformId, ResourceStatus.ACTIVE, "RESTORE", now));
        int restoredDevice = restoreAndRecord(deviceAlignment.restoreActive(taskId, platformId, now),
                () -> recordMapper.recordDevice(taskId, platformId, ResourceStatus.ACTIVE, "RESTORE", now));
        int restoredChannel = restoreAndRecord(channelAlignment.restoreActive(taskId, platformId, now),
                () -> recordMapper.recordChannel(taskId, platformId, ResourceStatus.ACTIVE, "RESTORE", now));

        // ---- 组织 / 分组：建树 ----
        int orgAdded = mergeOrgTree(taskId, platformId, tenantId, now);
        int groupAdded = mergeGroupTree(taskId, platformId, tenantId, now);
        int orgUpdated = orgAlignment.updateDiff(taskId, now, SyncColumns.ORG);
        int groupUpdated = groupAlignment.updateDiff(taskId, now, SyncColumns.GROUP);

        // ---- 设备 / 通道 ----
        int deviceAdded = deviceAlignment.insertNew(taskId, tenantId, now, SyncColumns.DEVICE);
        int deviceUpdated = deviceAlignment.updateDiff(taskId, now, SyncColumns.DEVICE);
        deviceAlignment.refreshOrg(taskId, now);

        int channelAdded = channelAlignment.insertNew(taskId, tenantId, now, SyncColumns.CHANNEL);
        int channelUpdated = channelAlignment.updateDiff(taskId, now, SyncColumns.CHANNEL);
        channelAlignment.refreshOrg(taskId, now);

        long orphanChannels = channelAlignment.countOrphanByMissingDevice(taskId);
        if (orphanChannels > 0) {
            log.warn("[资源同步] {} 条通道因载体设备缺失未入库 platformId={} taskId={}",
                    orphanChannels, platformId, taskId);
        }

        // ---- 消失标记 + G2 ----
        // 动作级开关（3.13.5）：mark 只置 STALE 便于观察，real 直接落 REMOVED。
        // 默认仍是 mark：切 real 是"会撤销上级可见性"的动作，按平台逐个切更安全；
        // 共享侧联动（C12）已就位，切到 real 即整条链生效。
        boolean realDelete = settings.realDelete();
        BigDecimal ratio = platform.getDeleteGuardRatio() == null
                ? BigDecimal.ZERO : platform.getDeleteGuardRatio();
        boolean deleteSkipped = false;
        int stale = 0;
        int staleOrg = 0;
        int staleGroup = 0;
        int staleDevice = 0;
        int staleChannel = 0;

        long wouldOrg = orgAlignment.countWouldStale(taskId, platformId, platformCode);
        if (SyncGuards.allowStale(wouldOrg, localOrg, ratio, settings.getG2MinLocalRows())) {
            staleOrg = realDelete
                    ? orgAlignment.markRemoved(taskId, platformId, platformCode, now)
                    : orgAlignment.markMissing(taskId, platformId, platformCode, now);
            if (staleOrg > 0) {
                recordMapper.recordOrg(taskId, platformId, terminalStatus(realDelete), terminalChange(realDelete), now);
            }
            stale += staleOrg;
        } else {
            deleteSkipped = true;
            log.warn("[资源同步] G2 跳过组织消失标记 platformId={} 预计={} 本地={} 阈值={}",
                    platformId, wouldOrg, localOrg, ratio);
        }

        long wouldGroup = groupAlignment.countWouldStale(taskId, platformId);
        if (SyncGuards.allowStale(wouldGroup, localGroup, ratio, settings.getG2MinLocalRows())) {
            staleGroup = realDelete
                    ? groupAlignment.markRemoved(taskId, platformId, now)
                    : groupAlignment.markMissing(taskId, platformId, now);
            if (staleGroup > 0) {
                recordMapper.recordGroup(taskId, platformId, terminalStatus(realDelete), terminalChange(realDelete), now);
            }
            stale += staleGroup;
        } else {
            deleteSkipped = true;
            log.warn("[资源同步] G2 跳过分组消失标记 platformId={} 预计={} 本地={} 阈值={}",
                    platformId, wouldGroup, localGroup, ratio);
        }

        long wouldDevice = deviceAlignment.countWouldStale(taskId, platformId, platformCode);
        if (SyncGuards.allowStale(wouldDevice, localDevice, ratio, settings.getG2MinLocalRows())) {
            staleDevice = realDelete
                    ? deviceAlignment.markRemoved(taskId, platformId, platformCode, now)
                    : deviceAlignment.markMissing(taskId, platformId, platformCode, now);
            if (staleDevice > 0) {
                recordMapper.recordDevice(taskId, platformId, terminalStatus(realDelete), terminalChange(realDelete), now);
            }
            stale += staleDevice;
        } else {
            deleteSkipped = true;
            log.warn("[资源同步] G2 跳过设备消失标记 platformId={} 预计={} 本地={} 阈值={}",
                    platformId, wouldDevice, localDevice, ratio);
        }

        long wouldChannel = channelAlignment.countWouldStale(taskId, platformId);
        if (SyncGuards.allowStale(wouldChannel, localChannel, ratio, settings.getG2MinLocalRows())) {
            staleChannel = realDelete
                    ? channelAlignment.markRemoved(taskId, platformId, now)
                    : channelAlignment.markMissing(taskId, platformId, now);
            if (staleChannel > 0) {
                recordMapper.recordChannel(taskId, platformId, terminalStatus(realDelete), terminalChange(realDelete), now);
            }
            stale += staleChannel;
        } else {
            deleteSkipped = true;
            log.warn("[资源同步] G2 跳过通道消失标记 platformId={} 预计={} 本地={} 阈值={}",
                    platformId, wouldChannel, localChannel, ratio);
        }

        // ---- 共享侧联动（C12 第 2~3 步）----
        // 必须在 res_* 落定之后、同一事务内：通道已置 REMOVED，这里才圈得出"哪些共享该置死"。
        // 只在 real 删除模式下有实际动作：STALE 是"暂未出现"，还不该撤销上级的可见性。
        ShareDisappearService.DisappearResult disappear =
                shareDisappearService.afterMerge(platformId);
        if (disappear.changed()) {
            log.info("[资源同步] 共享联动 platformId={} 通道置死={} 规则失效={} 分组摘除={} 通道恢复={} 规则恢复={}",
                    platformId, disappear.brokenChannels(), disappear.deadRules(),
                    disappear.removedGroups(), disappear.restoredChannels(), disappear.restoredRules());
        }

        // ---- 在线状态跟随（6.6：上游离线 → 上级收到 OFF）----
        // 与消失联动分开：STALE 也可能伴随 offline，但状态是"事实变化"，
        // 不需要等 real 删除模式；同一事务内落 outbox，上级不需要等到下次拉目录才知道。
        int followed = shareStatusFollowService.afterMerge(platformId);
        if (followed > 0) {
            log.info("[资源同步] 状态跟随出站事件 platformId={} 条数={}", platformId, followed);
        }

        // 汇总审计（3.12.6）：一行说清本轮标记了什么。审计是旁路，开关关掉就不写
        if (stale > 0 || deleteSkipped) {
            auditService.record(realDelete ? "SYNC_DELETE" : "SYNC_MARK_STALE",
                    AuditLog.TARGET_PLATFORM, String.valueOf(platformId),
                    "task=" + task.getTaskSn() + " 组织 " + staleOrg + "/分组 " + staleGroup
                            + "/设备 " + staleDevice + "/通道 " + staleChannel
                            + (deleteSkipped ? "（G2 跳过部分类型）" : ""));
        }
        if (restoredOrg + restoredGroup + restoredDevice + restoredChannel > 0) {
            auditService.record("SYNC_RESTORE", AuditLog.TARGET_PLATFORM, String.valueOf(platformId),
                    "task=" + task.getTaskSn() + " 组织 " + restoredOrg + "/分组 " + restoredGroup
                            + "/设备 " + restoredDevice + "/通道 " + restoredChannel);
        }

        deviceAlignment.refreshChannelCount(platformId, now);

        // ---- 指纹 / 版本（M2a 只记录不跳过，M2b 才用它省掉无变化的整轮） ----
        String fingerprint = Long.toHexString(stagingMapper.fingerprintSum(taskId)) + "-" + staged;
        upsertCatalogVer(platformId, tenantId, taskId, fingerprint, (int) staged, now);

        // ---- 收尾 ----
        SyncTask done = new SyncTask();
        done.setId(taskId);
        done.setStatus(SyncTaskStatus.SUCCESS);
        done.setProgress(100);
        done.setExpectedTotal((int) staged);
        done.setRecvTotal((int) staged);
        done.setAddedCount(orgAdded + groupAdded + deviceAdded + channelAdded);
        done.setUpdatedCount(orgUpdated + groupUpdated + deviceUpdated + channelUpdated);
        done.setStaleCount(stale);
        done.setFailedCount((int) orphanChannels);
        done.setDeleteSkipped(deleteSkipped ? 1 : 0);
        done.setGuardRatio(ratio);
        done.setOrgMissingCount((int) (deviceAlignment.countOrgMissing(platformId)
                + channelAlignment.countOrgMissing(platformId)));
        done.setCatalogFingerprint(fingerprint);
        done.setEndTime(now);
        taskMapper.updateById(done);

        // 台账（3.13.1）：与任务状态分开落盘，delete_skipped 与当时的 guard_ratio 必须能回查
        writeLedger(taskId, platformId, platform, task.getBeginTime(), now,
                (int) stagingMapper.countByTaskAndType(taskId, SyncItemType.ORG),
                (int) stagingMapper.countByTaskAndType(taskId, SyncItemType.DEVICE),
                (int) stagingMapper.countByTaskAndType(taskId, SyncItemType.CHANNEL),
                orgAdded, deviceAdded, deviceUpdated, staleDevice,
                deleteSkipped, ratio, done.getOrgMissingCount());

        // ---- 变更事件（3.11.7）----
        // 两条硬约束都落在这一行之后：范围（新增+更新+消失）在写库时已经定死，
        // 明细则靠 update_time = now 在写库之后回读，拿到的是终值。
        // 与资源同事务：事件写不进去就让整轮回滚，绝不允许"资源变了但订阅方不知道"。
        eventEmitter.emitInbound(tenantId, platformId, platformCode, taskId, now,
                done.getAddedCount() + done.getUpdatedCount() + stale,
                orgAdded > 0 || groupAdded > 0 || staleOrg > 0 || staleGroup > 0,
                deleteSkipped);

        // 组织缺失延迟重试（3.8.2）：必须在清 staging 之前登记——重试要拿 parent_code
        // 重走第①步，而它只活在 staging 里。deleteResolved 顺手把已归位的踢出队列，
        // 队列清空后扫描自然不再调度。
        pendingMapper.capture(taskId, tenantId, now);
        pendingMapper.deleteResolved(platformId);

        // 成功收尾：清本轮 + 同域历史残留（3.13.4）。同平台互斥保证此刻没有别的轮次在写，
        // 所以按平台删等价于"本轮 + 历史"，一条语句收完，不留逐轮累积的残渣。
        stagingMapper.deleteByPlatform(platformId);

        // 快照轮次收尾（3.13.2）：异步清理旧轮，保留最近 N 轮；关闭时是空操作。
        // 必须在 staging 之后提交也无妨——快照队列按提交顺序执行，清理一定排在最后一笔写入之后。
        snapshotWriter.roundFinished(platformId);

        log.info("[资源同步] 合并完成 taskId={} platformId={} 收到={} 新增={} 更新={} 消失={} 跳过删除={} 无归属={} 孤儿通道={}",
                taskId, platformId, staged,
                done.getAddedCount(), done.getUpdatedCount(), stale,
                deleteSkipped, done.getOrgMissingCount(), orphanChannels);
    }

    /**
     * 落轮次台账（3.13.1）。
     *
     * <p>为什么要单独一张表而不是复用 {@code sync_task}：前者是执行事实、不可重写，
     * 后者是任务状态、可重跑；两周后回查"那天为什么没删"，看的是这一行里的
     * {@code delete_skipped} 与当时的 {@code guard_ratio}。</p>
     */
    private void writeLedger(Long taskId, Long platformId, Platform platform,
                             LocalDateTime beginTime, LocalDateTime endTime,
                             int orgRecv, int devRecv, int chRecv,
                             int orgInsert, int devInsert, int devUpdate, int devDelete,
                             boolean deleteSkipped, BigDecimal ratio, int orgMissing) {
        SyncRound round = new SyncRound();
        round.setTaskId(taskId);
        round.setPlatformId(platformId);
        round.setPlatformName(platform.getName());
        round.setBeginTime(beginTime == null ? endTime : beginTime);
        round.setEndTime(endTime);
        round.setStatus(SyncRound.DONE);
        round.setOrgRecv(orgRecv);
        round.setDevRecv(devRecv);
        round.setChRecv(chRecv);
        round.setOrgInsert(orgInsert);
        round.setDevInsert(devInsert);
        round.setDevUpdate(devUpdate);
        round.setDevDelete(devDelete);
        round.setDevDup(0);
        round.setDeleteSkipped(deleteSkipped ? 1 : 0);
        round.setGuardRatio(ratio);
        round.setOrgMissing(orgMissing);
        round.setResult(deleteSkipped ? "DELETE_SKIPPED" : "SUCCESS");
        round.setSummary(summary(orgRecv, devRecv, chRecv, orgInsert, devInsert, devUpdate,
                devDelete, deleteSkipped, orgMissing));
        roundRecorder.complete(round);
    }

    private static String summary(int orgRecv, int devRecv, int chRecv,
                                  int orgInsert, int devInsert, int devUpdate, int devDelete,
                                  boolean deleteSkipped, int orgMissing) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("收到 ").append(orgRecv + devRecv + chRecv).append(" 条")
                .append("（组织 ").append(orgRecv)
                .append(" / 设备 ").append(devRecv)
                .append(" / 通道 ").append(chRecv).append("）")
                .append("，组织新增 ").append(orgInsert)
                .append("，设备新增 ").append(devInsert)
                .append("、更新 ").append(devUpdate)
                .append("、消失 ").append(devDelete);
        if (deleteSkipped) {
            sb.append("（删除已跳过）");
        }
        if (orgMissing > 0) {
            sb.append("，未归属 ").append(orgMissing);
        }
        return sb.toString();
    }

    /** 组织建树：解析父 → 逐层插入 → 再解析父 → 根/子 path 收敛 */
    private int mergeOrgTree(Long taskId, Long platformId, long tenantId, LocalDateTime now) {
        orgAlignment.refreshParent(platformId, now);
        int added = insertLoop(() -> orgAlignment.insertReady(taskId, tenantId, now));
        // 父可能在本轮才插入，插入后再解析一次，否则新子行的 parent_id 一直为 NULL
        orgAlignment.refreshParent(platformId, now);
        orgAlignment.repairRoots(platformId, now);
        repairChildrenLoop(() -> orgAlignment.repairChildren(platformId, now));
        return added;
    }

    private int mergeGroupTree(Long taskId, Long platformId, long tenantId, LocalDateTime now) {
        groupAlignment.refreshParent(taskId, now);
        int added = insertLoop(() -> groupAlignment.insertReady(taskId, tenantId, now));
        groupAlignment.refreshParent(taskId, now);
        groupAlignment.repairRoots(platformId, now);
        repairChildrenLoop(() -> groupAlignment.repairChildren(platformId, now));
        return added;
    }

    private int insertLoop(Runnable0 step) {
        int total = 0;
        for (int round = 0; round < settings.getTreeMaxDepth(); round++) {
            int inserted = step.run();
            total += inserted;
            if (inserted == 0) {
                return total;
            }
        }
        log.warn("[资源同步] 建树达到轮数上限 {}，目录里可能存在环", settings.getTreeMaxDepth());
        return total;
    }

    private void repairChildrenLoop(Runnable0 step) {
        for (int round = 0; round < settings.getTreeMaxDepth(); round++) {
            if (step.run() == 0) {
                return;
            }
        }
    }

    /** 恢复行数 >0 才留痕：0 行时留痕语句也可能抓到别的行，顺手省一次往返 */
    private int restoreAndRecord(int restored, Runnable0 record) {
        if (restored <= 0) {
            return 0;
        }
        record.run();
        return restored;
    }

    private static String terminalStatus(boolean realDelete) {
        return realDelete ? ResourceStatus.REMOVED : ResourceStatus.STALE;
    }

    private static String terminalChange(boolean realDelete) {
        return realDelete ? "REMOVE" : "STALE";
    }

    private void upsertCatalogVer(Long platformId, long tenantId, Long taskId,
                                  String fingerprint, int itemCount, LocalDateTime now) {
        PlatformCatalogVer ver = catalogVerMapper.selectById(platformId);
        if (ver == null) {
            ver = new PlatformCatalogVer();
            ver.setPlatformId(platformId);
            ver.setTenantId(tenantId);
            ver.setFingerprint(fingerprint);
            ver.setItemCount(itemCount);
            ver.setLastTaskId(taskId);
            ver.setLastSuccessAt(now);
            ver.setCreateTime(now);
            ver.setUpdateTime(now);
            catalogVerMapper.insert(ver);
            return;
        }
        ver.setFingerprint(fingerprint);
        ver.setItemCount(itemCount);
        ver.setLastTaskId(taskId);
        ver.setLastSuccessAt(now);
        ver.setUpdateTime(now);
        catalogVerMapper.updateById(ver);
    }

    private static String platformCode(Platform platform) {
        if (platform.getCode() != null && !platform.getCode().isBlank()) {
            return platform.getCode().trim();
        }
        if (platform.getLocalDomain() != null && !platform.getLocalDomain().isBlank()) {
            return platform.getLocalDomain().trim();
        }
        throw new ServiceException("平台未配置编码/域，无法完成合并");
    }

    /** 为了把"逐层重复调用"写成循环而引入的最小函数接口（避免装箱 Supplier） */
    @FunctionalInterface
    private interface Runnable0 {
        int run();
    }
}
