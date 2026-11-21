package com.solo.media;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solo.media.entity.MediaServer;
import com.solo.media.mapper.GbMediaServerMapper;
import com.solo.media.properties.MediaProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MediaServerBootstrap {

    private final MediaProperties mediaProperties;
    private final GbMediaServerMapper mediaServerMapper;

    @PostConstruct
    void init() {
        // 启动期显式检查 hook secret 配置
        String hookSecret = mediaProperties.getHookSecret();
        if (hookSecret == null || hookSecret.isBlank()) {
            log.error("[流媒体] media.hook-secret 未配置，ZLM 所有 hook 回调都会被拒。请在 application.yaml 补齐。");
        } else {
            log.info("[流媒体] hook secret 已启用，长度={}", hookSecret.length());
        }

        MediaProperties.DefaultServer cfg = mediaProperties.getDefaultServer();
        if (cfg == null || !cfg.isEnabled()) {
            return;
        }
        if (cfg.getServerId() == null || cfg.getServerId().isBlank()) {
            log.warn("[流媒体] default-server.server-id 为空，跳过默认节点自举");
            return;
        }
        MediaServer exist = mediaServerMapper.selectOne(
                new LambdaQueryWrapper<MediaServer>().eq(MediaServer::getServerId, cfg.getServerId()));
        if (exist != null) {
            // 运行期唯一真实来源是 media_server 表（页面「媒体服务器」维护）。
            // yaml 只负责首次播种：已存在的节点必须跳过，否则每次重启都会把页面上
            // 改过的 secret / 名称 / 对外 IP 冲回配置值。
            log.info("[流媒体] 默认 ZLM 节点已存在，跳过播种 serverId={}（以管理面配置为准）",
                    cfg.getServerId());
            return;
        }
        MediaServer server = new MediaServer();
        applyConfig(server, cfg);
        server.setStatus(0);
        mediaServerMapper.insert(server);
        log.info("[流媒体] 默认 ZLM 节点已写入 serverId={} {}:{}",
                cfg.getServerId(), cfg.getHostIp(), cfg.getHttpPort());
    }

    /**
     * 将配置中的默认节点字段应用到实体上（不含 id / status / hookAliveTime 等运行期状态）。
     */
    private void applyConfig(MediaServer server, MediaProperties.DefaultServer cfg) {
        server.setServerId(cfg.getServerId());
        server.setName(cfg.getName());
        server.setHostIp(cfg.getHostIp());
        server.setHttpPort(cfg.getHttpPort());
        server.setSecret(cfg.getSecret());
        server.setStreamIp(cfg.getStreamIp() == null || cfg.getStreamIp().isBlank()
                ? cfg.getHostIp() : cfg.getStreamIp());
        server.setStreamHttpPort(cfg.getStreamHttpPort());
        server.setRtcPort(cfg.getRtcPort());
    }
}
