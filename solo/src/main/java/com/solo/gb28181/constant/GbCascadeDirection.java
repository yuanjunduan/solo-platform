package com.solo.gb28181.constant;

/**
 * 级联方向与目录来源类型（{@code gb_platform_registry} 的 direction / kind 取值）。
 *
 * <p>方向描述"这条登记行相对本平台的关系"：</p>
 * <ul>
 *   <li>{@link #DOWNSTREAM}：下级平台或接入设备，向本平台注册并推送 Catalog，目录落在
 *       {@code gb_device_channel}；</li>
 *   <li>{@link #UPSTREAM}：本平台作为下级注册过去的上级平台，只作级联关系与展示，
 *       本平台不拉取其目录；</li>
 *   <li>{@link #REMOTE}：第三方资源平台（HTTP 拉取），当前只有域骨架。</li>
 * </ul>
 *
 * <p>国标 20 位编码第 11-13 位为类型段，{@code 200} 表示平台/业务服务器类端点，
 * 其余（如 131/132）是设备类。注册上来的端点据此判域：平台 → SUBORDINATE，设备 → LOCAL。</p>
 */
public final class GbCascadeDirection {

    private GbCascadeDirection() {
    }

    public static final String DOWNSTREAM = "DOWNSTREAM";
    public static final String UPSTREAM = "UPSTREAM";
    public static final String REMOTE = "REMOTE";

    public static final String KIND_PLATFORM = "PLATFORM";
    public static final String KIND_DEVICE = "DEVICE";

    /** 国标编码第 11-13 位是否为 200（平台类端点） */
    public static boolean isPlatformGbId(String gbId) {
        return gbId != null && gbId.length() >= 13 && "200".equals(gbId.substring(10, 13));
    }

    /** 端点类型：平台 / 接入设备 */
    public static String kindOf(String gbId) {
        return isPlatformGbId(gbId) ? KIND_PLATFORM : KIND_DEVICE;
    }

    public static boolean isValidDirection(String direction) {
        return DOWNSTREAM.equals(direction) || UPSTREAM.equals(direction) || REMOTE.equals(direction);
    }
}
