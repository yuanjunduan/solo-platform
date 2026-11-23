-- ============================================================================
-- 2026-11-24  M4 上级平台资源共享（PostgreSQL）
--
-- 与 2026-11-24_share_core_mysql.sql 等价，仅方言差异：
--   DATETIME  -> TIMESTAMP(0)（秒精度，与 sync_round 等表一致）
--   TINYINT   -> SMALLINT
--   payload 用 TEXT 而非 JSONB（只需整段读取，不需要 JSON 查询；
--   避免 MyBatis 往 jsonb 写入时还要显式 CAST，两方言共用同一套 SQL）
--   COMMENT 内联 -> COMMENT ON
--   前缀索引  -> 普通索引（PG 无前缀索引）
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行。
-- ============================================================================

-- 1) 虚拟组织树（215/216），每个上级一棵
CREATE TABLE IF NOT EXISTS out_group
(
    id              BIGINT        NOT NULL,
    tenant_id       BIGINT        NOT NULL DEFAULT 1,
    platform_id     BIGINT        NOT NULL,
    code            VARCHAR(64)   NOT NULL,
    parent_id       BIGINT        NULL,
    parent_code     VARCHAR(64)   NULL,
    path            VARCHAR(1024) NOT NULL,
    level           SMALLINT      NOT NULL DEFAULT 1,
    name            VARCHAR(128)  NOT NULL,
    group_type      VARCHAR(16)   NOT NULL DEFAULT '216',
    source_org_id   BIGINT        NULL,
    source_org_path VARCHAR(1024) NULL,
    sort_no         INT           NOT NULL DEFAULT 0,
    remark          VARCHAR(255)  NULL,
    create_time     TIMESTAMP(0)  NOT NULL,
    update_time     TIMESTAMP(0)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_out_group_plat_code UNIQUE (platform_id, code)
);

CREATE INDEX IF NOT EXISTS idx_out_group_platform ON out_group (platform_id);
CREATE INDEX IF NOT EXISTS idx_out_group_parent ON out_group (parent_id);
CREATE INDEX IF NOT EXISTS idx_out_group_src ON out_group (platform_id, source_org_id);

COMMENT ON TABLE out_group IS '上级虚拟组织树（215/216）：给"谁"看的投影，不引用本地组织的物理挂载';
COMMENT ON COLUMN out_group.code IS '该上级看到的节点码（= 暴露码，域10+215/216+序号7）';
COMMENT ON COLUMN out_group.source_org_id IS '镜像来源组织 id（本地或入站），一键生成幂等键';

-- 2) 共享关系
CREATE TABLE IF NOT EXISTS out_share
(
    id                     BIGINT        NOT NULL,
    tenant_id              BIGINT        NOT NULL DEFAULT 1,
    platform_id            BIGINT        NOT NULL,
    group_id               BIGINT        NULL,
    ref_type               VARCHAR(16)   NOT NULL,
    ref_id                 BIGINT        NULL,
    ref_source_type        VARCHAR(16)   NOT NULL,
    ref_source_platform_id BIGINT        NOT NULL DEFAULT 0,
    ref_code               VARCHAR(64)   NOT NULL,
    org_path               VARCHAR(1024) NULL,
    scope_mode             VARCHAR(16)   NOT NULL DEFAULT 'EXPLICIT',
    derived                SMALLINT      NOT NULL DEFAULT 0,
    enabled                SMALLINT      NOT NULL DEFAULT 1,
    ref_alive              SMALLINT      NOT NULL DEFAULT 1,
    ref_online_status      VARCHAR(8)    NULL,
    status                 VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    last_error             VARCHAR(512)  NULL,
    last_pushed_at         TIMESTAMP(0)  NULL,
    operator               VARCHAR(64)   NULL,
    create_time            TIMESTAMP(0)  NOT NULL,
    update_time            TIMESTAMP(0)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_out_share UNIQUE (platform_id, ref_source_type, ref_source_platform_id, ref_code)
);

CREATE INDEX IF NOT EXISTS idx_out_share_platform ON out_share (platform_id, enabled, ref_alive);
CREATE INDEX IF NOT EXISTS idx_out_share_group ON out_share (group_id);
CREATE INDEX IF NOT EXISTS idx_out_share_ref ON out_share (ref_type, ref_id);
CREATE INDEX IF NOT EXISTS idx_out_share_path ON out_share (org_path);

COMMENT ON TABLE out_share IS '共享关系：资源 ↔ 组 ↔ 上级；撤销只置 ref_alive=0，不删行（D2）';
COMMENT ON COLUMN out_share.ref_type IS 'ORG=子树规则 / DEVICE / CHANNEL=具体资源';
COMMENT ON COLUMN out_share.scope_mode IS 'EXPLICIT / SUBTREE（3.9.2）';
COMMENT ON COLUMN out_share.enabled IS '0=排除（剔除/停用），优先级高于规则';
COMMENT ON COLUMN out_share.ref_alive IS '0=资源已 REMOVED，本平台已撤销但保留关系便于恢复（C12）';
COMMENT ON COLUMN out_share.status IS 'PENDING/SYNCED/FAILED/DEAD/REVOKING（3.9.6）';

-- 3) 编码映射
CREATE TABLE IF NOT EXISTS out_code_map
(
    id           BIGINT       NOT NULL,
    tenant_id    BIGINT       NOT NULL DEFAULT 1,
    platform_id  BIGINT       NOT NULL,
    ref_type     VARCHAR(16)  NOT NULL,
    native_code  VARCHAR(64)  NOT NULL,
    exposed_code VARCHAR(64)  NOT NULL,
    type_seg     VARCHAR(3)   NOT NULL,
    seq_no       BIGINT       NOT NULL,
    state        VARCHAR(16)  NOT NULL,
    create_time  TIMESTAMP(0) NOT NULL,
    update_time  TIMESTAMP(0) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_out_code_exposed UNIQUE (platform_id, exposed_code),
    CONSTRAINT uk_out_code_native UNIQUE (platform_id, ref_type, native_code)
);

CREATE INDEX IF NOT EXISTS idx_out_code_seq ON out_code_map (platform_id, type_seg, seq_no);

COMMENT ON TABLE out_code_map IS '编码映射：一个上级一套暴露码，分配后不随资源改名/移动变化';
COMMENT ON COLUMN out_code_map.exposed_code IS '对上级暴露码：域10 + 类型3 + 序号7（C16）';
COMMENT ON COLUMN out_code_map.type_seg IS '类型段：200 设备 / 131 通道 / 215 业务分组 / 216 虚拟组织';
COMMENT ON COLUMN out_code_map.seq_no IS '平台内分配序号，删除不复用';
COMMENT ON COLUMN out_code_map.state IS 'ACTIVE / TOMBSTONE';

-- 4) 推送队列
CREATE TABLE IF NOT EXISTS outbox
(
    id            BIGINT       NOT NULL,
    tenant_id     BIGINT       NOT NULL DEFAULT 1,
    platform_id   BIGINT       NOT NULL,
    seq_no        BIGINT       NOT NULL,
    event_type    VARCHAR(16)  NOT NULL,
    ref_type      VARCHAR(16)  NOT NULL,
    ref_id        BIGINT       NULL,
    ref_code      VARCHAR(64)  NULL,
    exposed_code  VARCHAR(64)  NULL,
    payload       TEXT         NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    status        VARCHAR(8)   NOT NULL,
    retry_count   INT          NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP(0) NULL,
    last_error    VARCHAR(512) NULL,
    round_id      BIGINT       NULL,
    create_time   TIMESTAMP(0) NOT NULL,
    update_time   TIMESTAMP(0) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_outbox_plat_seq UNIQUE (platform_id, seq_no)
);

CREATE INDEX IF NOT EXISTS idx_outbox_dispatch ON outbox (status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_outbox_ref ON outbox (platform_id, ref_type, ref_code);
CREATE INDEX IF NOT EXISTS idx_outbox_round ON outbox (round_id);

COMMENT ON TABLE outbox IS '出站变更事件：与 out_share/out_code_map 同事务写入，Dispatcher 按平台分片投递';
COMMENT ON COLUMN outbox.seq_no IS '平台内单调递增（同一轮推送内保序）';
COMMENT ON COLUMN outbox.event_type IS 'ADD/UPDATE/DELETE/REFRESH';
COMMENT ON COLUMN outbox.payload IS '组装报文所需快照：只带定位与名称';
COMMENT ON COLUMN outbox.version IS '目录版本，供上级判新旧';
COMMENT ON COLUMN outbox.status IS 'PENDING/SENT/FAILED/DEAD/PAUSED';

-- 5) 推送轮次台账
CREATE TABLE IF NOT EXISTS publish_round
(
    id            BIGINT       NOT NULL,
    tenant_id     BIGINT       NOT NULL DEFAULT 1,
    platform_id   BIGINT       NOT NULL,
    platform_name VARCHAR(128) NULL,
    trigger_type  VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    status        VARCHAR(16)  NOT NULL,
    planned       INT          NOT NULL DEFAULT 0,
    sent          INT          NOT NULL DEFAULT 0,
    failed        INT          NOT NULL DEFAULT 0,
    degraded      SMALLINT     NOT NULL DEFAULT 0,
    threshold     INT          NOT NULL DEFAULT 0,
    error_code    VARCHAR(64)  NULL,
    error_msg     VARCHAR(512) NULL,
    summary       VARCHAR(512) NULL,
    begin_time    TIMESTAMP(0) NOT NULL,
    end_time      TIMESTAMP(0) NULL,
    create_time   TIMESTAMP(0) NOT NULL,
    update_time   TIMESTAMP(0) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_publish_round_platform ON publish_round (platform_id, begin_time);
CREATE INDEX IF NOT EXISTS idx_publish_round_status ON publish_round (status);

COMMENT ON TABLE publish_round IS '推送轮次台账：一轮一行，出站对账依据，不可重写（C17）';
COMMENT ON COLUMN publish_round.trigger_type IS 'MANUAL/SCHEDULED/RECONCILE/RESOURCE';
COMMENT ON COLUMN publish_round.status IS 'RUNNING/DONE/FAILED/SKIPPED';
COMMENT ON COLUMN publish_round.planned IS '本轮应推条数';
COMMENT ON COLUMN publish_round.degraded IS '1=超阈值已合并为 REFRESH';
COMMENT ON COLUMN publish_round.threshold IS '当时的 publish.detail-max';

-- 6) 校验
SELECT 'out_group' AS tbl, COUNT(*) AS cnt FROM out_group
UNION ALL SELECT 'out_share', COUNT(*) FROM out_share
UNION ALL SELECT 'out_code_map', COUNT(*) FROM out_code_map
UNION ALL SELECT 'outbox', COUNT(*) FROM outbox
UNION ALL SELECT 'publish_round', COUNT(*) FROM publish_round;
