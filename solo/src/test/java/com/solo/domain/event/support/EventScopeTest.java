package com.solo.domain.event.support;

import com.solo.domain.event.model.ChangeEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订阅 scope 的二次过滤（3.12.1 / Q10）。
 *
 * <p>这一层是越权的最后一道闸：订阅时校验的是"建订阅那一刻"的结论，
 * 事件的 {@code platform_id / org_path} 来自实际落库，两者必须再过一遍。</p>
 */
class EventScopeTest {

    private static ChangeEvent detail(Long platformId, String orgPath) {
        ChangeEvent event = new ChangeEvent();
        event.setShape(ChangeEvent.SHAPE_DETAIL);
        event.setPlatformId(platformId);
        event.setOrgPath(orgPath);
        return event;
    }

    private static ChangeEvent refresh(Long platformId) {
        ChangeEvent event = new ChangeEvent();
        event.setShape(ChangeEvent.SHAPE_REFRESH);
        event.setPlatformId(platformId);
        event.setOrgPath(null);
        return event;
    }

    @Test
    void emptyScopeAcceptsEverything() {
        EventScope scope = EventScope.of(null, null);

        assertThat(scope.accepts(detail(5L, "/1/5/"))).isTrue();
        assertThat(scope.accepts(detail(null, null))).isTrue();
        assertThat(scope.accepts(refresh(null))).isTrue();
    }

    @Test
    void platformScopeRejectsOtherPlatform() {
        EventScope scope = EventScope.of(5L, null);

        assertThat(scope.accepts(detail(5L, "/1/"))).isTrue();
        assertThat(scope.accepts(detail(6L, "/1/"))).isFalse();
        // 事件不带平台 = 全局刷新，宁多勿漏
        assertThat(scope.accepts(detail(null, "/1/"))).isTrue();
        assertThat(scope.accepts(refresh(null))).isTrue();
    }

    @Test
    void orgScopeOnlyAcceptsSubtree() {
        EventScope scope = EventScope.of(null, "/1/5/");

        assertThat(scope.accepts(detail(null, "/1/5/"))).isTrue();
        assertThat(scope.accepts(detail(null, "/1/5/9/"))).isTrue();
        assertThat(scope.accepts(detail(null, "/1/"))).isFalse();
        assertThat(scope.accepts(detail(null, "/1/6/"))).isFalse();
        // 兄弟节点 id 以 "5" 开头也不能被 /1/5/ 吞进来
        assertThat(scope.accepts(detail(null, "/1/50/"))).isFalse();
    }

    @Test
    void orgScopeRejectsEventWithoutLocationButAcceptsRefreshAll() {
        EventScope scope = EventScope.of(null, "/1/5/");

        // 定位不明的事件不能因为"看起来没越权"就发出去
        assertThat(scope.accepts(detail(null, null))).isFalse();
        // REFRESH_ALL 只说"去拉快照"，快照自己还会按 scope 裁剪
        assertThat(scope.accepts(refresh(null))).isTrue();
    }

    @Test
    void normalizesMessyOrgPath() {
        // 历史脏数据里 /1/5 与 /1/5/ 两种写法都出现过，入口规范一次后续比较才有意义
        EventScope scope = EventScope.of(null, "1/5");

        assertThat(scope.orgPath()).isEqualTo("/1/5/");
        assertThat(scope.accepts(detail(null, "1/5/9"))).isTrue();
    }

    @Test
    void labelsMatchDocumentSamples() {
        assertThat(EventScope.of(5L, null).label()).isEqualTo("platform:5");
        assertThat(EventScope.of(null, "/1/2/").label()).isEqualTo("org:/1/2/");
        assertThat(EventScope.of(null, null).label()).isEqualTo("all");

        assertThat(EventScope.labelOf(detail(7L, "/1/"))).isEqualTo("platform:7");
        assertThat(EventScope.labelOf(detail(null, "/1/"))).isEqualTo("org:/1/");
        assertThat(EventScope.labelOf(refresh(null))).isEqualTo("all");
    }
}
