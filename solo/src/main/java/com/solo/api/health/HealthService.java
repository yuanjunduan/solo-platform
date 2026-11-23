package com.solo.api.health;

import com.solo.api.health.dto.HealthStatus;
import com.solo.api.health.dto.ReadinessStatus;
import com.solo.media.entity.MediaServer;
import com.solo.media.service.MediaServerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 健康探针（M7）。
 *
 * <p><b>为什么不用 Actuator</b>：本项目只做两个端点，自建可以保证响应字段、判定口径
 * （媒体节点默认不参与就绪）和鉴权放行范围都是可控的，也少一条依赖。</p>
 *
 * <p><b>就绪缓存</b>：k8s / nginx 的探针频率通常是秒级，每次都开连接会把连接池
 * 探成瓶颈。结果默认缓存 5s，{@code health.ready-cache-ms=0} 可关闭缓存。</p>
 *
 * <p><b>不主动探活 ZLM</b>：媒体节点状态取库里的巡检结果。探针应该是"便宜且稳定"的，
 * 让它去等一个可能超时的 HTTP 请求，只会把整个实例拖成不健康。</p>
 */
@Slf4j
@Service
public class HealthService {

    private static final String UP = "UP";
    private static final String DOWN = "DOWN";
    /** 只读探测键：不写任何数据，能返回就说明连接可用 */
    private static final String REDIS_PROBE_KEY = "health:probe";

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final MediaServerService mediaServerService;
    private final String serviceName;
    private final int readyCacheMs;
    private final boolean readyRequireMedia;
    private final int dbTimeoutSeconds;
    private final String version;
    private final LocalDateTime startedAt;
    private final long startMillis;

    private volatile Cached cached;

    public HealthService(DataSource dataSource,
                         StringRedisTemplate redisTemplate,
                         MediaServerService mediaServerService,
                         @Value("${spring.application.name:solo}") String serviceName,
                         @Value("${health.ready-cache-ms:5000}") int readyCacheMs,
                         @Value("${health.ready-require-media:false}") boolean readyRequireMedia,
                         @Value("${health.db-timeout-seconds:2}") int dbTimeoutSeconds) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.mediaServerService = mediaServerService;
        this.serviceName = serviceName;
        this.readyCacheMs = Math.max(0, readyCacheMs);
        this.readyRequireMedia = readyRequireMedia;
        this.dbTimeoutSeconds = Math.max(1, dbTimeoutSeconds);
        this.version = resolveVersion();
        this.startMillis = ManagementFactory.getRuntimeMXBean().getStartTime();
        this.startedAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(startMillis), ZoneId.systemDefault());
    }

    /** 存活：不碰 DB / Redis / ZLM，进程能响应就是 UP */
    public HealthStatus liveness() {
        HealthStatus status = new HealthStatus();
        status.setStatus(UP);
        status.setService(serviceName);
        status.setVersion(version);
        status.setStartedAt(startedAt);
        status.setUptimeSeconds(Math.max(0, (System.currentTimeMillis() - startMillis) / 1000));
        return status;
    }

    /** 就绪：DB + Redis 必须可用；媒体节点按配置决定是否参与判定 */
    public ReadinessStatus readiness() {
        Cached hit = cached;
        long now = System.currentTimeMillis();
        if (hit != null && readyCacheMs > 0 && now - hit.atMillis() < readyCacheMs) {
            ReadinessStatus status = hit.status();
            status.setCachedForMs(now - hit.atMillis());
            return status;
        }
        ReadinessStatus fresh = probe();
        cached = new Cached(fresh, now);
        return fresh;
    }

    private ReadinessStatus probe() {
        ReadinessStatus status = new ReadinessStatus();
        status.setCheckedAt(LocalDateTime.now());
        status.setCachedForMs(0);

        ReadinessStatus.Dependency db = checkDb();
        ReadinessStatus.Dependency redis = checkRedis();
        ReadinessStatus.Dependency media = checkMedia();
        status.getDependencies().add(db);
        status.getDependencies().add(redis);
        status.getDependencies().add(media);

        boolean ready = UP.equals(db.getStatus())
                && UP.equals(redis.getStatus())
                && (!readyRequireMedia || UP.equals(media.getStatus()));
        status.setReady(ready);
        if (!ready) {
            log.warn("[健康检查] 未就绪：db={} redis={} media={}(requireMedia={})",
                    db.getStatus(), redis.getStatus(), media.getStatus(), readyRequireMedia);
        }
        return status;
    }

    private ReadinessStatus.Dependency checkDb() {
        long t0 = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            boolean ok = connection != null && connection.isValid(dbTimeoutSeconds);
            return dep("db", ok, ok ? null : "isValid=false", t0);
        } catch (Exception e) {
            // 响应里不带异常原文：SQLException 常把连接串、库名带出来
            log.warn("[健康检查] db 探测失败: {}", e.toString());
            return dep("db", false, e.getClass().getSimpleName() + "（详情见服务端日志）", t0);
        }
    }

    private ReadinessStatus.Dependency checkRedis() {
        long t0 = System.nanoTime();
        try {
            // 返回值 true/false/null 都说明命令往返成功，只有抛异常才是不可用
            redisTemplate.hasKey(REDIS_PROBE_KEY);
            return dep("redis", true, null, t0);
        } catch (Exception e) {
            log.warn("[健康检查] redis 探测失败: {}", e.toString());
            return dep("redis", false, e.getClass().getSimpleName() + "（详情见服务端日志）", t0);
        }
    }

    private ReadinessStatus.Dependency checkMedia() {
        long t0 = System.nanoTime();
        try {
            List<MediaServer> servers = mediaServerService.list();
            int total = servers == null ? 0 : servers.size();
            long online = servers == null ? 0 : servers.stream()
                    .filter(s -> s.getStatus() != null && s.getStatus() == 1)
                    .count();
            return dep("media", online > 0,
                    "库内在线节点 " + online + "/" + total + "（不主动探活 ZLM）", t0);
        } catch (Exception e) {
            log.warn("[健康检查] media 节点状态读取失败: {}", e.toString());
            return dep("media", false, e.getClass().getSimpleName() + "（详情见服务端日志）", t0);
        }
    }

    private static ReadinessStatus.Dependency dep(String name, boolean up, String detail, long startNanos) {
        ReadinessStatus.Dependency d = new ReadinessStatus.Dependency();
        d.setName(name);
        d.setStatus(up ? UP : DOWN);
        d.setDetail(detail);
        d.setCostMs((System.nanoTime() - startNanos) / 1_000_000);
        return d;
    }

    private static String resolveVersion() {
        String v = HealthService.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v;
    }

    /** 缓存条目：记录探测结果与探测时刻，避免每次探针都打库 */
    private record Cached(ReadinessStatus status, long atMillis) {
    }
}
