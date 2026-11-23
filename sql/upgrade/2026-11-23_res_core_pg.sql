-- ============================================================================
-- 2026-11-23  目标架构 M1：资源中心四表 + 平台连接 + 凭证 + 审计（PostgreSQL）
--
-- 与 2026-11-23_res_core_mysql.sql 等价，仅方言差异：
--   * 库内唯一/普通索引用 CREATE ... INDEX IF NOT EXISTS 单独声明
--   * JSON 用 jsonb；时间用 timestamp(0)
-- 幂等：可重复执行。
-- ============================================================================

-- 1) 平台连接
CREATE TABLE IF NOT EXISTS platform
(
    id             bigint       NOT NULL,
    tenant_id      bigint       NOT NULL DEFAULT 1,
    code           varchar(64)  NOT NULL,
    name           varchar(128) NOT NULL,
    vendor         varchar(32)  NOT NULL,
    protocol       varchar(32)  NOT NULL,
    sync_protocol  varchar(32)  NOT NULL DEFAULT 'SIP_GB28181',
    publish_protocol varchar(32) NOT NULL DEFAULT 'SIP_GB28181',
    media_protocol varchar(32)  NOT NULL DEFAULT 'SIP_GB28181',
    host           varchar(64)  NOT NULL,
    port           int          NOT NULL,
    local_domain   varchar(32)  NOT NULL,
    accept_inbound smallint     NOT NULL DEFAULT 0,
    push_outbound  smallint     NOT NULL DEFAULT 0,
    status         varchar(16)  NOT NULL DEFAULT 'OFFLINE',
    last_online_at timestamp(0) DEFAULT NULL,
    sync_policy    varchar(16)  NOT NULL DEFAULT 'SCHEDULED',
    sync_cron      varchar(32)  DEFAULT NULL,
    delete_guard_ratio numeric(5,4) NOT NULL DEFAULT 0,
    remark         varchar(512) DEFAULT NULL,
    create_time    timestamp(0) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    timestamp(0) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE platform IS '平台连接：一个外部平台一条记录，方向由能力位表达';
COMMENT ON COLUMN platform.accept_inbound IS '1=可向本平台推目录（它是我的下级）';
COMMENT ON COLUMN platform.push_outbound IS '1=本平台可向它推目录（它是我的上级）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_platform_code ON platform (code);
CREATE INDEX IF NOT EXISTS idx_platform_status ON platform (status);
CREATE INDEX IF NOT EXISTS idx_platform_vendor ON platform (vendor);

-- 2) 平台凭证（GB35114 首版只占位）
CREATE TABLE IF NOT EXISTS platform_credential
(
    id                 bigint       NOT NULL,
    tenant_id          bigint       NOT NULL DEFAULT 1,
    platform_id        bigint       NOT NULL,
    username           varchar(128) DEFAULT NULL,
    password_cipher    varchar(512) DEFAULT NULL,
    cert_cipher        text         DEFAULT NULL,
    private_key_cipher text         DEFAULT NULL,
    cipher_suite       varchar(32)  NOT NULL DEFAULT 'NONE',
    gb35114_cert_id    varchar(128) DEFAULT NULL,
    gb35114_cert_type  varchar(32)  DEFAULT NULL,
    gb35114_key_alg    varchar(32)  DEFAULT NULL,
    gb35114_proto_ver  varchar(32)  DEFAULT NULL,
    gb35114_ext        varchar(512) DEFAULT NULL,
    create_time        timestamp(0) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        timestamp(0) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE platform_credential IS '平台认证配置：密文存储，查询只返回掩码';

CREATE UNIQUE INDEX IF NOT EXISTS uk_credential_platform ON platform_credential (platform_id);

-- 3) 组织
CREATE TABLE IF NOT EXISTS res_org
(
    id                 bigint        NOT NULL,
    tenant_id          bigint        NOT NULL DEFAULT 1,
    code               varchar(64)   NOT NULL,
    parent_code        varchar(64)   DEFAULT NULL,
    parent_id          bigint        DEFAULT NULL,
    path               varchar(1024) NOT NULL,
    level              smallint      NOT NULL DEFAULT 1,
    name               varchar(128)  NOT NULL,
    local_name         varchar(128)  DEFAULT NULL,
    source_type        varchar(16)   NOT NULL,
    source_platform_id bigint        NOT NULL DEFAULT 0,
    origin_chain       varchar(128)  NOT NULL DEFAULT '',
    hop_count          smallint      NOT NULL DEFAULT 0,
    status             varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    removed_at         timestamp(0)  DEFAULT NULL,
    org_missing        smallint      NOT NULL DEFAULT 0,
    unclassified       smallint      NOT NULL DEFAULT 0,
    sort_no            int           NOT NULL DEFAULT 0,
    create_time        timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE res_org IS '组织（本地与下级镜像同表，来源作列）';
COMMENT ON COLUMN res_org.path IS '物化路径 /id/id/，子树查询用前缀匹配';

CREATE UNIQUE INDEX IF NOT EXISTS uk_res_org_src ON res_org (source_type, source_platform_id, code);
CREATE INDEX IF NOT EXISTS idx_res_org_parent ON res_org (parent_id);
CREATE INDEX IF NOT EXISTS idx_res_org_path ON res_org (path);
CREATE INDEX IF NOT EXISTS idx_res_org_src ON res_org (source_platform_id, status);

-- 4) 目录分组（215 业务分组 / 216 虚拟组织）
CREATE TABLE IF NOT EXISTS res_group
(
    id                 bigint        NOT NULL,
    tenant_id          bigint        NOT NULL DEFAULT 1,
    code               varchar(64)   NOT NULL,
    name               varchar(128)  NOT NULL,
    parent_id          bigint        DEFAULT NULL,
    path               varchar(1024) NOT NULL,
    level              smallint      NOT NULL DEFAULT 1,
    group_type         varchar(16)   NOT NULL DEFAULT '215',
    source_type        varchar(16)   NOT NULL,
    source_platform_id bigint        NOT NULL DEFAULT 0,
    origin_chain       varchar(128)  NOT NULL DEFAULT '',
    hop_count          smallint      NOT NULL DEFAULT 0,
    status             varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    removed_at         timestamp(0)  DEFAULT NULL,
    sort_no            int           NOT NULL DEFAULT 0,
    create_time        timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE res_group IS '目录分组：215 业务分组 / 216 虚拟组织';

CREATE UNIQUE INDEX IF NOT EXISTS uk_res_group_src ON res_group (source_type, source_platform_id, code);
CREATE INDEX IF NOT EXISTS idx_res_group_parent ON res_group (parent_id);
CREATE INDEX IF NOT EXISTS idx_res_group_src ON res_group (source_platform_id, status);

-- 5) 设备
CREATE TABLE IF NOT EXISTS res_device
(
    id                 bigint        NOT NULL,
    tenant_id          bigint        NOT NULL DEFAULT 1,
    code               varchar(64)   NOT NULL,
    name               varchar(128)  NOT NULL,
    local_name         varchar(128)  DEFAULT NULL,
    org_id             bigint        NOT NULL,
    org_path           varchar(1024) NOT NULL,
    device_ip          varchar(64)   DEFAULT NULL,
    vendor             varchar(32)   DEFAULT NULL,
    model              varchar(64)   DEFAULT NULL,
    channel_count      int           NOT NULL DEFAULT 0,
    fingerprint        varchar(64)   DEFAULT NULL,
    source_type        varchar(16)   NOT NULL,
    source_platform_id bigint        NOT NULL DEFAULT 0,
    origin_platform_id bigint        NOT NULL DEFAULT 0,
    origin_code        varchar(64)   NOT NULL DEFAULT '',
    origin_chain       varchar(128)  NOT NULL DEFAULT '',
    hop_count          smallint      NOT NULL DEFAULT 0,
    status             varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    removed_at         timestamp(0)  DEFAULT NULL,
    org_missing        smallint      NOT NULL DEFAULT 0,
    create_time        timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE res_device IS '设备：本地与下级镜像同表，含跨路径识别指纹';
COMMENT ON COLUMN res_device.fingerprint IS '去重强证据：稳定分量（IPv6+厂商+机型）哈希，不含通道数等易变项';

CREATE UNIQUE INDEX IF NOT EXISTS uk_res_device_src ON res_device (source_type, source_platform_id, code);
CREATE INDEX IF NOT EXISTS idx_res_device_org ON res_device (org_id);
CREATE INDEX IF NOT EXISTS idx_res_device_src ON res_device (source_platform_id, status);
CREATE INDEX IF NOT EXISTS idx_res_device_fp ON res_device (fingerprint);

-- 6) 通道
CREATE TABLE IF NOT EXISTS res_channel
(
    id                 bigint        NOT NULL,
    tenant_id          bigint        NOT NULL DEFAULT 1,
    code               varchar(64)   NOT NULL,
    name               varchar(128)  NOT NULL,
    local_name         varchar(128)  DEFAULT NULL,
    org_id             bigint        NOT NULL,
    org_path           varchar(1024) NOT NULL,
    device_id          bigint        NOT NULL,
    group_id           bigint        DEFAULT NULL,
    stream_type        varchar(16)   NOT NULL DEFAULT 'MAIN',
    online_status      varchar(8)    NOT NULL DEFAULT 'OFF',
    capability         varchar(128)  NOT NULL DEFAULT '',
    media_hint         varchar(512)  DEFAULT NULL,
    source_type        varchar(16)   NOT NULL,
    source_platform_id bigint        NOT NULL DEFAULT 0,
    origin_platform_id bigint        NOT NULL DEFAULT 0,
    origin_code        varchar(64)   NOT NULL DEFAULT '',
    code_chain         jsonb         DEFAULT NULL,
    origin_chain       varchar(128)  NOT NULL DEFAULT '',
    hop_count          smallint      NOT NULL DEFAULT 0,
    canonical_id       bigint        NOT NULL DEFAULT 0,
    status             varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    removed_at         timestamp(0)  DEFAULT NULL,
    org_missing        smallint      NOT NULL DEFAULT 0,
    create_time        timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE res_channel IS '通道：位置(org) + 载体(device) 双链路；来源三元组 + 全局身份 + 实体组';

CREATE UNIQUE INDEX IF NOT EXISTS uk_res_channel_src ON res_channel (source_type, source_platform_id, code);
CREATE INDEX IF NOT EXISTS idx_res_channel_org ON res_channel (org_id);
CREATE INDEX IF NOT EXISTS idx_res_channel_org_path ON res_channel (org_path);
CREATE INDEX IF NOT EXISTS idx_res_channel_device ON res_channel (device_id);
CREATE INDEX IF NOT EXISTS idx_res_channel_src ON res_channel (source_platform_id, status);
CREATE INDEX IF NOT EXISTS idx_res_channel_origin ON res_channel (origin_platform_id, origin_code);
CREATE INDEX IF NOT EXISTS idx_res_channel_canonical ON res_channel (canonical_id);

-- 7) 审计
CREATE TABLE IF NOT EXISTS audit_log
(
    id            bigint        NOT NULL,
    tenant_id     bigint        NOT NULL DEFAULT 1,
    operator_id   bigint        DEFAULT NULL,
    operator_name varchar(64)   DEFAULT NULL,
    action        varchar(64)   NOT NULL,
    target_type   varchar(32)   NOT NULL,
    target_id     varchar(64)   DEFAULT NULL,
    detail        varchar(1024) DEFAULT NULL,
    client_ip     varchar(64)   DEFAULT NULL,
    create_time   timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE audit_log IS '审计日志：只追加，异步可丢，可配置关闭';

CREATE INDEX IF NOT EXISTS idx_audit_target ON audit_log (target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_audit_operator_time ON audit_log (operator_id, create_time);
CREATE INDEX IF NOT EXISTS idx_audit_create_time ON audit_log (create_time);

-- ============================================================
-- M2a 增量：同步主链 5 张表（PostgreSQL）
-- ============================================================

-- 8) 同步任务。active_platform_id 为生成列，供互斥唯一索引使用（C24）
CREATE TABLE IF NOT EXISTS sync_task
(
    id                  bigint        NOT NULL,
    tenant_id           bigint        NOT NULL DEFAULT 1,
    task_sn             varchar(64)   NOT NULL,
    platform_id         bigint        NOT NULL,
    trigger_type        varchar(16)   NOT NULL DEFAULT 'MANUAL',
    status              varchar(16)   NOT NULL DEFAULT 'PENDING',
    progress            int           NOT NULL DEFAULT 0,
    expected_total      int           NOT NULL DEFAULT 0,
    recv_total          int           NOT NULL DEFAULT 0,
    org_recv            int           NOT NULL DEFAULT 0,
    dev_recv            int           NOT NULL DEFAULT 0,
    ch_recv             int           NOT NULL DEFAULT 0,
    added_count         int           NOT NULL DEFAULT 0,
    updated_count       int           NOT NULL DEFAULT 0,
    stale_count         int           NOT NULL DEFAULT 0,
    failed_count        int           NOT NULL DEFAULT 0,
    delete_skipped      smallint      NOT NULL DEFAULT 0,
    guard_ratio         numeric(5,4)  NOT NULL DEFAULT 0,
    org_missing_count   int           NOT NULL DEFAULT 0,
    catalog_fingerprint varchar(64)   DEFAULT NULL,
    error_code          varchar(64)   DEFAULT NULL,
    error_msg           varchar(512)  DEFAULT NULL,
    begin_time          timestamp(0)  DEFAULT NULL,
    end_time            timestamp(0)  DEFAULT NULL,
    create_time         timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active_platform_id  bigint GENERATED ALWAYS AS (
                            CASE WHEN status IN ('PENDING', 'RUNNING', 'SYNCHING')
                                 THEN platform_id ELSE NULL END) STORED,
    PRIMARY KEY (id)
);
COMMENT ON TABLE sync_task IS '同步任务：一轮全量一行，三段会话的状态载体';
COMMENT ON COLUMN sync_task.active_platform_id IS '互斥用生成列：活跃态才有值，NULL 不参与唯一约束';

CREATE UNIQUE INDEX IF NOT EXISTS uk_sync_task_sn ON sync_task (task_sn);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sync_task_active ON sync_task (active_platform_id);
CREATE INDEX IF NOT EXISTS idx_sync_task_platform_time ON sync_task (platform_id, create_time);
CREATE INDEX IF NOT EXISTS idx_sync_task_status ON sync_task (status);

-- 9) 本轮远端基准（主链路，不是旁路，C14）
CREATE TABLE IF NOT EXISTS sync_staging
(
    id                 bigint        NOT NULL,
    tenant_id          bigint        NOT NULL DEFAULT 1,
    task_id            bigint        NOT NULL,
    platform_id        bigint        NOT NULL,
    item_type          varchar(16)   NOT NULL,
    code               varchar(64)   NOT NULL,
    parent_code        varchar(64)   DEFAULT NULL,
    name               varchar(128)  NOT NULL,
    online_status      varchar(8)    NOT NULL DEFAULT 'ON',
    device_code        varchar(64)   DEFAULT NULL,
    stream_type        varchar(16)   DEFAULT NULL,
    capability         varchar(128)  DEFAULT NULL,
    media_hint         varchar(512)  DEFAULT NULL,
    device_ip          varchar(64)   DEFAULT NULL,
    vendor             varchar(32)   DEFAULT NULL,
    model              varchar(64)   DEFAULT NULL,
    group_type         varchar(16)   DEFAULT NULL,
    origin_platform_id bigint        NOT NULL DEFAULT 0,
    origin_code        varchar(64)   NOT NULL DEFAULT '',
    origin_chain       varchar(128)  NOT NULL DEFAULT '',
    hop_count          smallint      NOT NULL DEFAULT 0,
    create_time        timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE sync_staging IS '本轮远端目录暂存：集合 SQL 的唯一数据源，禁止内存 diff（C8）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_sync_staging_task_item ON sync_staging (task_id, item_type, code);
CREATE INDEX IF NOT EXISTS idx_sync_staging_task_type ON sync_staging (task_id, item_type);

-- 10) 单条变更留痕
CREATE TABLE IF NOT EXISTS sync_record
(
    id             bigint        NOT NULL,
    tenant_id      bigint        NOT NULL DEFAULT 1,
    task_id        bigint        NOT NULL,
    platform_id    bigint        NOT NULL,
    item_type      varchar(16)   NOT NULL,
    code           varchar(64)   NOT NULL,
    change_type    varchar(16)   NOT NULL,
    before_summary varchar(1024) DEFAULT NULL,
    after_summary  varchar(1024) DEFAULT NULL,
    create_time    timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE sync_record IS '同步明细：单条资源变更留痕';

CREATE INDEX IF NOT EXISTS idx_sync_record_task ON sync_record (task_id);
CREATE INDEX IF NOT EXISTS idx_sync_record_item ON sync_record (item_type, code);
CREATE INDEX IF NOT EXISTS idx_sync_record_time ON sync_record (create_time);

-- 11) 目录指纹
CREATE TABLE IF NOT EXISTS platform_catalog_ver
(
    platform_id     bigint        NOT NULL,
    tenant_id       bigint        NOT NULL DEFAULT 1,
    fingerprint     varchar(64)   NOT NULL,
    item_count      int           NOT NULL DEFAULT 0,
    last_task_id    bigint        DEFAULT NULL,
    last_success_at timestamp(0)  DEFAULT NULL,
    create_time     timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (platform_id)
);
COMMENT ON TABLE platform_catalog_ver IS '平台目录版本：快照指纹，用于跳过无变化的整轮合并';

-- 12) 设备↔组织人工绑定（4 步回填第 ② 步）
CREATE TABLE IF NOT EXISTS device_org_binding
(
    id                 bigint        NOT NULL,
    tenant_id          bigint        NOT NULL DEFAULT 1,
    source_platform_id bigint        NOT NULL DEFAULT 0,
    device_code        varchar(64)   NOT NULL,
    org_id             bigint        NOT NULL,
    operator_id        bigint        DEFAULT NULL,
    create_time        timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        timestamp(0)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE device_org_binding IS '设备↔组织人工绑定：回填第 ② 步命中';

CREATE UNIQUE INDEX IF NOT EXISTS uk_device_org_binding ON device_org_binding (source_platform_id, device_code);
CREATE INDEX IF NOT EXISTS idx_device_org_binding_org ON device_org_binding (org_id);

-- 13) 校验
SELECT 'platform' AS tbl, COUNT(*) AS cnt FROM platform
UNION ALL SELECT 'platform_credential', COUNT(*) FROM platform_credential
UNION ALL SELECT 'res_org', COUNT(*) FROM res_org
UNION ALL SELECT 'res_group', COUNT(*) FROM res_group
UNION ALL SELECT 'res_device', COUNT(*) FROM res_device
UNION ALL SELECT 'res_channel', COUNT(*) FROM res_channel
UNION ALL SELECT 'audit_log', COUNT(*) FROM audit_log
UNION ALL SELECT 'sync_task', COUNT(*) FROM sync_task
UNION ALL SELECT 'sync_staging', COUNT(*) FROM sync_staging
UNION ALL SELECT 'sync_record', COUNT(*) FROM sync_record
UNION ALL SELECT 'platform_catalog_ver', COUNT(*) FROM platform_catalog_ver
UNION ALL SELECT 'device_org_binding', COUNT(*) FROM device_org_binding;
