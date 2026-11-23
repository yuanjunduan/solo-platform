package com.solo.domain.event.service;

import com.solo.domain.event.model.ChangeEvent;
import com.solo.domain.event.support.EventSettings;
import com.solo.domain.event.support.EventShape;
import com.solo.domain.event.support.EventShapeDecider;
import com.solo.domain.resource.model.ResourceStatus;
import com.solo.infra.mapper.ChangeEventReadMapper;
import com.solo.infra.mapper.projection.ChangedRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同步收尾时把"本轮改了什么"翻译成事件（3.11.7）。
 *
 * <p>顺序按文档的两条硬要求来：</p>
 * <ol>
 *   <li><b>写库前定范围</b>：调用方传入本轮影响行数（三条集合 SQL 的真实影响行数之和），
 *       这个数就是"变化范围"的大小，不需要再把 code 全捞出来判；</li>
 *   <li><b>写库后回读明细</b>：靠 {@code update_time = now} 选出本轮触碰过的行，
 *       此时暴露码、组织 id、在线状态都已是终值。</li>
 * </ol>
 *
 * <p><b>先判降级再回读</b>：树重建 / 删被跳过 / 变化数超阈值时直接 {@code REFRESH_ALL}，
 * 一条明细都不回读——5 万条明细的回读本身就是一次全表扫。</p>
 *
 * <p>异常不吞：事件与资源同事务，写入失败要让整轮回滚（见 {@link ChangeEventPublisher}）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncEventEmitter {

    private final ChangeEventReadMapper readMapper;
    private final ChangeEventPublisher publisher;
    private final EventSettings settings;

    /**
     * @param changedCount  本轮影响行数（新增 + 更新 + 消失标记，取真实影响行数）
     * @param treeRebuilt   组织/分组树是否重建（新增、换父、删除任一发生）
     * @param deleteSkipped 是否有删除被 G2 跳过
     */
    public void emitInbound(Long tenantId, Long platformId, String platformCode, Long taskId,
                            LocalDateTime now, int changedCount,
                            boolean treeRebuilt, boolean deleteSkipped) {
        if (changedCount <= 0) {
            // 无变化不推送：避免"心跳式刷新"把订阅方的增量逻辑打成全量
            return;
        }
        int detailMax = Math.max(1, settings.getDetailMax());
        if (treeRebuilt || deleteSkipped || changedCount > detailMax) {
            publisher.publishRefresh(tenantId, ChangeEvent.SOURCE_INBOUND, platformId, taskId,
                    degradeReason(treeRebuilt, deleteSkipped, changedCount, detailMax));
            return;
        }

        List<ChangeEvent> details = readDetails(platformId, platformCode, now, detailMax + 1);
        EventShape shape = EventShapeDecider.decide(changedCount, details.size(),
                treeRebuilt, deleteSkipped, detailMax);
        switch (shape) {
            case NONE -> {
                // changedCount > 0 已经排除，这里只是防御
            }
            case REFRESH -> publisher.publishRefresh(tenantId, ChangeEvent.SOURCE_INBOUND, platformId,
                    taskId, "明细回读为空或条目不足");
            case DETAIL -> publisher.publishDetails(tenantId, ChangeEvent.SOURCE_INBOUND, platformId,
                    taskId, details);
        }
    }

    private List<ChangeEvent> readDetails(Long platformId, String platformCode,
                                          LocalDateTime now, int limit) {
        List<ChangeEvent> events = new ArrayList<>();
        append(events, readMapper.readOrgChanges(platformId, now, platformCode, limit),
                ChangeEvent.ITEM_ORG, now);
        append(events, readMapper.readDeviceChanges(platformId, now, platformCode, limit),
                ChangeEvent.ITEM_DEVICE, now);
        append(events, readMapper.readChannelChanges(platformId, now, limit),
                ChangeEvent.ITEM_CHANNEL, now);
        return events;
    }

    private void append(List<ChangeEvent> target, List<ChangedRow> rows, String itemType,
                        LocalDateTime now) {
        if (rows == null) {
            return;
        }
        for (ChangedRow row : rows) {
            target.add(publisher.detail(itemType, row.getCode(), changeTypeOf(row, now),
                    row.getOrgPath(), payloadOf(row)));
        }
    }

    /**
     * REMOVED 先判：消失标记也写 {@code update_time = now}，
     * 若按 create_time 判会把它误当 UPDATED。
     */
    private static String changeTypeOf(ChangedRow row, LocalDateTime now) {
        String status = row.getStatus();
        if (ResourceStatus.STALE.equals(status) || ResourceStatus.REMOVED.equals(status)) {
            return ChangeEvent.CHANGE_REMOVED;
        }
        if (row.getCreateTime() != null && row.getCreateTime().equals(now)) {
            return ChangeEvent.CHANGE_ADDED;
        }
        return ChangeEvent.CHANGE_UPDATED;
    }

    private static Map<String, Object> payloadOf(ChangedRow row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", row.getName());
        payload.put("orgPath", row.getOrgPath());
        payload.put("status", row.getStatus());
        if (row.getOnlineStatus() != null) {
            payload.put("onlineStatus", row.getOnlineStatus());
        }
        return payload;
    }

    private static String degradeReason(boolean treeRebuilt, boolean deleteSkipped,
                                        int changedCount, int detailMax) {
        if (treeRebuilt) {
            return "组织/分组树有重建";
        }
        if (deleteSkipped) {
            return "删除被闸门跳过，明细语义不完整";
        }
        return "变化 " + changedCount + " 条超过阈值 " + detailMax;
    }
}
