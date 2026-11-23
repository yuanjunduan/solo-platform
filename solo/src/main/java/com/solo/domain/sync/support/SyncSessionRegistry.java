package com.solo.domain.sync.support;

import com.solo.domain.sync.spi.CatalogSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 轮次 → 协议会话句柄（只为了"能中止"）。
 *
 * <p>这里存的是<b>句柄</b>不是状态：取消/超时要在本进程里把投递口摘掉，句柄无法外置
 * （SIP 响应本来就只能在进程内接收）。轮次的真实状态在 {@code sync_task} 与 Redis 里，
 * 所以本表丢了最多是"少摘一个投递口"，超时兜底会补上。</p>
 */
@Slf4j
@Component
public class SyncSessionRegistry {

    private final ConcurrentMap<Long, CatalogSession> sessions = new ConcurrentHashMap<>();

    public void put(Long taskId, CatalogSession session) {
        sessions.put(taskId, session);
    }

    /** 本进程是否还握着该轮次的投递口（残留清理用来区分"本进程在跑"和"上次运行的遗留"） */
    public boolean contains(Long taskId) {
        return sessions.containsKey(taskId);
    }

    public void close(Long taskId) {
        CatalogSession session = sessions.remove(taskId);
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (RuntimeException e) {
            log.warn("[资源同步] 关闭会话句柄失败 taskId={} err={}", taskId, e.getMessage());
        }
    }
}
