package com.solo.domain.share.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SUBTREE 命中判定（3.9.2）。
 *
 * <p>锁住那个经典误匹配：{@code /1/5/} 的规则不能把兄弟 {@code /1/50/} 圈进来。
 * 一旦错了，组织换父或新建同级组织时会静默多共享一批通道。</p>
 */
class ShareScopeResolverTest {

    @Test
    void exactAndDescendantPathsAreCovered() {
        assertThat(ShareScopeResolver.covers("/1/5/", "/1/5/")).isTrue();
        assertThat(ShareScopeResolver.covers("/1/5/", "/1/5/9/")).isTrue();
        assertThat(ShareScopeResolver.covers("/1/5/", "/1/5/9/20/")).isTrue();
    }

    @Test
    void siblingWithSharedStringPrefixIsNotCovered() {
        assertThat(ShareScopeResolver.covers("/1/5/", "/1/50/")).isFalse();
        assertThat(ShareScopeResolver.covers("/1/5/", "/1/50/7/")).isFalse();
        assertThat(ShareScopeResolver.covers("/1/5/", "/1/")).isFalse();
    }

    @Test
    void rootRuleCoversEverythingButBlankCandidate() {
        assertThat(ShareScopeResolver.covers("/", "/1/")).isTrue();
        assertThat(ShareScopeResolver.covers(null, "/1/5/")).isTrue();
        assertThat(ShareScopeResolver.covers("/", null)).isFalse();
        assertThat(ShareScopeResolver.covers("/", " ")).isFalse();
    }

    @Test
    void messyPathsAreNormalizedBeforeCompare() {
        assertThat(ShareScopeResolver.covers("1/5", "1/5/9")).isTrue();
        assertThat(ShareScopeResolver.covers("//1//5//", "/1/5/9/")).isTrue();
    }

    @Test
    void coversAnyHitsAtLeastOneRule() {
        List<String> rules = List.of("/1/5/", "/1/8/");

        assertThat(ShareScopeResolver.coversAny(rules, "/1/8/3/")).isTrue();
        assertThat(ShareScopeResolver.coversAny(rules, "/1/9/")).isFalse();
        assertThat(ShareScopeResolver.coversAny(List.of(), "/1/9/")).isFalse();
        assertThat(ShareScopeResolver.coversAny(null, "/1/9/")).isFalse();
    }
}
