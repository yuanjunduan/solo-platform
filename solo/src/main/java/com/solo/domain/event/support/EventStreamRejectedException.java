package com.solo.domain.event.support;

/**
 * 订阅连接被拒（不是业务异常，是"这条连接开不了"）。
 *
 * <p>两种拒绝含义完全不同，所以带 {@link Reason} 一起抛：</p>
 * <ul>
 *   <li>{@code CURSOR_EXPIRED}：客户端游标早于保留窗口，中间的事件已被回收，
 *       <b>补不上</b>——只能返回 409 + 快照地址让它重拉（6.4 验收标准 ④）；</li>
 *   <li>{@code SATURATED}：本实例并发连接数已满，属于临时状态，应返回 429。</li>
 * </ul>
 *
 * <p>为什么不直接抛开放 API 的异常：domain 层不该依赖 openapi 层，
 * 由 Controller 翻译成 409 / 429 与对应响应体（含 snapshotUrl）。</p>
 */
public class EventStreamRejectedException extends RuntimeException {

    public enum Reason {
        CURSOR_EXPIRED,
        SATURATED
    }

    private final Reason reason;
    private final long minSeq;
    private final long maxSeq;
    private final String snapshotUrl;

    private EventStreamRejectedException(Reason reason, String message, long minSeq, long maxSeq,
                                         String snapshotUrl) {
        super(message);
        this.reason = reason;
        this.minSeq = minSeq;
        this.maxSeq = maxSeq;
        this.snapshotUrl = snapshotUrl;
    }

    public static EventStreamRejectedException cursorExpired(long requested, long minSeq, long maxSeq,
                                                             String snapshotUrl) {
        return new EventStreamRejectedException(Reason.CURSOR_EXPIRED,
                "游标 " + requested + " 早于保留窗口 [" + minSeq + ", " + maxSeq + "]，请改拉全量快照",
                minSeq, maxSeq, snapshotUrl);
    }

    public static EventStreamRejectedException saturated(int limit) {
        return new EventStreamRejectedException(Reason.SATURATED,
                "并发订阅连接数已达上限 " + limit, 0, 0, null);
    }

    public Reason getReason() {
        return reason;
    }

    public long getMinSeq() {
        return minSeq;
    }

    public long getMaxSeq() {
        return maxSeq;
    }

    /** 409 需要把快照地址一起返回，否则对接方只能猜"从哪里重拉" */
    public String getSnapshotUrl() {
        return snapshotUrl;
    }
}
