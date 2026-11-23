package com.solo.domain.share.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四道防环（C20）。
 *
 * <p>重点是第二步的"跨表识别同一个物理平台"：{@code gb_parent_platform.id} 与
 * {@code platform.id} 是两套 id 空间，只比 id 会漏掉最常见的反向共享，
 * 必须同时按国标码比对。</p>
 */
class ShareGuardTest {

    private static final String DOMAIN = "3402000000";
    private static final long TARGET = 9L;
    private static final String TARGET_GB = "34020000002000000009";

    @Test
    void reverseBySameSourceId() {
        ShareGuard.Origin origin = new ShareGuard.Origin(9L, "9999999999", "", 0, "34020000001310000001");

        assertThat(ShareGuard.check(TARGET, TARGET_GB, origin, DOMAIN, 3))
                .isEqualTo(ShareGuard.Violation.REVERSE);
    }

    @Test
    void reverseBySameGbCodeEvenWhenIdSpaceDiffers() {
        // 来源是入站 platform.id=1，目标上级是 gb_parent_platform.id=9，但国标码相同
        ShareGuard.Origin origin = new ShareGuard.Origin(1L, TARGET_GB, "", 0, "34020000001310000001");

        assertThat(ShareGuard.check(TARGET, TARGET_GB, origin, DOMAIN, 3))
                .isEqualTo(ShareGuard.Violation.REVERSE);
    }

    @Test
    void originChainHitsByIdOrCode() {
        ShareGuard.Origin byId = new ShareGuard.Origin(5L, "1234567890", "1,9,5", 2, "34020000001310000001");
        assertThat(ShareGuard.check(TARGET, TARGET_GB, byId, DOMAIN, 5))
                .isEqualTo(ShareGuard.Violation.ORIGIN_CHAIN);

        ShareGuard.Origin byCode = new ShareGuard.Origin(5L, "1234567890", "1," + TARGET_GB, 2, "34020000001310000001");
        assertThat(ShareGuard.check(TARGET, TARGET_GB, byCode, DOMAIN, 5))
                .isEqualTo(ShareGuard.Violation.ORIGIN_CHAIN);
    }

    @Test
    void hopLimitRejectsWhenNextHopExceeds() {
        ShareGuard.Origin atLimit = new ShareGuard.Origin(5L, "1234567890", "1", 3, "34020000001310000001");
        assertThat(ShareGuard.check(TARGET, TARGET_GB, atLimit, DOMAIN, 3))
                .isEqualTo(ShareGuard.Violation.HOP_LIMIT);

        ShareGuard.Origin justBelow = new ShareGuard.Origin(5L, "1234567890", "1", 2, "12345678901310000001");
        assertThat(ShareGuard.check(TARGET, TARGET_GB, justBelow, DOMAIN, 3)).isNull();
    }

    @Test
    void nonPositiveMaxHopFallsBackToDefault() {
        ShareGuard.Origin origin = new ShareGuard.Origin(5L, "1234567890", "1", ShareGuard.DEFAULT_MAX_HOP, "34020000001310000001");

        assertThat(ShareGuard.check(TARGET, TARGET_GB, origin, DOMAIN, 0))
                .isEqualTo(ShareGuard.Violation.HOP_LIMIT);
    }

    @Test
    void selfDomainOnlyWhenResourceComesFromOutside() {
        // 入站资源却带着本平台域的码：绕了一圈回来
        ShareGuard.Origin inboundLoop = new ShareGuard.Origin(5L, "1234567890", "1", 0, "34020000001310000001");
        assertThat(ShareGuard.check(TARGET, TARGET_GB, inboundLoop, DOMAIN, 3))
                .isEqualTo(ShareGuard.Violation.SELF_DOMAIN);

        // 本地资源用本平台域的码是正常的
        ShareGuard.Origin local = ShareGuard.Origin.local("34020000001310000001");
        assertThat(ShareGuard.check(TARGET, TARGET_GB, local, DOMAIN, 3)).isNull();
    }

    @Test
    void nullTargetOrOriginIsPassedThrough() {
        ShareGuard.Origin origin = new ShareGuard.Origin(5L, "1234567890", "1", 0, "34020000001310000001");

        assertThat(ShareGuard.check(null, TARGET_GB, origin, DOMAIN, 3)).isNull();
        assertThat(ShareGuard.check(TARGET, TARGET_GB, null, DOMAIN, 3)).isNull();
    }

    @Test
    void messageAlwaysTellsUserWhatToDo() {
        String reverse = ShareGuard.message(ShareGuard.Violation.REVERSE, "上级A", "平台B");
        assertThat(reverse).contains("上级A").contains("平台B").contains("换一个上级");

        assertThat(ShareGuard.message(ShareGuard.Violation.HOP_LIMIT, null, null))
                .contains("目标上级").contains("publish.max-hop");
        assertThat(ShareGuard.message(null, "上级A", "平台B")).isEmpty();
    }
}
