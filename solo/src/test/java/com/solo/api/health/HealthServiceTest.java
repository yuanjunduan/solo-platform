package com.solo.api.health;

import com.solo.api.health.dto.HealthStatus;
import com.solo.api.health.dto.ReadinessStatus;
import com.solo.media.entity.MediaServer;
import com.solo.media.service.MediaServerService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 健康探针单测（M7）：只验证判定口径与缓存，不启动 Spring 上下文。
 */
class HealthServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final MediaServerService mediaService = mock(MediaServerService.class);

    @Test
    void readiness_shouldBeReady_whenDbAndRedisUp() throws Exception {
        DataSource ds = dataSource(mockConnection(true));
        when(redis.hasKey(anyString())).thenReturn(Boolean.FALSE);
        when(mediaService.list()).thenReturn(List.of(mediaServer(1)));

        ReadinessStatus status = service(ds, 5_000, false).readiness();

        assertThat(status.isReady()).isTrue();
        assertThat(status.getDependencies()).hasSize(3);
        assertThat(dep(status, "db").getStatus()).isEqualTo("UP");
        assertThat(dep(status, "redis").getStatus()).isEqualTo("UP");
        assertThat(dep(status, "media").getDetail()).contains("1/1");
    }

    @Test
    void readiness_shouldNotReady_whenDbFails_andNotLeakConnectionUrl() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("Communications link failure jdbc:mysql://db:3306/solo_db"));
        when(redis.hasKey(anyString())).thenReturn(Boolean.TRUE);
        when(mediaService.list()).thenReturn(List.of(mediaServer(1)));

        ReadinessStatus status = service(ds, 0, false).readiness();

        assertThat(status.isReady()).isFalse();
        assertThat(dep(status, "db").getStatus()).isEqualTo("DOWN");
        // 响应里不能出现连接串，排障看服务端日志
        assertThat(dep(status, "db").getDetail()).doesNotContain("jdbc");
        assertThat(dep(status, "db").getDetail()).contains("SQLException");
    }

    @Test
    void readiness_shouldNotReady_whenRedisFails() throws Exception {
        DataSource ds = dataSource(mockConnection(true));
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(mediaService.list()).thenReturn(List.of(mediaServer(1)));

        ReadinessStatus status = service(ds, 0, false).readiness();

        assertThat(status.isReady()).isFalse();
        assertThat(dep(status, "redis").getStatus()).isEqualTo("DOWN");
    }

    @Test
    void readiness_shouldFollowRequireMediaFlag() throws Exception {
        DataSource ds = dataSource(mockConnection(true));
        when(redis.hasKey(anyString())).thenReturn(Boolean.FALSE);
        when(mediaService.list()).thenReturn(List.of(mediaServer(0)));

        // 默认口径：媒体节点只报告，不影响接活
        assertThat(service(ds, 0, false).readiness().isReady()).isTrue();
        // 显式要求时：没有在线节点就 503
        ReadinessStatus strict = service(ds, 0, true).readiness();
        assertThat(strict.isReady()).isFalse();
        assertThat(dep(strict, "media").getStatus()).isEqualTo("DOWN");
    }

    @Test
    void readiness_shouldCacheWithinWindow() throws Exception {
        Connection connection = mockConnection(true);
        DataSource ds = dataSource(connection);
        when(redis.hasKey(anyString())).thenReturn(Boolean.FALSE);
        when(mediaService.list()).thenReturn(List.of(mediaServer(1)));
        HealthService service = service(ds, 60_000, false);

        ReadinessStatus first = service.readiness();
        ReadinessStatus second = service.readiness();

        assertThat(first).isSameAs(second);
        verify(connection, times(1)).isValid(anyInt());
    }

    @Test
    void readiness_shouldProbeEveryTime_whenCacheDisabled() throws Exception {
        Connection connection = mockConnection(true);
        DataSource ds = dataSource(connection);
        when(redis.hasKey(anyString())).thenReturn(Boolean.FALSE);
        when(mediaService.list()).thenReturn(List.of(mediaServer(1)));
        HealthService service = service(ds, 0, false);

        service.readiness();
        service.readiness();

        verify(connection, times(2)).isValid(anyInt());
    }

    @Test
    void liveness_shouldReportProcessInfo() {
        HealthStatus status = service(mock(DataSource.class), 0, false).liveness();

        assertThat(status.getStatus()).isEqualTo("UP");
        assertThat(status.getService()).isEqualTo("solo");
        assertThat(status.getVersion()).isNotBlank();
        assertThat(status.getUptimeSeconds()).isGreaterThanOrEqualTo(0);
        assertThat(status.getStartedAt()).isNotNull();
    }

    private HealthService service(DataSource ds, int cacheMs, boolean requireMedia) {
        return new HealthService(ds, redis, mediaService, "solo", cacheMs, requireMedia, 1);
    }

    private static DataSource dataSource(Connection connection) throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection);
        return ds;
    }

    private static Connection mockConnection(boolean valid) throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.isValid(anyInt())).thenReturn(valid);
        return connection;
    }

    private static MediaServer mediaServer(int status) {
        MediaServer server = new MediaServer();
        server.setStatus(status);
        return server;
    }

    private static ReadinessStatus.Dependency dep(ReadinessStatus status, String name) {
        return status.getDependencies().stream()
                .filter(d -> name.equals(d.getName()))
                .findFirst()
                .orElseThrow();
    }
}
