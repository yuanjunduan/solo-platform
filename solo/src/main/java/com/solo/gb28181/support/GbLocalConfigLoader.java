package com.solo.gb28181.support;

import com.solo.gb28181.service.GbLocalConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时把 gb_local_config 覆盖到 {@link com.solo.gb28181.config.Gb28181Properties}。
 *
 * <p>@Order(1) 早于 {@code SipLayer}（CommandLineRunner @Order(10)）：SIP 协议栈创建
 * 监听点时读到的必须是库里配置，否则 id/ip/port 改了要等下一次重启才生效。</p>
 *
 * <p>加载失败（表不存在、库不可用等）只告警不阻断启动，沿用 application yaml——
 * 历史部署环境下能正常拉起比强依赖新表更重要。</p>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class GbLocalConfigLoader implements ApplicationRunner {

    private final GbLocalConfigService gbLocalConfigService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            gbLocalConfigService.applyOnStartup();
        } catch (Exception e) {
            log.warn("[本平台配置] 从数据库加载失败，沿用 application yaml 配置：{}", e.getMessage(), e);
        }
    }
}
