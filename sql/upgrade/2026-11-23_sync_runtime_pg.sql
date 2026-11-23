-- ============================================================================
-- 2026-11-23  M2b 运行保障（PostgreSQL / 瀚高）
--
-- 与 2026-11-23_sync_runtime_mysql.sql 等价，仅方言差异：
--   1) 无 COMMENT 内联写法，用注释说明列义
--   2) sync_record 的批量 INSERT ... SELECT 需要序列生成主键，这里建一个
-- ============================================================================

-- 0) sync_record 批量落库的取号序列（MySQL 用 UUID_SHORT()，PG 没有等价函数）
CREATE SEQUENCE IF NOT EXISTS seq_sync_record START WITH 1 INCREMENT BY 1;

-- 1) 轮次台账（3.13.1）
CREATE TABLE IF NOT EXISTS sync_round
(
    id             BIGINT        NOT NULL,
    tenant_id      BIGINT        NOT NULL DEFAULT 1,
    task_id        BIGINT        NOT NULL,
    task_sn        VARCHAR(64)   NOT NULL,
    platform_id    BIGINT        NOT NULL,
    platform_name  VARCHAR(128)  NULL,
    begin_time     TIMESTAMP(0)  NOT NULL,
    end_time       TIMESTAMP(0)  NULL,
    status         VARCHAR(16)   NOT NULL DEFAULT 'RUNNING',
    org_recv       INT           NOT NULL DEFAULT 0,
    dev_recv       INT           NOT NULL DEFAULT 0,
    ch_recv        INT           NOT NULL DEFAULT 0,
    org_insert     INT           NOT NULL DEFAULT 0,
    dev_insert     INT           NOT NULL DEFAULT 0,
    dev_update     INT           NOT NULL DEFAULT 0,
    dev_delete     INT           NOT NULL DEFAULT 0,
    dev_dup        INT           NOT NULL DEFAULT 0,
    delete_skipped SMALLINT      NOT NULL DEFAULT 0,
    guard_ratio    NUMERIC(5,4)  NOT NULL DEFAULT 0.0000,
    org_missing    INT           NOT NULL DEFAULT 0,
    result         VARCHAR(32)   NULL,
    summary        VARCHAR(512)  NULL,
    error_code     VARCHAR(64)   NULL,
    error_msg      VARCHAR(512)  NULL,
    create_time    TIMESTAMP(0)  NOT NULL,
    update_time    TIMESTAMP(0)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sync_round_task UNIQUE (task_id)
);
CREATE INDEX IF NOT EXISTS idx_sync_round_platform_time ON sync_round (platform_id, begin_time);
CREATE INDEX IF NOT EXISTS idx_sync_round_status ON sync_round (status);

COMMENT ON TABLE sync_round IS '同步轮次台账：每轮一行，运维对账的第一现场';
COMMENT ON COLUMN sync_round.delete_skipped IS 'G2 是否跳过删除：1=跳过（必须在台账可见）';
COMMENT ON COLUMN sync_round.guard_ratio IS '本轮当时的 G2 阈值快照（C23，必须落盘）';

-- 2) 原始报文快照（3.13.2，C 档：做表与开关，默认关闭）
CREATE TABLE IF NOT EXISTS sync_round_snapshot
(
    id            BIGINT        NOT NULL,
    tenant_id     BIGINT        NOT NULL DEFAULT 1,
    task_id       BIGINT        NOT NULL,
    platform_id   BIGINT        NOT NULL,
    item_type     VARCHAR(16)   NOT NULL,
    code          VARCHAR(64)   NOT NULL,
    parent_code   VARCHAR(64)   NULL,
    name          VARCHAR(128)  NOT NULL,
    online_status VARCHAR(8)    NULL,
    device_code   VARCHAR(64)   NULL,
    stream_type   VARCHAR(16)   NULL,
    capability    VARCHAR(128)  NULL,
    device_ip     VARCHAR(64)   NULL,
    vendor        VARCHAR(32)   NULL,
    model         VARCHAR(64)   NULL,
    group_type    VARCHAR(16)   NULL,
    create_time   TIMESTAMP(0)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sync_snapshot_task_item UNIQUE (task_id, item_type, code)
);
CREATE INDEX IF NOT EXISTS idx_sync_snapshot_platform_time ON sync_round_snapshot (platform_id, create_time);
CREATE INDEX IF NOT EXISTS idx_sync_snapshot_task ON sync_round_snapshot (task_id);

COMMENT ON TABLE sync_round_snapshot IS '原始报文快照：默认关闭，排障期打开，只保留最近 N 轮';

-- 3) 组织缺失设备的延迟重试队列（3.8.2）
CREATE TABLE IF NOT EXISTS sync_org_pending
(
    id           BIGINT        NOT NULL,
    tenant_id    BIGINT        NOT NULL DEFAULT 1,
    platform_id  BIGINT        NOT NULL,
    device_code  VARCHAR(64)   NOT NULL,
    parent_code  VARCHAR(64)   NULL,
    create_time  TIMESTAMP(0)  NOT NULL,
    update_time  TIMESTAMP(0)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sync_org_pending_device UNIQUE (platform_id, device_code)
);
CREATE INDEX IF NOT EXISTS idx_sync_org_pending_platform_time ON sync_org_pending (platform_id, create_time);

COMMENT ON TABLE sync_org_pending IS '组织缺失设备延迟重试队列：归属成功即删，仍失败等下轮';

-- 4) 校验
SELECT 'sync_round' AS tbl, COUNT(*) AS cnt FROM sync_round
UNION ALL SELECT 'sync_round_snapshot', COUNT(*) FROM sync_round_snapshot
UNION ALL SELECT 'sync_org_pending', COUNT(*) FROM sync_org_pending;
