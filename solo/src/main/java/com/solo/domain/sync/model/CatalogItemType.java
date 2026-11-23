package com.solo.domain.sync.model;

/**
 * 目录项类型（3.7.2）：与协议无关的五分类。
 *
 * <p>为什么不直接用 {@link SyncItemType} 的四分类：协议侧要区分 215 业务分组与 216 虚拟组织，
 * 落到库里都是 {@code res_group}（{@code group_type} 区分），所以这里多一层，
 * 由 {@link #itemType()} 与 {@link #groupType()} 各自映射。</p>
 */
public enum CatalogItemType {

    /** 组织（含≤8 位行政码目录、以及无法判定具体类型的目录节点） */
    ORG,
    /** 215 业务分组 */
    GROUP_BUSINESS,
    /** 216 虚拟组织 */
    GROUP_VIRTUAL,
    /** 设备/平台（类型段 200） */
    DEVICE,
    /** 通道（131 等摄像机类型） */
    CHANNEL;

    /** 落到暂存表 {@code sync_staging.item_type} 的取值 */
    public String itemType() {
        return switch (this) {
            case ORG -> SyncItemType.ORG;
            case GROUP_BUSINESS, GROUP_VIRTUAL -> SyncItemType.GROUP;
            case DEVICE -> SyncItemType.DEVICE;
            case CHANNEL -> SyncItemType.CHANNEL;
        };
    }

    /** {@code res_group.group_type} 取值；非分组返回 null */
    public String groupType() {
        return switch (this) {
            case GROUP_BUSINESS -> "215";
            case GROUP_VIRTUAL -> "216";
            default -> null;
        };
    }

    public boolean isGroup() {
        return this == GROUP_BUSINESS || this == GROUP_VIRTUAL;
    }
}
