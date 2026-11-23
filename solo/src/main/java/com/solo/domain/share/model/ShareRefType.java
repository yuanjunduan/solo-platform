package com.solo.domain.share.model;

/**
 * 共享项引用的资源类型。
 *
 * <p>{@code ORG} 只出现在规则行（SUBTREE）；{@code CHANNEL}/{@code DEVICE} 是具体资源。
 * 首版对外暴露通道与分组，设备节点按需由通道的载体推导，不单独共享（3.9.1 的第 ③ 段只到通道）。</p>
 */
public final class ShareRefType {

    public static final String ORG = "ORG";
    public static final String DEVICE = "DEVICE";
    public static final String CHANNEL = "CHANNEL";

    /** 虚拟组织节点在 out_code_map / outbox 里的类型名 */
    public static final String GROUP = "GROUP";

    private ShareRefType() {
    }
}
