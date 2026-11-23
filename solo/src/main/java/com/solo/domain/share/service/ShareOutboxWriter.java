package com.solo.domain.share.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.solo.common.ServiceException;
import com.solo.domain.share.model.OutboxEvent;
import com.solo.domain.share.model.ShareStatus;
import com.solo.domain.share.support.PublishDegradeDecider;
import com.solo.domain.share.support.ShareEvent;
import com.solo.domain.share.support.ShareSettings;
import com.solo.gb28181.mapper.GbParentPlatformMapper;
import com.solo.infra.mapper.OutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 出站事件的落库入口（C15）。
 *
 * <p><b>{@code Propagation.MANDATORY} 是刻意的</b>：共享写与事件写必须同一个事务。
 * 如果哪天有人在事务外调用它，Spring 会直接抛异常，而不是留下
 * "共享生效了、上级永远收不到"或"消息发了、共享回滚了"这两种最难查的窗口。</p>
 *
 * <p>降级判定只在这里做一次（{@link PublishDegradeDecider}）：调用方只负责说清
 * "改了哪些节点"，是否合并成 REFRESH 由本类按当时阈值决定——阈值只有一处，改配置不会出现两套行为。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareOutboxWriter {

    private static final long DEFAULT_TENANT_ID = 1L;

    private final OutboxMapper outboxMapper;
    private final GbParentPlatformMapper parentPlatformMapper;
    private final ShareSettings settings;
    private final ObjectMapper objectMapper;

    /**
     * 把一批事件写入 outbox（序号在平台锁内统一分配）。
     *
     * @param treeRebuilt 虚拟组织树是否重建过；重建时逐条 ADD 永远对不上父节点，直接降级
     * @return 实际写入的条数（降级时为 1，无事件为 0）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int submit(Long platformId, List<ShareEvent> events, boolean treeRebuilt) {
        int planned = events == null ? 0 : events.size();
        PublishDegradeDecider.Shape shape = PublishDegradeDecider.decide(
                planned, settings.getDetailMax(), treeRebuilt);
        if (shape == PublishDegradeDecider.Shape.NONE) {
            return 0;
        }
        List<ShareEvent> target = shape == PublishDegradeDecider.Shape.REFRESH
                ? List.of(ShareEvent.refresh(treeRebuilt ? "虚拟组织树重建" : "单轮变更超过明细上限"))
                : events;
        int rows = write(platformId, target);
        if (shape == PublishDegradeDecider.Shape.REFRESH) {
            log.info("[上级共享] 超量降级为 REFRESH platform={} 原计划={} 阈值={}",
                    platformId, planned, settings.getDetailMax());
        }
        return rows;
    }

    /**
     * 分配序号并批量插入。
     * <p>锁平台行 → 取本平台最大号 → 依次 +1：同一上级收到的序号必须连续，
     * 否则上级判新旧会出错。{@code version} 直接用 seq（目录版本 = 事件序号）。</p>
     */
    private int write(Long platformId, List<ShareEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        Long locked = parentPlatformMapper.lockById(platformId);
        if (locked == null) {
            throw new ServiceException("上级平台不存在：" + platformId);
        }
        long seq = outboxMapper.maxSeq(platformId);
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> rows = new ArrayList<>(events.size());
        for (ShareEvent event : events) {
            if (event == null) {
                continue;
            }
            seq++;
            OutboxEvent row = new OutboxEvent();
            row.setId(IdWorker.getId());
            row.setTenantId(DEFAULT_TENANT_ID);
            row.setPlatformId(platformId);
            row.setSeqNo(seq);
            row.setEventType(event.eventType());
            row.setRefType(event.refType());
            row.setRefId(event.refId());
            row.setRefCode(event.refCode());
            row.setExposedCode(event.exposedCode());
            row.setPayload(toJson(event.payload()));
            row.setVersion(seq);
            row.setStatus(ShareStatus.OUTBOX_PENDING);
            row.setRetryCount(0);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return 0;
        }
        outboxMapper.insertBatch(rows);
        log.info("[上级共享] 写入出站事件 platform={} 条数={} 首号={} 末号={}",
                platformId, rows.size(), rows.get(0).getSeqNo(), rows.get(rows.size() - 1).getSeqNo());
        return rows.size();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            // 与事件流同口径：payload 只是报文素材，序列化失败不能拖垮共享本身
            log.warn("[上级共享] payload 序列化失败（事件仍落库） err={}", e.getMessage());
            return null;
        }
    }
}
