-- ============================================================================
-- 2026-11-23  M3 资源变更事件流（PostgreSQL）
--
-- 与 2026-11-23_event_stream_mysql.sql 等价，仅方言差异：
--   AUTO_INCREMENT  -> BIGSERIAL
--   DATETIME        -> TIMESTAMP(0)（秒精度，与 sync_round 等表一致）
--   COMMENT 内联    -> COMMENT ON
--   TINYINT         -> SMALLINT
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行。
-- ============================================================================

CREATE TABLE IF NOT EXISTS change_event
(
    seq          BIGSERIAL    NOT NULL,
    tenant_id    BIGINT       NOT NULL DEFAULT 1,
    source_type  VARCHAR(16)  NOT NULL,
    platform_id  BIGINT       NULL,
    org_path     VARCHAR(512) NULL,
    item_type    VARCHAR(16)  NOT NULL,
    code         VARCHAR(64)  NULL,
    change_type  VARCHAR(16)  NOT NULL,
    shape        VARCHAR(16)  NOT NULL,
    payload      TEXT         NULL,
    task_id      BIGINT       NULL,
    create_time  TIMESTAMP(0) NOT NULL,
    PRIMARY KEY (seq)
);

CREATE INDEX IF NOT EXISTS idx_change_event_tenant_seq ON change_event (tenant_id, seq);
CREATE INDEX IF NOT EXISTS idx_change_event_platform_seq ON change_event (platform_id, seq);
CREATE INDEX IF NOT EXISTS idx_change_event_time ON change_event (create_time);

COMMENT ON TABLE change_event IS '资源变更事件流：REFRESH_ALL 也占一行，保证游标连续';
COMMENT ON COLUMN change_event.seq IS '全局游标：SSE 的 Last-Event-ID';
COMMENT ON COLUMN change_event.source_type IS 'NATIVE/INBOUND，与 res_* 的 source_type 同义';
COMMENT ON COLUMN change_event.org_path IS '写库后回读的组织路径：订阅 scope 二次过滤用';
COMMENT ON COLUMN change_event.change_type IS 'ADDED/UPDATED/REMOVED/REFRESH';
COMMENT ON COLUMN change_event.shape IS 'CHANGED_DETAIL/REFRESH_ALL（3.11.7 的两种形态）';
COMMENT ON COLUMN change_event.payload IS '关键字段 JSON：只带定位不带全量';
COMMENT ON COLUMN change_event.task_id IS '关联的同步轮次（sync_task.id）';

CREATE TABLE IF NOT EXISTS event_subscription
(
    id            BIGINT       NOT NULL,
    tenant_id     BIGINT       NOT NULL DEFAULT 1,
    app_id        BIGINT       NOT NULL,
    app_key       VARCHAR(64)  NOT NULL,
    topic         VARCHAR(32)  NOT NULL DEFAULT 'resource',
    platform_id   BIGINT       NULL,
    org_path      VARCHAR(512) NULL,
    last_event_id BIGINT       NOT NULL DEFAULT 0,
    status        SMALLINT     NOT NULL DEFAULT 1,
    remark        VARCHAR(255) NULL,
    create_time   TIMESTAMP(0) NOT NULL,
    update_time   TIMESTAMP(0) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_event_subscription_key UNIQUE (app_id, topic)
);

CREATE INDEX IF NOT EXISTS idx_event_subscription_topic ON event_subscription (topic);
CREATE INDEX IF NOT EXISTS idx_event_subscription_status ON event_subscription (status);

COMMENT ON TABLE event_subscription IS '对外订阅：scope 即授权，推送前按 platform_id/org_path 二次过滤';
COMMENT ON COLUMN event_subscription.platform_id IS 'scope 一级：只看这个来源平台';
COMMENT ON COLUMN event_subscription.org_path IS 'scope 二级：只看这棵组织子树（path 前缀）';
COMMENT ON COLUMN event_subscription.last_event_id IS '服务端已发出的最大游标';
