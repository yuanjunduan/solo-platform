package com.solo.domain.resource.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 物化路径契约。
 *
 * <p>重点是那个经典误匹配：{@code /1/5/} 的子树不能把兄弟 {@code /1/50/} 圈进来。
 * 这条一错，组织换父就会静默改掉别的组织的资源归属，而且事后极难发现。</p>
 */
class ResourcePathTest {

    @Test
    void buildsRootAndChildPath() {
        assertThat(ResourcePath.ofRoot(1L)).isEqualTo("/1/");
        assertThat(ResourcePath.ofChild("/1/", 5L)).isEqualTo("/1/5/");
        assertThat(ResourcePath.ofChild("/1/5/", 9L)).isEqualTo("/1/5/9/");
    }

    @Test
    void normalizesMessyInput() {
        assertThat(ResourcePath.normalize("1/5")).isEqualTo("/1/5/");
        assertThat(ResourcePath.normalize("/1/5")).isEqualTo("/1/5/");
        assertThat(ResourcePath.normalize("//1//5//")).isEqualTo("/1/5/");
        assertThat(ResourcePath.normalize("  /1/5/  ")).isEqualTo("/1/5/");
        assertThat(ResourcePath.normalize(null)).isEqualTo("/");
        assertThat(ResourcePath.normalize(" ")).isEqualTo("/");
    }

    @Test
    void levelEqualsPathSegmentCount() {
        assertThat(ResourcePath.levelOf("/")).isZero();
        assertThat(ResourcePath.levelOf("/1/")).isEqualTo(1);
        assertThat(ResourcePath.levelOf("/1/5/9/")).isEqualTo(3);
    }

    @Test
    void subtreePrefixDoesNotSwallowSibling() {
        // 兄弟节点 id 是前缀字符串时必须视为不同子树
        assertThat(ResourcePath.isUnder("/1/50/", "/1/5/")).isFalse();
        assertThat(ResourcePath.isUnder("/1/5/9/", "/1/5/")).isTrue();
        assertThat(ResourcePath.isUnder("/1/5/", "/1/5/")).isFalse();
    }

    @Test
    void resolvesParentPath() {
        assertThat(ResourcePath.parentOf("/1/5/9/")).isEqualTo("/1/5/");
        assertThat(ResourcePath.parentOf("/1/5/")).isEqualTo("/1/");
        assertThat(ResourcePath.parentOf("/1/")).isEqualTo("/");
        assertThat(ResourcePath.parentOf("/")).isEqualTo("/");
    }
}
