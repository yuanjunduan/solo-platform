package com.solo.gb28181.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每跳独立互斥与冷却（6.6 / T5.3）。
 *
 * <p>最需要盯住的两件事：① key 必须按"跳"隔离——上级 A 的失败不能锁住上级 B；
 * ② 冷却只影响自己这个 key，过期后立刻恢复，不能变成永久封锁。</p>
 */
class MediaHopGuardTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final MediaHopGuard guard = new MediaHopGuard(now::get);

    @Test
    @DisplayName("同一条腿同时只允许一个请求在飞")
    void rejectsSecondAcquireOnSameKey() {
        String key = MediaHopGuard.inboundKey("34020000001320000001_34020000001310000001");

        assertThat(guard.tryAcquire(key)).isTrue();
        assertThat(guard.tryAcquire(key)).isFalse();

        guard.release(key);
        assertThat(guard.tryAcquire(key)).isTrue();
    }

    @Test
    @DisplayName("入站腿与出站腿互不干扰；不同上级各自独立")
    void hopsAreIsolated() {
        String inbound = MediaHopGuard.inboundKey("dev_chan");
        String outA = MediaHopGuard.outboundKey(9L, "34029900001310000042");
        String outB = MediaHopGuard.outboundKey(10L, "34029900001310000042");

        assertThat(guard.tryAcquire(inbound)).isTrue();
        assertThat(guard.tryAcquire(outA)).isTrue();
        assertThat(guard.tryAcquire(outB)).isTrue();
        assertThat(guard.tryAcquire(outA)).isFalse();
        assertThat(guard.inFlightCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("冷却期内剩余时间递减，到期后自动放行并回收条目")
    void cooldownExpires() {
        String key = MediaHopGuard.outboundKey(9L, "34029900001310000042");
        guard.cooldown(key, 3_000L);

        assertThat(guard.cooldownRemainingMs(key)).isEqualTo(3_000L);

        now.addAndGet(1_500L);
        assertThat(guard.cooldownRemainingMs(key)).isEqualTo(1_500L);

        now.addAndGet(1_500L);
        assertThat(guard.cooldownRemainingMs(key)).isZero();
        assertThat(guard.coolingCount()).isZero();
    }

    @Test
    @DisplayName("冷却只作用于自己这个 key")
    void cooldownIsPerKey() {
        String outA = MediaHopGuard.outboundKey(9L, "chan-a");
        String outB = MediaHopGuard.outboundKey(9L, "chan-b");
        guard.cooldown(outA, 5_000L);

        assertThat(guard.cooldownRemainingMs(outA)).isPositive();
        assertThat(guard.cooldownRemainingMs(outB)).isZero();
    }

    @Test
    @DisplayName("冷却时长 <= 0 视为关闭，不产生任何记录")
    void nonPositiveCooldownIsIgnored() {
        String key = MediaHopGuard.outboundKey(9L, "chan");

        guard.cooldown(key, 0L);
        guard.cooldown(key, -1L);

        assertThat(guard.cooldownRemainingMs(key)).isZero();
        assertThat(guard.coolingCount()).isZero();
    }

    @Test
    @DisplayName("clearCooldown 让刚恢复的通道立刻可点播")
    void clearCooldownUnblocksImmediately() {
        String key = MediaHopGuard.outboundKey(9L, "chan");
        guard.cooldown(key, 60_000L);
        assertThat(guard.cooldownRemainingMs(key)).isPositive();

        guard.clearCooldown(key);

        assertThat(guard.cooldownRemainingMs(key)).isZero();
    }

    @Test
    @DisplayName("key 为空时放行且不抛异常（参数问题交给业务层报错）")
    void blankKeysAreSafe() {
        assertThat(guard.tryAcquire(null)).isTrue();
        assertThat(guard.tryAcquire(" ")).isTrue();
        guard.release(null);
        guard.cooldown(null, 1_000L);
        assertThat(guard.cooldownRemainingMs(null)).isZero();
    }

    @Test
    @DisplayName("key 格式：入站按 streamId，出站按「上级 + 上级通道编码」")
    void keyFormat() {
        assertThat(MediaHopGuard.inboundKey("dev_chan_2")).isEqualTo("in:dev_chan_2");
        assertThat(MediaHopGuard.outboundKey(9L, "34029900001310000042"))
                .isEqualTo("out:9:34029900001310000042");
    }
}
