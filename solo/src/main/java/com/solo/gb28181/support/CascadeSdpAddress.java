package com.solo.gb28181.support;

/**
 * 回给上级 SDP 的媒体地址解析（架构约束 C21a）。
 *
 * <p>本平台作为下级被上级点播时，回复的 200 OK 里 {@code c=} / {@code o=} 必须是
 * <b>本平台对外可达</b>的媒体地址。取值优先级：</p>
 * <ol>
 *   <li>{@code gb28181.cascade.sdp-public-ip}（显式配置，NAT / 多网卡场景的唯一可靠来源）</li>
 *   <li>{@code MediaServer.streamIp}（节点上标注的"对外播放 IP"）</li>
 *   <li>{@code MediaServer.hostIp}（节点 IP，最后的兜底）</li>
 * </ol>
 *
 * <p>三个来源都要过一道可用性检查：空值、{@code 0.0.0.0}、回环地址一律不可用。
 * 把这种地址回给上级不会报错，只会让上级黑屏——所以宁可拒绝本次点播，也不回一个坏地址。</p>
 */
public final class CascadeSdpAddress {

    private CascadeSdpAddress() {
    }

    /**
     * 解析回给上级的媒体地址。
     *
     * @param publicIpOverride {@code gb28181.cascade.sdp-public-ip}，可空
     * @param streamIp         {@code MediaServer.streamIp}，可空
     * @param hostIp           {@code MediaServer.hostIp}，可空
     * @return 可用地址；全部不可用（或显式配置了非法值）时返回 {@code null}，调用方应拒绝点播
     */
    public static String resolve(String publicIpOverride, String streamIp, String hostIp) {
        // 显式配置了就只认它：配错必须失败，不能静默回退掩盖问题
        if (publicIpOverride != null && !publicIpOverride.isBlank()) {
            return isUsable(publicIpOverride) ? publicIpOverride.trim() : null;
        }
        if (isUsable(streamIp)) {
            return streamIp.trim();
        }
        if (isUsable(hostIp)) {
            return hostIp.trim();
        }
        return null;
    }

    /** 能否作为媒体地址回给对端：非空、非 {@code 0.0.0.0}、非回环。 */
    public static boolean isUsable(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String v = ip.trim();
        return !"0.0.0.0".equals(v)
                && !"127.0.0.1".equals(v)
                && !"::1".equals(v)
                && !"localhost".equalsIgnoreCase(v);
    }

    /**
     * 是否 RFC1918 私网地址。
     *
     * <p>用于回 200 OK 前打一条 WARN：私网地址跨网段必然不可达，通常意味着节点没配
     * {@code streamIp}，需要补 {@code cascade.sdp-public-ip}。这里不做"上级是否同网段"的比对——
     * 那需要上级源 IP 且判断规则易错，一条可读的 WARN 已经足够定位。</p>
     */
    public static boolean isPrivate(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String v = ip.trim();
        if (v.startsWith("10.") || v.startsWith("192.168.")) {
            return true;
        }
        if (v.startsWith("172.")) {
            String[] parts = v.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignore) {
                    return false;
                }
            }
        }
        return false;
    }
}
