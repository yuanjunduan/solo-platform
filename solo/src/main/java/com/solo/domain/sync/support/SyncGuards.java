package com.solo.domain.sync.support;

import java.math.BigDecimal;

/**
 * 两道删除闸门（C11）的裁决逻辑。
 *
 * <p>抽成纯函数是为了能单测：闸门的边界条件（阈值 0 / 小平台 / 恰好等于上限）
 * 是三行 if 写错就会删库的地方，不值得靠联调去验证。</p>
 */
public final class SyncGuards {

    private SyncGuards() {
    }

    /**
     * G1 空批保护：暂存 0 行，本地却有数据 → 本轮必须整轮失败。
     *
     * <p>空批的典型成因是对端发不出目录（未注册、超时、被防火墙吞了），
     * 如果按"暂存是空集"去算消失，会把本地整棵树标记成 STALE。
     * 反过来"本地本来就空"不算异常，直接成功即可。</p>
     */
    public static boolean emptyStagingIsFatal(long staged, long localRows) {
        return staged == 0 && localRows > 0;
    }

    /**
     * G2 批量删除保护：预计消失的行是否允许真的标记。
     *
     * @param wouldStale   与"消失标记"完全同一谓词算出来的预计行数
     * @param localRows    本地该类型的行数（分母）
     * @param ratio        平台阈值快照；{@code null} 或 {@code <= 0} 视为关闭（C25 默认 0）
     * @param minLocalRows 规模下限：不超过它就不做比例保护
     * @return true = 允许标记；false = 只跳过删除，新增/更新照常
     */
    public static boolean allowStale(long wouldStale, long localRows, BigDecimal ratio, long minLocalRows) {
        if (wouldStale <= 0) {
            return true;
        }
        if (ratio == null || ratio.signum() <= 0) {
            return true;
        }
        if (localRows <= minLocalRows) {
            return true;
        }
        BigDecimal limit = ratio.multiply(BigDecimal.valueOf(localRows));
        return BigDecimal.valueOf(wouldStale).compareTo(limit) <= 0;
    }
}
