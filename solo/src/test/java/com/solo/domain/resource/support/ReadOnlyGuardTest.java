package com.solo.domain.resource.support;

import com.solo.common.ServiceException;
import com.solo.domain.resource.model.SourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只读守卫：外部同步资源一律拒写（C2）。
 */
class ReadOnlyGuardTest {

    private final ReadOnlyGuard guard = new ReadOnlyGuard();

    @Test
    void nativeResourceIsWritable() {
        assertThatCode(() -> guard.requireWritable(SourceType.NATIVE, "删除")).doesNotThrowAnyException();
        assertThat(guard.isWritable(SourceType.NATIVE)).isTrue();
    }

    @Test
    void inboundResourceIsRejectedWithForbidden() {
        assertThatThrownBy(() -> guard.requireWritable(SourceType.INBOUND, "删除"))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> {
                    ServiceException se = (ServiceException) e;
                    assertThat(se.getCode()).isEqualTo(403);
                    // 文案必须说清"为什么不能改"和"该由谁改"，否则用户只会反复重试
                    assertThat(se.getMessage()).contains("外部平台").contains("删除");
                });
        assertThat(guard.isWritable(SourceType.INBOUND)).isFalse();
    }

    @Test
    void unknownSourceTypeIsTreatedAsReadOnly() {
        // 兜底方向必须偏保守：来源字段脏掉时默认拒写，而不是默认放行
        assertThatThrownBy(() -> guard.requireWritable(null, "修改")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> guard.requireWritable("LEGACY", "修改")).isInstanceOf(ServiceException.class);
    }
}
