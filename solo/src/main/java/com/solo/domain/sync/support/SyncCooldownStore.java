package com.solo.domain.sync.support;

import com.solo.common.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 失败冷却窗口（3.8.2 三段会话第 3 条）：失败轮收尾后的一段时间内拒绝再次触发。
 *
 * <p>没有它，运维点"重新同步"会与本端还没熄灭的会话撞车，表现是"偶尔提示平台忙、
 * 偶尔数据对不上"，且难复现。窗口长度 = 超时 + 额外秒数，存 Redis 带 TTL，
 * 重启与多实例都不丢（C10：不放进程内变量）。</p>
 *
 * <p>Redis 不可用时<b>放行</b>而不是拦住：冷却只是防撞车，不该变成新的单点。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncCooldownStore {

    private static final String KEY_PREFIX = "solo:sync:cooldown:";

    private final StringRedisTemplate redis;
    private final SyncSettings settings;

    public void markFailed(Long platformId) {
        try {
            redis.opsForValue().set(KEY_PREFIX + platformId,
                    String.valueOf(System.currentTimeMillis()), Duration.ofMillis(settings.cooldownMs()));
            log.info("[资源同步] 进入冷却窗口 platformId={} 时长={}ms", platformId, settings.cooldownMs());
        } catch (RuntimeException e) {
            log.warn("[资源同步] 写冷却窗口失败 platformId={} err={}", platformId, e.getMessage());
        }
    }

    /** 还要等多久（毫秒）；0 表示可以触发 */
    public long remainingMs(Long platformId) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + platformId);
            if (value == null) {
                return 0L;
            }
            long failedAt = Long.parseLong(value);
            return Math.max(0L, failedAt + settings.cooldownMs() - System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("[资源同步] 读冷却窗口失败 platformId={} err={}", platformId, e.getMessage());
            return 0L;
        }
    }

    public void assertReady(Long platformId) {
        long remaining = remainingMs(platformId);
        if (remaining > 0) {
            throw new ServiceException("SYNC_COOLING_DOWN: 上一轮刚失败，还需等待 " + remaining + "ms");
        }
    }
}
