package com.solo.domain.event.service;

import com.solo.domain.event.model.ChangeEvent;
import com.solo.domain.event.support.EventSignal;
import com.solo.infra.mapper.ChangeEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 变更事件的写入（3.11.7）。
 *
 * <p><b>写入失败要不要回滚业务</b>：要。事件与资源变更<b>同事务</b>——
 * 少一条事件意味着订阅方永久漏掉一批变化，而订阅方没有自己的对账手段
 * （首版不做增量对账），所以宁可让这一轮同步失败重跑。这一点与审计/快照的
 * "旁路可丢"是相反的语义，代码里必须能看出来（这里没有任何 try/catch）。</p>
 *
 * <p>唯一的例外是 payload 序列化：它只是给客户端看的摘要，序列化失败就让 payload 为空，
 * 事件本身仍然落库（定位字段 code/item_type 都在列上）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeEventPublisher {

    /** 降级事件里引用的全量快照地址（M3-S5 提供） */
    public static final String SNAPSHOT_PATH = "/open-api/v1/snapshot";

    private final ChangeEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final EventSignal signal;

    /**
     * 发一条 {@code REFRESH_ALL}，占住一个游标。
     *
     * @return 新事件的游标；调用方通常不需要，仅日志/断言用
     */
    public Long publishRefresh(Long tenantId, String sourceType, Long platformId,
                               Long taskId, String reason) {
        ChangeEvent event = common(tenantId, sourceType, platformId, taskId);
        event.setShape(ChangeEvent.SHAPE_REFRESH);
        event.setChangeType(ChangeEvent.CHANGE_REFRESH);
        event.setPayload(toJson(Map.of(
                "reason", reason == null ? "" : reason,
                "snapshot", SNAPSHOT_PATH)));
        eventMapper.insert(event);
        signal.raise();
        log.info("[资源事件] REFRESH_ALL tenant={} platform={} task={} 原因={}",
                tenantId, platformId, taskId, reason);
        return event.getSeq();
    }

    /**
     * 批量写明细。调用方必须已经做完了降级判定（{@code <= detailMax}），
     * 这里不再二次判断——判定规则只有一处，见 {@code EventShapeDecider}。
     */
    public int publishDetails(Long tenantId, String sourceType, Long platformId, Long taskId,
                              List<ChangeEvent> details) {
        if (details == null || details.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        for (ChangeEvent event : details) {
            event.setTenantId(tenantId);
            event.setSourceType(sourceType);
            event.setPlatformId(platformId);
            event.setTaskId(taskId);
            event.setShape(ChangeEvent.SHAPE_DETAIL);
            event.setCreateTime(now);
        }
        int rows = eventMapper.insertBatch(details);
        signal.raise();
        log.info("[资源事件] 明细 tenant={} platform={} task={} 条数={}",
                tenantId, platformId, taskId, rows);
        return rows;
    }

    /** 组装一条尚未填公共字段的明细（调用方只关心 itemType/code/changeType/orgPath/payload） */
    public ChangeEvent detail(String itemType, String code, String changeType,
                              String orgPath, Object payload) {
        ChangeEvent event = new ChangeEvent();
        event.setItemType(itemType);
        event.setCode(code);
        event.setChangeType(changeType);
        event.setOrgPath(orgPath);
        event.setPayload(toJson(payload));
        return event;
    }

    private ChangeEvent common(Long tenantId, String sourceType, Long platformId, Long taskId) {
        ChangeEvent event = new ChangeEvent();
        event.setTenantId(tenantId);
        event.setSourceType(sourceType);
        event.setPlatformId(platformId);
        event.setTaskId(taskId);
        event.setCreateTime(LocalDateTime.now());
        return event;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            // 事件本身仍要落库：payload 只是摘要
            log.warn("[资源事件] payload 序列化失败（事件仍落库） err={}", e.getMessage());
            return null;
        }
    }
}
