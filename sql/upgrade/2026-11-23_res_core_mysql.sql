-- ============================================================================
-- 2026-11-23  目标架构 M1：资源中心四表 + 平台连接 + 凭证 + 审计（MySQL）
--
-- 依据：架构文档 3.5.3 表清单 / 3.5.4 关键表 DDL
-- 与 2026-11-23_res_core_pg.sql 等价，仅方言差异
--
-- 与 gb_* 的关系：gb_* 是对接多厂家平台的遗留结构，本组表是"来源作列"的统一资源模型，
--   两者并存，不互相引用；M1 起新代码只写本组表。
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行。
-- ============================================================================

-- 1) 平台连接（一个外部平台一条记录，方向由能力位表达）
CREATE TABLE IF NOT EXISTS platform
(
    id             BIGINT       NOT NULL COMMENT '主键',
    tenant_id      BIGINT       NOT NULL DEFAULT 1 COMMENT '预留多租户',
    code           VARCHAR(64)  NOT NULL COMMENT '平台唯一标识（本系统内），建议用国标码',
    name           VARCHAR(128) NOT NULL COMMENT '平台名称',
    vendor         VARCHAR(32)  NOT NULL COMMENT 'HIKVISION/DAHUA/GB28181/OTHER',
    protocol       VARCHAR(32)  NOT NULL COMMENT '旧版单一协议位，M2a 起仅作缺省兜底，实际按下面三个协议列',
    sync_protocol  VARCHAR(32)  NOT NULL DEFAULT 'SIP_GB28181' COMMENT '目录同步协议（3.7.1 三层协议必须分开）',
    publish_protocol VARCHAR(32) NOT NULL DEFAULT 'SIP_GB28181' COMMENT '共享推送协议',
    media_protocol VARCHAR(32)  NOT NULL DEFAULT 'SIP_GB28181' COMMENT '取流协议（同一平台可能三种都不同）',
    host           VARCHAR(64)  NOT NULL COMMENT '平台地址',
    port           INT          NOT NULL COMMENT '平台端口',
    local_domain   VARCHAR(32)  NOT NULL COMMENT '本平台在该连接中使用的 SIP 域/编码前缀',
    accept_inbound TINYINT      NOT NULL DEFAULT 0 COMMENT '1=可向本平台推目录',
    push_outbound  TINYINT      NOT NULL DEFAULT 0 COMMENT '1=本平台可向它推目录',
    status         VARCHAR(16)  NOT NULL DEFAULT 'OFFLINE' COMMENT 'ONLINE/OFFLINE/UNREACHABLE/DISABLED',
    last_online_at DATETIME     NULL COMMENT '最后在线时间',
    sync_policy    VARCHAR(16)  NOT NULL DEFAULT 'SCHEDULED' COMMENT 'MANUAL/SCHEDULED/NOTIFY/BOTH',
    sync_cron      VARCHAR(32)  NULL COMMENT '定时同步表达式',
    delete_guard_ratio DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT 'G2 批量删除保护阈值，0=关闭；首次接入必须手工设 0.2（C25）',
    remark         VARCHAR(512) NULL,
    create_time    DATETIME     NOT NULL,
    update_time    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code),
    KEY idx_status (status),
    KEY idx_vendor (vendor)
) COMMENT '平台连接：一个外部平台一条记录，方向由能力位表达';

-- 2) 平台凭证（密文存储，GB35114 预留 5 个可空占位，首版 cipher_suite=NONE）
CREATE TABLE IF NOT EXISTS platform_credential
(
    id                  BIGINT       NOT NULL,
    tenant_id           BIGINT       NOT NULL DEFAULT 1,
    platform_id         BIGINT       NOT NULL,
    username            VARCHAR(128) NULL COMMENT '认证账号（明文，仅账号）',
    password_cipher     VARCHAR(512) NULL COMMENT '密码密文',
    cert_cipher         TEXT         NULL COMMENT '证书密文',
    private_key_cipher  TEXT         NULL COMMENT '私钥密文',
    cipher_suite        VARCHAR(32)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE/SM2/SM3/SM4（首版 NONE）',
    gb35114_cert_id     VARCHAR(128) NULL COMMENT 'GB35114 证书标识（占位）',
    gb35114_cert_type   VARCHAR(32)  NULL COMMENT 'GB35114 证书类型（占位）',
    gb35114_key_alg     VARCHAR(32)  NULL COMMENT 'GB35114 密钥算法（占位）',
    gb35114_proto_ver   VARCHAR(32)  NULL COMMENT 'GB35114 协议版本（占位）',
    gb35114_ext         VARCHAR(512) NULL COMMENT 'GB35114 扩展位（占位）',
    create_time         DATETIME     NOT NULL,
    update_time         DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform (platform_id)
) COMMENT '平台认证配置：密文存储，查询只返回掩码';

-- 3) 组织（本地与下级镜像同表，来源作列）
CREATE TABLE IF NOT EXISTS res_org
(
    id                 BIGINT        NOT NULL,
    tenant_id          BIGINT        NOT NULL DEFAULT 1,
    code               VARCHAR(64)   NOT NULL COMMENT '组织编码（本地自编 / 上游原始码）',
    parent_code        VARCHAR(64)   NULL COMMENT '父组织编码，根为 NULL',
    parent_id          BIGINT        NULL COMMENT '父组织主键（同源内有效，根为 NULL）',
    path               VARCHAR(1024) NOT NULL COMMENT '物化路径 /id/id/，子树查询用前缀',
    level              SMALLINT      NOT NULL DEFAULT 1 COMMENT '层级，根=1',
    name               VARCHAR(128)  NOT NULL,
    local_name         VARCHAR(128)  NULL COMMENT '本地别名（预留，同步不覆盖）',
    source_type        VARCHAR(16)   NOT NULL COMMENT 'NATIVE / INBOUND',
    source_platform_id BIGINT        NOT NULL DEFAULT 0 COMMENT '0=本地',
    origin_chain       VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '来源链（平台ID逗号串），用于追溯与防环',
    hop_count          SMALLINT      NOT NULL DEFAULT 0 COMMENT '级联跳数',
    status             VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/STALE/OFFLINE/REMOVED',
    removed_at         DATETIME      NULL,
    org_missing        TINYINT       NOT NULL DEFAULT 0 COMMENT '1=4步回填未命中，挂在[未分类]（C13 第4步）',
    unclassified       TINYINT       NOT NULL DEFAULT 0 COMMENT '1=本行就是[未分类]兜底组织',
    sort_no            INT           NOT NULL DEFAULT 0,
    create_time        DATETIME      NOT NULL,
    update_time        DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_src (source_type, source_platform_id, code),
    KEY idx_parent (parent_id),
    KEY idx_path (path(64)),
    KEY idx_src (source_platform_id, status)
) COMMENT '组织（本地与下级镜像同表，来源作列）';

-- 4) 目录分组（215 业务分组 / 216 虚拟组织）
CREATE TABLE IF NOT EXISTS res_group
(
    id                 BIGINT        NOT NULL,
    tenant_id          BIGINT        NOT NULL DEFAULT 1,
    code               VARCHAR(64)   NOT NULL,
    name               VARCHAR(128)  NOT NULL,
    parent_id          BIGINT        NULL,
    path               VARCHAR(1024) NOT NULL,
    level              SMALLINT      NOT NULL DEFAULT 1,
    group_type         VARCHAR(16)   NOT NULL DEFAULT '215' COMMENT '215 业务分组 / 216 虚拟组织',
    source_type        VARCHAR(16)   NOT NULL,
    source_platform_id BIGINT        NOT NULL DEFAULT 0,
    origin_chain       VARCHAR(128)  NOT NULL DEFAULT '',
    hop_count          SMALLINT      NOT NULL DEFAULT 0,
    status             VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    removed_at         DATETIME      NULL,
    sort_no            INT           NOT NULL DEFAULT 0,
    create_time        DATETIME      NOT NULL,
    update_time        DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_src (source_type, source_platform_id, code),
    KEY idx_parent (parent_id),
    KEY idx_src (source_platform_id, status)
) COMMENT '目录分组：215 业务分组 / 216 虚拟组织';

-- 5) 设备（含指纹字段，用于跨路径识别）
CREATE TABLE IF NOT EXISTS res_device
(
    id                 BIGINT       NOT NULL,
    tenant_id          BIGINT       NOT NULL DEFAULT 1,
    code               VARCHAR(64)  NOT NULL COMMENT '设备编码',
    name               VARCHAR(128) NOT NULL,
    local_name         VARCHAR(128) NULL COMMENT '本地别名（预留，同步不覆盖）',
    org_id             BIGINT       NOT NULL COMMENT '所属组织（同源内）',
    org_path           VARCHAR(1024) NOT NULL COMMENT '所属组织路径快照，用于子树筛选',
    device_ip          VARCHAR(64)  NULL,
    vendor             VARCHAR(32)  NULL,
    model              VARCHAR(64)  NULL,
    channel_count      INT          NOT NULL DEFAULT 0,
    fingerprint        VARCHAR(64)  NULL COMMENT '去重强证据：稳定分量哈希（IPv6+厂商+机型），不含通道数等易变项',
    source_type        VARCHAR(16)  NOT NULL,
    source_platform_id BIGINT       NOT NULL DEFAULT 0 COMMENT '0=本地',
    origin_platform_id BIGINT       NOT NULL DEFAULT 0 COMMENT '最上游平台，0=本地或未知',
    origin_code        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '最上游平台上的原始编码',
    origin_chain       VARCHAR(128) NOT NULL DEFAULT '',
    hop_count          SMALLINT     NOT NULL DEFAULT 0,
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    removed_at         DATETIME     NULL,
    org_missing        TINYINT      NOT NULL DEFAULT 0 COMMENT '1=4步回填未命中，挂在[未分类]（C13 第4步）',
    create_time        DATETIME     NOT NULL,
    update_time        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_src (source_type, source_platform_id, code),
    KEY idx_org (org_id),
    KEY idx_src (source_platform_id, status),
    KEY idx_fp (fingerprint)
) COMMENT '设备：本地与下级镜像同表，含跨路径识别指纹';

-- 6) 通道（位置 org + 载体 device 双链路）
CREATE TABLE IF NOT EXISTS res_channel
(
    id                 BIGINT        NOT NULL,
    tenant_id          BIGINT        NOT NULL DEFAULT 1,
    code               VARCHAR(64)   NOT NULL COMMENT '通道编码',
    name               VARCHAR(128)  NOT NULL,
    local_name         VARCHAR(128)  NULL,
    org_id             BIGINT        NOT NULL COMMENT '所属组织（硬约束，不得为 0）',
    org_path           VARCHAR(1024) NOT NULL COMMENT '所属组织路径快照，用于子树筛选',
    device_id          BIGINT        NOT NULL COMMENT '所属设备（取流载体）',
    group_id           BIGINT        NULL COMMENT '若挂在 215/216 分组下，指向 res_group',
    stream_type        VARCHAR(16)   NOT NULL DEFAULT 'MAIN' COMMENT 'MAIN/SUB/THIRD',
    online_status      VARCHAR(8)    NOT NULL DEFAULT 'OFF' COMMENT 'ON/OFF',
    capability         VARCHAR(128)  NOT NULL DEFAULT '' COMMENT 'PTZ/RECORD/AUDIO/TALK 位串',
    media_hint         VARCHAR(512)  NULL COMMENT '取流线索（如上游通道码/RTSP 地址模板），不含凭据',
    source_type        VARCHAR(16)   NOT NULL,
    source_platform_id BIGINT        NOT NULL DEFAULT 0 COMMENT '直接来源平台，0=本地',
    origin_platform_id BIGINT        NOT NULL DEFAULT 0 COMMENT '最上游平台，0=本地或未知',
    origin_code        VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '最上游平台上的原始编码',
    code_chain         JSON          NULL COMMENT '码链，从最上游到直接来源，仅展示与人工核对',
    origin_chain       VARCHAR(128)  NOT NULL DEFAULT '',
    hop_count          SMALLINT      NOT NULL DEFAULT 0,
    canonical_id       BIGINT        NOT NULL DEFAULT 0 COMMENT '同一实体组的主记录 id，0=自身为组主',
    status             VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    removed_at         DATETIME      NULL,
    org_missing        TINYINT       NOT NULL DEFAULT 0 COMMENT '1=通道归属未解析到有效组织，挂在[未分类]',
    create_time        DATETIME      NOT NULL,
    update_time        DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_src (source_type, source_platform_id, code),
    KEY idx_org (org_id),
    KEY idx_org_path (org_path(64)),
    KEY idx_device (device_id),
    KEY idx_src (source_platform_id, status),
    KEY idx_origin (origin_platform_id, origin_code),
    KEY idx_canonical (canonical_id)
) COMMENT '通道：位置(org) + 载体(device) 双链路；来源三元组 + 全局身份 + 实体组';

-- 7) 审计（共享/撤销/删除/导出；只追加，旁路可丢）
CREATE TABLE IF NOT EXISTS audit_log
(
    id            BIGINT       NOT NULL,
    tenant_id     BIGINT       NOT NULL DEFAULT 1,
    operator_id   BIGINT       NULL,
    operator_name VARCHAR(64)  NULL,
    action        VARCHAR(64)  NOT NULL COMMENT 'SAVE_SHARE/REVOKE_SHARE/DELETE_RESOURCE/EXPORT 等',
    target_type   VARCHAR(32)  NOT NULL COMMENT 'ORG/DEVICE/CHANNEL/PLATFORM/SHARE',
    target_id     VARCHAR(64)  NULL,
    detail        VARCHAR(1024) NULL COMMENT '关键字段快照，禁止写凭证与报文原文',
    client_ip     VARCHAR(64)  NULL,
    create_time   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_target (target_type, target_id),
    KEY idx_operator_time (operator_id, create_time),
    KEY idx_create_time (create_time)
) COMMENT '审计日志：只追加，异步可丢，可配置关闭';

-- ============================================================
-- M2a 增量：同步主链 5 张表（3.8.1 三段会话 + 3.8.2 staging/集合 SQL + 3.8.6 台账 + C13 第二步）
-- ============================================================

-- 8) 同步任务（三段会话主体）。active_platform_id 是互斥用的生成列：
--    同平台同时只允许一个活跃任务，靠 DB 唯一约束而不是进程内标志位（C24）
CREATE TABLE IF NOT EXISTS sync_task
(
    id                  BIGINT       NOT NULL,
    tenant_id           BIGINT       NOT NULL DEFAULT 1,
    task_sn             VARCHAR(64)  NOT NULL COMMENT '轮次业务号',
    platform_id         BIGINT       NOT NULL,
    trigger_type        VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/SCHEDULED/NOTIFY',
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SYNCHING/SUCCESS/FAILED/CANCELLED',
    progress            INT          NOT NULL DEFAULT 0 COMMENT '0-100',
    expected_total      INT          NOT NULL DEFAULT 0 COMMENT '对端告知的 SumNum',
    recv_total          INT          NOT NULL DEFAULT 0 COMMENT '已收条数',
    org_recv            INT          NOT NULL DEFAULT 0,
    dev_recv            INT          NOT NULL DEFAULT 0,
    ch_recv             INT          NOT NULL DEFAULT 0,
    added_count         INT          NOT NULL DEFAULT 0,
    updated_count       INT          NOT NULL DEFAULT 0,
    stale_count         INT          NOT NULL DEFAULT 0,
    failed_count        INT          NOT NULL DEFAULT 0,
    delete_skipped      TINYINT      NOT NULL DEFAULT 0 COMMENT 'G2 跳过删除，必须在台账/任务详情/事件三处可见（C11）',
    guard_ratio         DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT '本轮当时的阈值快照，要能回查“那天为什么没删”（C23）',
    org_missing_count   INT          NOT NULL DEFAULT 0 COMMENT '4步回填落到[未分类]的行数，界面必须高亮（C13）',
    catalog_fingerprint VARCHAR(64)  NULL COMMENT '本轮目录指纹，未变则跳过合并',
    error_code          VARCHAR(64)  NULL COMMENT 'SYNC_EMPTY_STAGING 等',
    error_msg           VARCHAR(512) NULL,
    begin_time          DATETIME     NULL,
    end_time            DATETIME     NULL,
    create_time         DATETIME     NOT NULL,
    update_time         DATETIME     NOT NULL,
    active_platform_id  BIGINT AS (CASE WHEN status IN ('PENDING', 'RUNNING', 'SYNCHING')
                                        THEN platform_id ELSE NULL END) STORED
        COMMENT '互斥用：活跃态才有值，NULL 不参与唯一约束（C24）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_sn (task_sn),
    UNIQUE KEY uk_active_platform (active_platform_id),
    KEY idx_platform_time (platform_id, create_time),
    KEY idx_status (status)
) COMMENT '同步任务：一轮全量一行，三段会话的状态载体';

-- 9) 本轮远端基准（不是旁路：写失败必须让整轮失败，C14）
CREATE TABLE IF NOT EXISTS sync_staging
(
    id                 BIGINT       NOT NULL,
    tenant_id          BIGINT       NOT NULL DEFAULT 1,
    task_id            BIGINT       NOT NULL,
    platform_id        BIGINT       NOT NULL,
    item_type          VARCHAR(16)  NOT NULL COMMENT 'ORG/GROUP/DEVICE/CHANNEL',
    code               VARCHAR(64)  NOT NULL,
    parent_code        VARCHAR(64)  NULL COMMENT '对端给的父编码，可能是组织/分组/设备/平台域',
    name               VARCHAR(128) NOT NULL,
    online_status      VARCHAR(8)   NOT NULL DEFAULT 'ON' COMMENT '上游目录项状态 ON/OFF',
    device_code        VARCHAR(64)  NULL COMMENT '通道的载体设备编码，合并时 join res_device.code',
    stream_type        VARCHAR(16)  NULL,
    capability         VARCHAR(128) NULL,
    media_hint         VARCHAR(512) NULL,
    device_ip          VARCHAR(64)  NULL,
    vendor             VARCHAR(32)  NULL,
    model              VARCHAR(64)  NULL,
    group_type         VARCHAR(16)  NULL COMMENT '215/216（仅分组项）',
    origin_platform_id BIGINT       NOT NULL DEFAULT 0,
    origin_code        VARCHAR(64)  NOT NULL DEFAULT '',
    origin_chain       VARCHAR(128) NOT NULL DEFAULT '',
    hop_count          SMALLINT     NOT NULL DEFAULT 0,
    create_time        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_item (task_id, item_type, code),
    KEY idx_task_type (task_id, item_type)
) COMMENT '本轮远端目录暂存：集合 SQL 的唯一数据源，禁止把两边都拉进内存做 diff（C8）';

-- 10) 单条变更留痕（3.8.6）：能回答“这条通道是几点变的、变前叫什么”
CREATE TABLE IF NOT EXISTS sync_record
(
    id             BIGINT        NOT NULL,
    tenant_id      BIGINT        NOT NULL DEFAULT 1,
    task_id        BIGINT        NOT NULL,
    platform_id    BIGINT        NOT NULL,
    item_type      VARCHAR(16)   NOT NULL,
    code           VARCHAR(64)   NOT NULL,
    change_type    VARCHAR(16)   NOT NULL COMMENT 'ADD/UPDATE/STALE/REMOVE/RESTORE',
    before_summary VARCHAR(1024) NULL,
    after_summary  VARCHAR(1024) NULL,
    create_time    DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_task (task_id),
    KEY idx_item (item_type, code),
    KEY idx_time (create_time)
) COMMENT '同步明细：单条资源变更留痕';

-- 11) 目录指纹（3.8.2 第 3e 步）：指纹未变则跳过合并，定时全量的性能关键
CREATE TABLE IF NOT EXISTS platform_catalog_ver
(
    platform_id     BIGINT      NOT NULL,
    tenant_id       BIGINT      NOT NULL DEFAULT 1,
    fingerprint     VARCHAR(64) NOT NULL COMMENT '按 code 排序取哈希',
    item_count      INT         NOT NULL DEFAULT 0,
    last_task_id    BIGINT      NULL,
    last_success_at DATETIME    NULL,
    create_time     DATETIME    NOT NULL,
    update_time     DATETIME    NOT NULL,
    PRIMARY KEY (platform_id)
) COMMENT '平台目录版本：快照指纹，用于跳过无变化的整轮合并';

-- 12) 设备↔组织人工绑定（4 步回填第 ② 步，C13）
CREATE TABLE IF NOT EXISTS device_org_binding
(
    id                 BIGINT      NOT NULL,
    tenant_id          BIGINT      NOT NULL DEFAULT 1,
    source_platform_id BIGINT      NOT NULL DEFAULT 0 COMMENT '0=本地',
    device_code        VARCHAR(64) NOT NULL,
    org_id             BIGINT      NOT NULL,
    operator_id        BIGINT      NULL,
    create_time        DATETIME    NOT NULL,
    update_time        DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_device (source_platform_id, device_code),
    KEY idx_org (org_id)
) COMMENT '设备↔组织人工绑定：上游 parent_code 不可信时的人工纠正，回填第 ② 步命中';

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
