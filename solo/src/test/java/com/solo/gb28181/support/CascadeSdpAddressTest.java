package com.solo.gb28181.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C21a 的地址解析契约：回给上级的地址优先取显式配置，其次节点对外 IP，最后节点 IP；
 * 任何一档拿到回环 / 0.0.0.0 都判定不可用，宁可拒绝点播也不回一个坏地址。
 */
class CascadeSdpAddressTest {

    @Test
    void explicitPublicIpWinsOverNodeAddresses() {
        assertThat(CascadeSdpAddress.resolve("203.0.113.7", "192.168.1.100", "10.0.0.5"))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void fallsBackToStreamIpThenHostIp() {
        assertThat(CascadeSdpAddress.resolve(null, "192.168.1.100", "10.0.0.5")).isEqualTo("192.168.1.100");
        assertThat(CascadeSdpAddress.resolve("  ", "  ", "10.0.0.5")).isEqualTo("10.0.0.5");
    }

    @Test
    void rejectsAnyAddressAndLoopback() {
        // streamIp / hostIp 都是无效地址 → 无可用地址，调用方必须回 500
        assertThat(CascadeSdpAddress.resolve(null, "0.0.0.0", "127.0.0.1")).isNull();
        assertThat(CascadeSdpAddress.resolve(null, null, null)).isNull();
        assertThat(CascadeSdpAddress.resolve(null, "::1", "localhost")).isNull();
    }

    @Test
    void invalidExplicitConfigFailsInsteadOfSilentlyFallingBack() {
        // 显式配错必须暴露：不能悄悄用节点 IP 顶上，否则永远发现不了配置错误
        assertThat(CascadeSdpAddress.resolve("127.0.0.1", "192.168.1.100", "10.0.0.5")).isNull();
        assertThat(CascadeSdpAddress.resolve("0.0.0.0", "192.168.1.100", "10.0.0.5")).isNull();
    }

    @Test
    void detectsRfc1918Ranges() {
        assertThat(CascadeSdpAddress.isPrivate("10.9.9.9")).isTrue();
        assertThat(CascadeSdpAddress.isPrivate("172.16.0.1")).isTrue();
        assertThat(CascadeSdpAddress.isPrivate("172.31.255.254")).isTrue();
        assertThat(CascadeSdpAddress.isPrivate("172.15.0.1")).isFalse();
        assertThat(CascadeSdpAddress.isPrivate("172.32.0.1")).isFalse();
        assertThat(CascadeSdpAddress.isPrivate("192.168.1.100")).isTrue();
        assertThat(CascadeSdpAddress.isPrivate("203.0.113.7")).isFalse();
        assertThat(CascadeSdpAddress.isPrivate(null)).isFalse();
    }
}
