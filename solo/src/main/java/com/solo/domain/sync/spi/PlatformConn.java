package com.solo.domain.sync.spi;

/**
 * 一条平台连接的协议侧画像。
 *
 * <p>刻意不带 {@code Platform} 实体本身：适配器只需要"往哪连、以谁的身份连"，
 * 拿到实体就会忍不住去读业务字段（架构原则 7：协议层不 JOIN 业务数据）。</p>
 *
 * @param tenantId     租户
 * @param platformId   platform 表主键
 * @param code         对端标识（GB28181 用国标编码，与对端注册时的 DeviceID 一致）
 * @param localDomain  本平台在该连接中使用的域
 * @param host         对端地址
 * @param port         对端端口
 * @param syncProtocol 目录同步协议标识
 */
public record PlatformConn(
        Long tenantId,
        Long platformId,
        String code,
        String localDomain,
        String host,
        Integer port,
        String syncProtocol
) {

    public String endpoint() {
        return host == null ? "" : host + (port == null ? "" : ":" + port);
    }
}
