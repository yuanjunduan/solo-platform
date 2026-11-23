package com.solo.gb28181.event.catalog;

import com.solo.domain.share.service.ShareCatalogService;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.entity.GbParentPlatform;
import com.solo.gb28181.processor.request.SubscribeHolder;
import com.solo.gb28181.processor.request.SubscribeInfo;
import com.solo.gb28181.service.GbParentPlatformService;
import com.solo.gb28181.transmit.cmd.PlatformCommander;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <p>share 增删后，对活跃的级联订阅（deviceId 空 = 订阅整个平台目录）按最新共享通道推送。</p>
 *
 * <p>数据源与 {@code Query/Catalog} 应答、NOTIFY 投递共用 M4 的出站三表
 * （{@link ShareCatalogService#loadCatalogItems}）；读遗留 {@code gb_platform_share} 会让
 * "应答目录里有、推送里没有"的节点永远对不齐。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShareChangedEventListener {

    private final SubscribeHolder subscribeHolder;
    private final GbParentPlatformService gbParentPlatformService;
    private final ShareCatalogService shareCatalogService;
    private final PlatformCommander platformCommander;
    private final Gb28181Properties properties;

    @Async("gb28181BizExecutor")
    @EventListener
    public void onShareChanged(ShareChangedEvent event) {
        Long parentPlatformId = event.getParentPlatformId();
        if (parentPlatformId == null) {
            return;
        }
        GbParentPlatform platform = gbParentPlatformService.getById(parentPlatformId);
        if (platform == null || !Integer.valueOf(1).equals(platform.getEnabled())) {
            return;
        }
        SubscribeInfo sub = subscribeHolder.getActive(platform.getServerGbId(), "Catalog").orElse(null);
        if (sub == null) {
            log.warn("[级联订阅] share 变化但无活跃 Catalog 订阅，跳过推送 上级平台:{}", platform.getServerGbId());
            return;
        }
        if (sub.getDeviceId() != null && !sub.getDeviceId().isEmpty()) {
            return;
        }
        List<GbDeviceChannel> items = shareCatalogService.loadCatalogItems(parentPlatformId);
        if (items == null) {
            items = List.of();
        }
        platformCommander.sendNotifyCatalogList(platform, properties.getId(), items,
                String.valueOf(PlatformCommander.nextSn()));
        log.info("[级联订阅] share 变化推送 上级平台:{} 目录条目:{}",
                platform.getServerGbId(), items.size());
    }
}
