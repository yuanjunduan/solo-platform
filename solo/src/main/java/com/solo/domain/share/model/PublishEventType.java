package com.solo.domain.share.model;

/**
 * 出站事件类型（3.9.5 / C18）。
 *
 * <p>{@code REFRESH} 是<b>超量降级</b>的产物：单轮待推条数超过 {@code publish.detail-max}
 * 时不再逐条循环，而是发一条"去拉全量"，与入站事件流的 {@code REFRESH_ALL} 同义（C18）。
 * 降级时也必须占住自己的 {@code seq_no}，否则上级侧的重连/补推会永久落后（C19 的同一条纪律）。</p>
 */
public final class PublishEventType {

    public static final String ADD = "ADD";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";
    public static final String REFRESH = "REFRESH";

    /**
     * 状态跟随（6.6）：上游通道从离线恢复 → 上级收 ON；从在线掉线 → 上级收 OFF。
     * <p>不复用 {@code UPDATE}：Catalog 的 {@code <Event>} 是上级判断"该不该改状态"的依据，
     * 带 {@code UPDATE} 的离线事件在部分平台上会被当成普通字段更新而忽略 {@code <Status>OFF</Status>}。</p>
     */
    public static final String ON = "ON";
    public static final String OFF = "OFF";

    private PublishEventType() {
    }
}
