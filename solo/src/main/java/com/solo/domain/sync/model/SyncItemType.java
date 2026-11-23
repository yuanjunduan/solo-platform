package com.solo.domain.sync.model;

/**
 * 目录项类型：一轮全量里 staging 的 {@code item_type} 取值。
 *
 * <p>与 {@link com.solo.domain.resource.model.SourceType} 不是一回事：那个是"这条资源哪来的"，
 * 这个是"目录里的哪一类节点"。分组（215/216）单独一类，因为它落 {@code res_group} 而不是 {@code res_org}。</p>
 */
public final class SyncItemType {

    public static final String ORG = "ORG";
    public static final String GROUP = "GROUP";
    public static final String DEVICE = "DEVICE";
    public static final String CHANNEL = "CHANNEL";

    private SyncItemType() {
    }
}
