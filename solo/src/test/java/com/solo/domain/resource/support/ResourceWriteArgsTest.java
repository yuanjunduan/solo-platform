package com.solo.domain.resource.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 注解层取参（3.6.5 第一层）。
 *
 * <p>取错参数的后果不是报错，而是守卫去查了另一条资源——表现为"有时拦、有时不拦"，
 * 人工点页面几乎发现不了，所以这里的每个兜底分支都要钉住。</p>
 */
class ResourceWriteArgsTest {

    @Test
    @DisplayName("按参数名命中 → 取到 id")
    void picksByIdName() {
        String[] names = {"id", "req"};
        Object[] args = {123L, new Object()};
        assertEquals(123L, ResourceWriteArgs.pickId(names, args, "id"));
    }

    @Test
    @DisplayName("参数顺序调换后仍按名字取，不会串位")
    void picksByPositionIndependently() {
        String[] names = {"req", "id"};
        Object[] args = {new Object(), 456L};
        assertEquals(456L, ResourceWriteArgs.pickId(names, args, "id"));
    }

    @Test
    @DisplayName("名字不匹配 → 返回 null（由切面告警放行，第二三层兜底）")
    void missesWhenNameNotPresent() {
        assertNull(ResourceWriteArgs.pickId(new String[]{"resourceId"}, new Object[]{1L}, "id"));
    }

    @Test
    @DisplayName("值为 null / 非数字 / 非正数 → 视为取不到")
    void rejectsUnusableValues() {
        assertNull(ResourceWriteArgs.pickId(new String[]{"id"}, new Object[]{null}, "id"));
        assertNull(ResourceWriteArgs.pickId(new String[]{"id"}, new Object[]{"abc"}, "id"));
        assertNull(ResourceWriteArgs.pickId(new String[]{"id"}, new Object[]{0L}, "id"));
        assertNull(ResourceWriteArgs.pickId(new String[]{"id"}, new Object[]{-5L}, "id"));
    }

    @Test
    @DisplayName("字符串形式的 id 也接受（路径变量被当字符串传时）")
    void parsesStringId() {
        assertEquals(789L, ResourceWriteArgs.pickId(new String[]{"id"}, new Object[]{" 789 "}, "id"));
    }

    @Test
    @DisplayName("形参名缺失（未开 -parameters）→ 返回 null 而不是猜")
    void returnsNullWhenNamesMissing() {
        assertNull(ResourceWriteArgs.pickId(null, new Object[]{1L}, "id"));
        assertNull(ResourceWriteArgs.pickId(new String[]{"id"}, null, "id"));
    }
}
