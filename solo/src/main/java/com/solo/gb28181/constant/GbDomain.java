package com.solo.gb28181.constant;

/**
 * 资源域：一条设备/通道"从哪来"。
 *
 * <p>域只落在 {@code gb_device}（通道通过 device_id 继承），因为设备是端点：
 * 一台下级平台或本地设备必然整台属于同一个域，不存在"半台设备的通道来自别处"。</p>
 */
public final class GbDomain {

    private GbDomain() {
    }

    /** 本地：本地组织下维护的设备/通道，带 {@code org_id}，可被推送到上级 */
    public static final String LOCAL = "LOCAL";
    /** 下级：通过 SIP 级联从下级平台目录同步来的镜像，不带 {@code org_id}，只读 */
    public static final String SUBORDINATE = "SUBORDINATE";
    /** 第三方资源平台：通过 HTTP 拉取的共享资源（第 3 轮实装） */
    public static final String REMOTE = "REMOTE";

    /** 过滤参数是否为合法域值（空/未知一律视为"不过滤"由调用方决定） */
    public static boolean isValid(String domain) {
        return LOCAL.equals(domain) || SUBORDINATE.equals(domain) || REMOTE.equals(domain);
    }
}
