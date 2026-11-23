package com.solo.domain.sync.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两道闸门的边界用例（C11）。
 *
 * <p>这两个判断错一次的后果是"把本地整棵树标成消失"，所以边界值要逐个钉死，
 * 而不是只测一条 happy path。</p>
 */
class SyncGuardsTest {

    @Nested
    @DisplayName("G1 空批保护")
    class EmptyStaging {

        @Test
        @DisplayName("暂存 0 行且本地有数据 → 整轮失败")
        void fatalWhenStagedEmptyButLocalHasRows() {
            assertTrue(SyncGuards.emptyStagingIsFatal(0, 1));
            assertTrue(SyncGuards.emptyStagingIsFatal(0, 100_000));
        }

        @Test
        @DisplayName("暂存 0 行但本地也空 → 放行（首次接入的正常情况）")
        void passWhenNothingOnEitherSide() {
            assertFalse(SyncGuards.emptyStagingIsFatal(0, 0));
        }

        @Test
        @DisplayName("暂存非空 → 一律不算空批")
        void passWhenStagedHasRows() {
            assertFalse(SyncGuards.emptyStagingIsFatal(1, 100_000));
        }
    }

    @Nested
    @DisplayName("G2 批量删除保护")
    class AllowStale {

        @Test
        @DisplayName("没有要消失的行 → 放行（不看阈值）")
        void passWhenNothingToStale() {
            assertTrue(SyncGuards.allowStale(0, 100_000, new BigDecimal("0.2"), 1000));
        }

        @Test
        @DisplayName("阈值为 null 或 0 → 关闭保护（C25 默认 0）")
        void disabledWhenRatioIsNullOrZero() {
            assertTrue(SyncGuards.allowStale(50_000, 100_000, null, 1000));
            assertTrue(SyncGuards.allowStale(50_000, 100_000, BigDecimal.ZERO, 1000));
            assertTrue(SyncGuards.allowStale(50_000, 100_000, new BigDecimal("-0.1"), 1000));
        }

        @Test
        @DisplayName("本地行数不超过下限 → 不做比例保护（小平台一次全删是合理的）")
        void passWhenLocalRowsWithinMin() {
            assertTrue(SyncGuards.allowStale(1000, 1000, new BigDecimal("0.2"), 1000));
        }

        @Test
        @DisplayName("超过阈值 → 跳过删除")
        void skipWhenOverRatio() {
            // 100000 × 0.2 = 20000，预计消失 20001 已越线
            assertFalse(SyncGuards.allowStale(20_001, 100_000, new BigDecimal("0.2"), 1000));
        }

        @Test
        @DisplayName("恰好等于上限 → 放行（用 ≤ 而不是 <）")
        void passWhenExactlyAtLimit() {
            assertTrue(SyncGuards.allowStale(20_000, 100_000, new BigDecimal("0.2"), 1000));
        }

        @Test
        @DisplayName("本地 1001 行已越过下限，只消失 1 行 → 放行")
        void passWhenJustAboveMinWithFewStale() {
            assertTrue(SyncGuards.allowStale(1, 1001, new BigDecimal("0.2"), 1000));
        }
    }
}
