package com.solo.domain.sync.service.impl;

import com.solo.domain.sync.support.SyncSettings;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/**
 * 运行期自检：启动成功后把"真正生效的参数"打一次 INFO（3.13.6）。
 *
 * <p>级联同步最常见的故障是"卡住"而不是"报错"，而卡住时最先要排除的就是超时与连接池：
 * 客户端 {@code statement_timeout} 若短于服务端，会看到莫名其妙的断连；连接池若被
 * 一轮百万级合并占满，"同步中其他接口全挂"就会被误判成数据库故障。</p>
 *
 * <p><b>只在启动时打一次</b>：这些值都是启动期固定的，每次同步都刷只会把日志淹掉。
 * 全部探测各自 {@code try/catch}：自检本身绝不能让应用起不来。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncSelfCheckReporter {

    private final DataSource dataSource;
    private final SyncSettings settings;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Value("${audit.enabled:true}")
    private boolean auditEnabled;

    @Value("${resource.write-guard.mode:warn}")
    private String writeGuardMode;

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        log.info("[资源同步自检] ══════ 运行期参数（启动一次） ══════");
        reportPool();
        reportDbTimeouts();
        reportSyncParams();
        log.info("[资源同步自检] ══════════════════════════════════");
    }

    /** 连接池：Hikari 的 maxWait 叫 connectionTimeout，别把两者混为一谈 */
    private void reportPool() {
        try {
            if (dataSource instanceof HikariDataSource hikari) {
                log.info("[资源同步自检] 连接池 {}：maximumPoolSize={} minimumIdle={} "
                                + "connectionTimeout={}ms validationTimeout={}ms keepalive={}ms",
                        hikari.getPoolName(), hikari.getMaximumPoolSize(), hikari.getMinimumIdle(),
                        hikari.getConnectionTimeout(), hikari.getValidationTimeout(),
                        hikari.getKeepaliveTime());
            } else {
                log.info("[资源同步自检] 连接池类型 {}（非 Hikari，超时请看该实现的配置）",
                        dataSource.getClass().getSimpleName());
            }
        } catch (RuntimeException e) {
            log.warn("[资源同步自检] 连接池参数读取失败 err={}", e.getMessage());
        }

        // JDBC 侧超时只能从 url 里看：MySQL/PG 都是 connectTimeout + socketTimeout 两个参数
        String url = datasourceUrl == null ? "" : datasourceUrl;
        if (url.isBlank()) {
            log.info("[资源同步自检] JDBC url 未配置，跳过后端超时检查");
            return;
        }
        log.info("[资源同步自检] JDBC url：{}（connectTimeout/socketTimeout 见查询参数）", maskUrl(url));
    }

    /**
     * 数据库侧超时。
     *
     * <p>服务端超时若短于客户端，客户端会看到"连接被重置"而不是"语句超时"，
     * 排查方向会被完全带偏，所以这里一定要把实际值读出来。</p>
     */
    private void reportDbTimeouts() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String product = meta.getDatabaseProductName();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            if (product != null && product.toLowerCase().contains("postgresql")) {
                log.info("[资源同步自检] DB(PG) statement_timeout={} lock_timeout={} "
                                + "idle_in_transaction_session_timeout={}",
                        show(jdbc, "statement_timeout"), show(jdbc, "lock_timeout"),
                        show(jdbc, "idle_in_transaction_session_timeout"));
            } else {
                log.info("[资源同步自检] DB(MySQL) max_execution_time={} innodb_lock_wait_timeout={} "
                                + "wait_timeout={}",
                        variable(jdbc, "MAX_EXECUTION_TIME"), variable(jdbc, "innodb_lock_wait_timeout"),
                        variable(jdbc, "wait_timeout"));
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("[资源同步自检] DB 侧超时读取失败 err={}", e.getMessage());
        }
    }

    /** 同步链路的闸门 / 保留 / 开关：避免"以为开了其实没开" */
    private void reportSyncParams() {
        log.info("[资源同步自检] 同步参数：stallTimeout={}ms cooldown={}ms g2MinLocalRows={} "
                        + "stagingPageSize={} treeMaxDepth={}",
                settings.getStallTimeoutMs(), settings.cooldownMs(), settings.getG2MinLocalRows(),
                settings.getStagingPageSize(), settings.getTreeMaxDepth());
        log.info("[资源同步自检] 删除与保留：deleteMode={}（real={}） residueRetentionHours={} "
                        + "orgRetryDebounce={}s orgRetryMaxWait={}min",
                settings.getDeleteMode(), settings.realDelete(), settings.getResidueRetentionHours(),
                settings.getOrgRetryDebounceSeconds(), settings.getOrgRetryMaxWaitMinutes());
        log.info("[资源同步自检] 开关：snapshotEnabled={} snapshotKeepRounds={} snapshotQueueSize={} "
                        + "auditEnabled={} resourceWriteGuard={}",
                settings.isSnapshotEnabled(), settings.getSnapshotKeepRounds(), settings.getSnapshotQueueSize(),
                auditEnabled, writeGuardMode);
    }

    private static String show(JdbcTemplate jdbc, String parameter) {
        try {
            String value = jdbc.queryForObject("SHOW " + parameter, String.class);
            return value == null ? "?" : value;
        } catch (RuntimeException e) {
            return "?" + e.getClass().getSimpleName();
        }
    }

    private static String variable(JdbcTemplate jdbc, String name) {
        try {
            Object value = jdbc.queryForObject("SELECT @@" + name, Object.class);
            return String.valueOf(value);
        } catch (RuntimeException e) {
            return "?" + e.getClass().getSimpleName();
        }
    }

    /** 只去掉密码段：url 里的 host/db 和超时参数都是排查要看的 */
    private static String maskUrl(String url) {
        int at = url.indexOf('@');
        int scheme = url.indexOf("://");
        if (at > 0 && scheme > 0 && at > scheme) {
            return url.substring(0, scheme + 3) + "***" + url.substring(at);
        }
        return url;
    }
}
