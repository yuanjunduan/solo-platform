package com.solo.infra.mapper.projection;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个"有设备待重新归属"的平台及其最早登记时间（3.8.2 延迟重试的扫描结果）。
 *
 * <p>用 {@code MIN(create_time)} 而不是 {@code NOW() - N}：防抖是从"登记那一刻"算的，
 * 不是从"扫描这一刻"算的。</p>
 */
@Data
public class PendingPlatform {

    private Long platformId;

    private LocalDateTime oldest;
}
