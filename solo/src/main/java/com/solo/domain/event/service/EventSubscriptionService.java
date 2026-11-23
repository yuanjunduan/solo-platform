package com.solo.domain.event.service;

import com.solo.domain.event.model.EventSubscription;
import com.solo.infra.mapper.EventSubscriptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 订阅档案的读取与游标记账（3.5.3 表 14 / 6.4）。
 *
 * <p><b>为什么允许"没有订阅就直接建"</b>：首版只有 {@code resource} 一个主题，
 * 而订阅的 scope 预设就等于"应用可见的全部"（都为空 = 不裁剪）。让集成方先调一次
 * 管理接口再连流，只是把同样的默认值提前写一遍。真正限制访问的是 API Key 的
 * {@code gb:event:subscribe} scope 与推送前的 scope 二次过滤，不是这行记录。</p>
 *
 * <p><b>游标为什么只进不退</b>：多实例可能同时推同一份订阅，小的那个把大游标改回去
 * 会让客户端重连时重复回放；SQL 里用 {@code last_event_id < #{seq}} 兜住（见 Mapper）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventSubscriptionService {

    /** 单租户平台的默认租户；OpenApp 本身不带租户字段 */
    public static final long DEFAULT_TENANT_ID = 1L;

    private final EventSubscriptionMapper subscriptionMapper;

    /**
     * 取订阅档案，没有就按默认 scope（全部可见）建一条。
     *
     * <p>并发首连时两个连接可能同时判定"没有"，靠 {@code uk_key(app_id, topic)}
     * 让后到者插入失败，再回查一次即可——不值得为此上分布式锁。</p>
     */
    public EventSubscription loadOrCreate(Long appId, String appKey) {
        EventSubscription existing = subscriptionMapper.selectByAppAndTopic(appId,
                EventSubscription.TOPIC_RESOURCE);
        if (existing != null) {
            return existing;
        }
        EventSubscription created = new EventSubscription();
        created.setTenantId(DEFAULT_TENANT_ID);
        created.setAppId(appId);
        created.setAppKey(appKey);
        created.setTopic(EventSubscription.TOPIC_RESOURCE);
        created.setLastEventId(0L);
        created.setStatus(EventSubscription.STATUS_ENABLED);
        created.setRemark("首连自动创建（scope=全部）");
        try {
            subscriptionMapper.insert(created);
            log.info("[资源事件] 为应用自动创建订阅 appKey={} subscriptionId={}", appKey, created.getId());
            return created;
        } catch (DuplicateKeyException e) {
            EventSubscription raced = subscriptionMapper.selectByAppAndTopic(appId,
                    EventSubscription.TOPIC_RESOURCE);
            if (raced == null) {
                throw e;
            }
            return raced;
        }
    }

    /**
     * 推进服务端游标：<b>只在事件成功写出之后</b>调。
     * 推送中途断开时游标停在最后一条已发出的位置，重连即从这里续。
     */
    public void advance(EventSubscription subscription, long seq) {
        if (seq <= 0) {
            return;
        }
        try {
            subscriptionMapper.advanceCursor(subscription.getId(), seq, LocalDateTime.now());
        } catch (RuntimeException e) {
            // 游标是"服务端记账"，客户端仍带着自己的 Last-Event-ID；
            // 这里失败最多让下次重连多重放一批，不能因此掐断正在推送的连接。
            log.warn("[资源事件] 游标推进失败 subscriptionId={} seq={} err={}",
                    subscription.getId(), seq, e.getMessage());
        }
    }
}
