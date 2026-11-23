-- ============================================================================
-- 2026-11-27  M6 视频业务完善（PostgreSQL）
--
-- 与 2026-11-27_m6_business_mysql.sql 等价：只差类型名与索引写法。
-- 幂等：CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS。
-- ============================================================================

-- 1) 播放 / 回放记录
CREATE TABLE IF NOT EXISTS res_play_record
(
    id                  BIGINT        NOT NULL,
    tenant_id           BIGINT        NOT NULL DEFAULT 1,
    channel_row_id      BIGINT        NOT NULL,
    channel_code        VARCHAR(64)   NOT NULL,
    channel_name        VARCHAR(128)  NULL,
    source_type         VARCHAR(16)   NOT NULL DEFAULT 'NATIVE',
    source_platform_id  BIGINT        NOT NULL DEFAULT 0,
    action              VARCHAR(16)   NOT NULL,
    stream_id           VARCHAR(160)  NULL,
    actor_id            VARCHAR(64)   NULL,
    actor_name          VARCHAR(64)   NULL,
    client_ip           VARCHAR(64)   NULL,
    result              VARCHAR(16)   NOT NULL,
    fail_reason         VARCHAR(256)  NULL,
    begin_time          TIMESTAMP     NOT NULL,
    end_time            TIMESTAMP     NULL,
    duration_sec        INT           NULL,
    create_time         TIMESTAMP     NOT NULL,
    update_time         TIMESTAMP     NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_play_record_channel ON res_play_record (channel_row_id, begin_time);
CREATE INDEX IF NOT EXISTS idx_play_record_actor ON res_play_record (actor_id, begin_time);
CREATE INDEX IF NOT EXISTS idx_play_record_time ON res_play_record (begin_time);
CREATE INDEX IF NOT EXISTS idx_play_record_stream ON res_play_record (stream_id);

COMMENT ON TABLE res_play_record IS '播放/回放请求记录：一次点播一行，失败也留痕';
COMMENT ON COLUMN res_play_record.action IS 'PLAY=实时 / PLAYBACK=回放';
COMMENT ON COLUMN res_play_record.result IS 'SUCCESS / FAILED';

-- 2) 通道预置位
CREATE TABLE IF NOT EXISTS res_channel_preset
(
    id               BIGINT       NOT NULL,
    tenant_id        BIGINT       NOT NULL DEFAULT 1,
    channel_row_id   BIGINT       NOT NULL,
    preset_index     INT          NOT NULL,
    name             VARCHAR(64)  NOT NULL,
    create_time      TIMESTAMP    NOT NULL,
    update_time      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_channel_preset ON res_channel_preset (channel_row_id, preset_index);
CREATE INDEX IF NOT EXISTS idx_channel_preset_channel ON res_channel_preset (channel_row_id);

COMMENT ON TABLE res_channel_preset IS '通道预置位名称：编号下发设备，名称仅本地保存';

-- 3) 校验
SELECT 'res_play_record' AS tbl, COUNT(*) AS cnt FROM res_play_record
UNION ALL SELECT 'res_channel_preset', COUNT(*) FROM res_channel_preset;
