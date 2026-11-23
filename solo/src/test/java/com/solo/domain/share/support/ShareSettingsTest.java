package com.solo.domain.share.support;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站退避与参数下限（6.5）。
 *
 * <p>退避必须是指数而不是固定间隔：上级平台离线通常不是"1 秒后就恢复"，
 * 固定 30 秒重试 5 次＝两分半就把机会用光。同时要有 10 分钟封顶，
 * 避免第 5 次重试等到几十分钟后、用户以为系统卡死。</p>
 */
class ShareSettingsTest {

    private ShareSettings settings(long backoffMs) {
        ShareSettings s = new ShareSettings();
        ReflectionTestUtils.setField(s, "retryBackoffMs", backoffMs);
        return s;
    }

    @Test
    void backoffGrowsExponentially() {
        ShareSettings s = settings(30_000L);

        assertThat(s.backoffMs(1)).isEqualTo(30_000L);
        assertThat(s.backoffMs(2)).isEqualTo(60_000L);
        assertThat(s.backoffMs(3)).isEqualTo(120_000L);
        assertThat(s.backoffMs(4)).isEqualTo(240_000L);
    }

    @Test
    void backoffIsCappedAtTenMinutes() {
        ShareSettings s = settings(30_000L);

        assertThat(s.backoffMs(5)).isEqualTo(480_000L);
        assertThat(s.backoffMs(6)).isEqualTo(600_000L);
        assertThat(s.backoffMs(30)).isEqualTo(600_000L);
    }

    @Test
    void nonPositiveRetryCountBehavesLikeFirstRetry() {
        ShareSettings s = settings(30_000L);

        assertThat(s.backoffMs(0)).isEqualTo(30_000L);
        assertThat(s.backoffMs(-3)).isEqualTo(30_000L);
    }

    @Test
    void baseBelowOneSecondIsClamped() {
        ShareSettings s = settings(10L);

        assertThat(s.backoffMs(1)).isEqualTo(1_000L);
        assertThat(s.backoffMs(2)).isEqualTo(2_000L);
    }
}
