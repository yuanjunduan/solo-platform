-- ============================================================================
-- 2026-11-24  M4 上级平台资源共享（MySQL）
--
-- 五张表：out_group（该上级的虚拟组织树）/ out_share（共享关系）/
--         out_code_map（暴露码映射）/ outbox（推送队列）/ publish_round（推送轮次台账）
--
-- 与入站的关系：与 sync_* 完全独立，一个入站事实一个出站事实（C17）。
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行。
-- 顺序：先 out_group，再 out_share（group_id 引用它），再其余。
-- ============================================================================

-- 1) 虚拟组织树：GB28181 上级只认 DeviceID + ParentID 的扁平目录，
--    所以每个上级一棵独立树，本地组织树不能直接当代替（C6）。
CREATE TABLE IF NOT EXISTS out_group
(
    id             BIGINT        NOT NULL,
    tenant_id      BIGINT        NOT NULL DEFAULT 1,
    platform_id    BIGINT        NOT NULL COMMENT '目标上级平台',
    code           VARCHAR(64)   NOT NULL COMMENT '该上级看到的节点码（= 暴露码，域10+215/216+序号7）',
    parent_id      BIGINT        NULL COMMENT '父节点，根为 NULL',
    parent_code    VARCHAR(64)   NULL COMMENT '父节点暴露码（组装报文用，避免逐层回查）',
    path           VARCHAR(1024) NOT NULL COMMENT '物化路径 /id/id/，一键生成/换父时按前缀重写',
    level          SMALLINT      NOT NULL DEFAULT 1,
    name           VARCHAR(128)  NOT NULL,
    group_type     VARCHAR(16)   NOT NULL DEFAULT '216' COMMENT '215 业务分组 / 216 虚拟组织',
    source_org_id  BIGINT        NULL COMMENT '镜像来源组织 id（本地或入站），一键生成幂等键',
    source_org_path VARCHAR(1024) NULL COMMENT '镜像来源组织路径快照，换父时用于重算',
    sort_no        INT           NOT NULL DEFAULT 0,
    remark         VARCHAR(255)  NULL,
    create_time    DATETIME      NOT NULL,
    update_time    DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_out_group_plat_code (platform_id, code),
    KEY idx_out_group_platform (platform_id),
    KEY idx_out_group_parent (parent_id),
    KEY idx_out_group_src (platform_id, source_org_id)
) COMMENT '上级虚拟组织树（215/216）：给"谁"看的投影，不引用本地组织的物理挂载';

-- 2) 共享关系：一个上级一份，与资源本体分离（A5）。
--    规则行（SUBTREE，ref_type=ORG）与资源行（EXPLICIT/派生）同表；
--    剔除不删行、不新增行，而是把对应行 enabled 置 0（优先级高于规则）。
CREATE TABLE IF NOT EXISTS out_share
(
    id                     BIGINT        NOT NULL,
    tenant_id              BIGINT        NOT NULL DEFAULT 1,
    platform_id            BIGINT        NOT NULL COMMENT '目标上级平台',
    group_id               BIGINT        NULL COMMENT '挂在该上级哪个虚拟组织下',
    ref_type               VARCHAR(16)   NOT NULL COMMENT 'ORG=子树规则 / DEVICE / CHANNEL=具体资源',
    ref_id                 BIGINT        NULL COMMENT '资源主键（规则行=组织 id）',
    ref_source_type        VARCHAR(16)   NOT NULL COMMENT 'NATIVE / INBOUND（来源，保证可追溯）',
    ref_source_platform_id BIGINT        NOT NULL DEFAULT 0,
    ref_code               VARCHAR(64)   NOT NULL COMMENT '资源原始编码（规则行为组织码）',
    org_path               VARCHAR(1024) NULL COMMENT '资源所在组织路径；规则行为规则路径，用于前缀命中',
    scope_mode             VARCHAR(16)   NOT NULL DEFAULT 'EXPLICIT' COMMENT 'EXPLICIT / SUBTREE',
    derived                TINYINT       NOT NULL DEFAULT 0 COMMENT '1=由 SUBTREE 规则派生（非人工逐条勾选）',
    enabled                TINYINT       NOT NULL DEFAULT 1 COMMENT '0=排除（剔除/停用），优先级高于规则',
    ref_alive              TINYINT       NOT NULL DEFAULT 1 COMMENT '0=资源已 REMOVED，本平台已撤销但保留关系便于恢复（C12）',
    ref_online_status      VARCHAR(8)    NULL COMMENT '上次推给上级的在线状态 ON/OFF；变化时补发 ON/OFF 事件（状态跟随，6.6）',
    status                 VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SYNCED/FAILED/DEAD/REVOKING（3.9.6）',
    last_error             VARCHAR(512)  NULL,
    last_pushed_at         DATETIME      NULL,
    operator               VARCHAR(64)   NULL,
    create_time            DATETIME      NOT NULL,
    update_time            DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_out_share (platform_id, ref_source_type, ref_source_platform_id, ref_code),
    KEY idx_out_share_platform (platform_id, enabled, ref_alive),
    KEY idx_out_share_group (group_id),
    KEY idx_out_share_ref (ref_type, ref_id),
    KEY idx_out_share_path (org_path(64))
) COMMENT '共享关系：资源 ↔ 组 ↔ 上级；撤销只置 ref_alive=0，不删行（D2）';

-- 3) 编码映射：入站不改码，出站必重编号（3.9.3）。
--    seq_no 平台内单调、删除不复用；同一资源恢复时复用自身墓碑码。
CREATE TABLE IF NOT EXISTS out_code_map
(
    id           BIGINT       NOT NULL,
    tenant_id    BIGINT       NOT NULL DEFAULT 1,
    platform_id  BIGINT       NOT NULL,
    ref_type     VARCHAR(16)  NOT NULL COMMENT 'CHANNEL / DEVICE / GROUP',
    native_code  VARCHAR(64)  NOT NULL COMMENT '资源原始编码（本平台内唯一键的一部分）',
    exposed_code VARCHAR(64)  NOT NULL COMMENT '对上级暴露码：域10 + 类型3 + 序号7（C16）',
    type_seg     VARCHAR(3)   NOT NULL COMMENT '类型段：200 设备 / 131 通道 / 215 业务分组 / 216 虚拟组织',
    seq_no       BIGINT       NOT NULL COMMENT '平台内分配序号，删除不复用',
    state        VARCHAR(16)  NOT NULL COMMENT 'ACTIVE / TOMBSTONE',
    create_time  DATETIME     NOT NULL,
    update_time  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_out_code_exposed (platform_id, exposed_code),
    UNIQUE KEY uk_out_code_native (platform_id, ref_type, native_code),
    KEY idx_out_code_seq (platform_id, type_seg, seq_no)
) COMMENT '编码映射：一个上级一套暴露码，分配后不随资源改名/移动变化';

-- 4) 推送队列（本地消息表，C15：与共享变更同事务写入）。
--    seq_no 是平台内单调号，供上级判新旧；uk 保证不跳号不重号。
CREATE TABLE IF NOT EXISTS outbox
(
    id            BIGINT       NOT NULL,
    tenant_id     BIGINT       NOT NULL DEFAULT 1,
    platform_id   BIGINT       NOT NULL,
    seq_no        BIGINT       NOT NULL COMMENT '平台内单调递增（同一轮推送内保序）',
    event_type    VARCHAR(16)  NOT NULL COMMENT 'ADD/UPDATE/DELETE/REFRESH',
    ref_type      VARCHAR(16)  NOT NULL COMMENT 'GROUP / CHANNEL / DEVICE',
    ref_id        BIGINT       NULL,
    ref_code      VARCHAR(64)  NULL COMMENT '资源原始编码',
    exposed_code  VARCHAR(64)  NULL COMMENT '节点暴露码（报文里的 DeviceID）',
    payload       TEXT         NULL COMMENT '组装报文所需快照（JSON 文本）：只带定位与名称，跨方言统一按文本存',
    version       BIGINT       NOT NULL DEFAULT 0 COMMENT '目录版本，供上级判新旧',
    status        VARCHAR(8)   NOT NULL COMMENT 'PENDING/SENT/FAILED/DEAD/PAUSED',
    retry_count   INT          NOT NULL DEFAULT 0,
    next_retry_at DATETIME     NULL,
    last_error    VARCHAR(512) NULL,
    round_id      BIGINT       NULL COMMENT '投递它的 publish_round.id',
    create_time   DATETIME     NOT NULL,
    update_time   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_plat_seq (platform_id, seq_no),
    KEY idx_outbox_dispatch (status, next_retry_at),
    KEY idx_outbox_ref (platform_id, ref_type, ref_code),
    KEY idx_outbox_round (round_id)
) COMMENT '出站变更事件：与 out_share/out_code_map 同事务写入，Dispatcher 按平台分片投递';

-- 5) 推送轮次台账：出站事实，与 sync_round 同口径但独立建表（C17）。
--    必须落盘"应推/实推/失败/是否降级/当时阈值"，否则无法回答"上一轮为什么没补齐"。
CREATE TABLE IF NOT EXISTS publish_round
(
    id            BIGINT       NOT NULL,
    tenant_id     BIGINT       NOT NULL DEFAULT 1,
    platform_id   BIGINT       NOT NULL,
    platform_name VARCHAR(128) NULL,
    trigger_type  VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/SCHEDULED/RECONCILE/RESOURCE',
    status        VARCHAR(16)  NOT NULL COMMENT 'RUNNING/DONE/FAILED/SKIPPED',
    planned       INT          NOT NULL DEFAULT 0 COMMENT '本轮应推条数',
    sent          INT          NOT NULL DEFAULT 0 COMMENT '实际提交成功条数',
    failed        INT          NOT NULL DEFAULT 0,
    degraded      TINYINT      NOT NULL DEFAULT 0 COMMENT '1=超阈值已合并为 REFRESH',
    threshold     INT          NOT NULL DEFAULT 0 COMMENT '当时的 publish.detail-max',
    error_code    VARCHAR(64)  NULL,
    error_msg     VARCHAR(512) NULL,
    summary       VARCHAR(512) NULL COMMENT '可直接展示的一句话摘要',
    begin_time    DATETIME     NOT NULL,
    end_time      DATETIME     NULL,
    create_time   DATETIME     NOT NULL,
    update_time   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_publish_round_platform (platform_id, begin_time),
    KEY idx_publish_round_status (status)
) COMMENT '推送轮次台账：一轮一行，出站对账依据，不可重写';

-- 6) 校验
SELECT 'out_group' AS tbl, COUNT(*) AS cnt FROM out_group
UNION ALL SELECT 'out_share', COUNT(*) FROM out_share
UNION ALL SELECT 'out_code_map', COUNT(*) FROM out_code_map
UNION ALL SELECT 'outbox', COUNT(*) FROM outbox
UNION ALL SELECT 'publish_round', COUNT(*) FROM publish_round;
