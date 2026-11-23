package com.solo.domain.resource.model;

/**
 * 资源状态四态（架构文档 3.8.4：删除 / 下线 / 失效必须分开）。
 *
 * <ul>
 *   <li>{@link #ACTIVE} 正常可见可播</li>
 *   <li>{@link #STALE} 本轮目录里没出现，但还没过宽限期（上游抖动时先不删）</li>
 *   <li>{@link #OFFLINE} 资源还在、当前不可用（通道离线等），不是删除</li>
 *   <li>{@link #REMOVED} 已确认消失（软删，{@code removed_at} 记时间；行不删，等墓地回收）</li>
 * </ul>
 */
public final class ResourceStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String STALE = "STALE";
    public static final String OFFLINE = "OFFLINE";
    public static final String REMOVED = "REMOVED";

    private ResourceStatus() {
    }

    /** 可见（列表/树默认过滤条件）：被删与待删都不展示 */
    public static boolean visible(String status) {
        return ACTIVE.equals(status) || OFFLINE.equals(status);
    }
}
