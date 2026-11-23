-- ============================================================================
-- 2026-11-23  M2b 运行保障（MySQL）
--
-- 依据：架构文档 3.13.1 轮次台账 / 3.13.2 报文快照 / 3.8.2 组织缺失延迟重试
-- 与 2026-11-23_sync_runtime_pg.sql 等价，仅方言差异
--
-- 三张表都是"运维辅助表"，不计入 3.5.3 的 15 张业务表：
--   sync_round           每轮一行，长期保留（对账依据，不可重写）
--   sync_round_snapshot  原始报文（默认关闭，只留最近 N 轮）
--   sync_org_pending     组织缺失设备的延迟重试队列（成功归属后即删）
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行。
-- ============================================================================

-- 1) 轮次台账（3.13.1）：sync_task 是"任务状态"，本表是"执行事实"，两者不能合并
CREATE TABLE IF NOT EXISTS sync_round
(
    id             BIGINT       NOT NULL,
    tenant_id      BIGINT       NOT NULL DEFAULT 1,
    task_id        BIGINT       NOT NULL,
    task_sn        VARCHAR(64)  NOT NULL COMMENT '轮次业务号（与 sync_task.task_sn 一致）',
    platform_id    BIGINT       NOT NULL,
    platform_name  VARCHAR(128) NULL COMMENT '当时平台名快照，平台改名后仍能对账',
    begin_time     DATETIME     NOT NULL,
    end_time       DATETIME     NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/DONE/FAILED',
    org_recv       INT          NOT NULL DEFAULT 0 COMMENT '收到组织数（去重后）',
    dev_recv       INT          NOT NULL DEFAULT 0 COMMENT '收到设备数（去重后）',
    ch_recv        INT          NOT NULL DEFAULT 0 COMMENT '收到通道数（去重后）',
    org_insert     INT          NOT NULL DEFAULT 0 COMMENT '组织新增影响行数',
    dev_insert     INT          NOT NULL DEFAULT 0 COMMENT '设备新增影响行数',
    dev_update     INT          NOT NULL DEFAULT 0 COMMENT '设备差异更新影响行数',
    dev_delete     INT          NOT NULL DEFAULT 0 COMMENT '设备消失影响行数（含被 G2 跳过时为 0）',
    dev_dup        INT          NOT NULL DEFAULT 0 COMMENT '重复业务键数（staging upsert 后为 0，预留）',
    delete_skipped TINYINT      NOT NULL DEFAULT 0 COMMENT 'G2 是否跳过删除：1=跳过',
    guard_ratio    DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT '本轮当时的 G2 阈值快照（C23，必须落盘）',
    org_missing    INT          NOT NULL DEFAULT 0 COMMENT '本轮落到[未分类]的设备数（C13 第4步）',
    result         VARCHAR(32)  NULL COMMENT '结果短标记：SUCCESS/DELETE_SKIPPED/EMPTY_STAGING…',
    summary        VARCHAR(512) NULL COMMENT '可直接展示的一句话摘要',
    error_code     VARCHAR(64)  NULL,
    error_msg      VARCHAR(512) NULL,
    create_time    DATETIME     NOT NULL,
    update_time    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task (task_id),
    KEY idx_platform_time (platform_id, begin_time),
    KEY idx_status (status)
) COMMENT '同步轮次台账：每轮一行，运维对账的第一现场';

-- 2) 原始报文快照（3.13.2，C 档：做表与开关，默认关闭）
--    容量：100 万通道 × 2 轮 × 约 200 字节 ≈ 400MB；只留最近 N 轮是硬约束
CREATE TABLE IF NOT EXISTS sync_round_snapshot
(
    id            BIGINT       NOT NULL,
    tenant_id     BIGINT       NOT NULL DEFAULT 1,
    task_id       BIGINT       NOT NULL,
    platform_id   BIGINT       NOT NULL,
    item_type     VARCHAR(16)  NOT NULL COMMENT 'ORG/GROUP/DEVICE/CHANNEL',
    code          VARCHAR(64)  NOT NULL,
    parent_code   VARCHAR(64)  NULL,
    name          VARCHAR(128) NOT NULL,
    online_status VARCHAR(8)   NULL,
    device_code   VARCHAR(64)  NULL,
    stream_type   VARCHAR(16)  NULL,
    capability    VARCHAR(128) NULL,
    device_ip     VARCHAR(64)  NULL,
    vendor        VARCHAR(32)  NULL,
    model         VARCHAR(64)  NULL,
    group_type    VARCHAR(16)  NULL,
    create_time   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_item (task_id, item_type, code),
    KEY idx_platform_time (platform_id, create_time),
    KEY idx_task (task_id)
) COMMENT '原始报文快照：默认关闭，排障期打开，只保留最近 N 轮';

-- 3) 组织缺失设备的延迟重试队列（3.8.2）
--    staging 收尾要清，但 parent_code 只在 staging 里；重试需要它才能重走 ①~④
CREATE TABLE IF NOT EXISTS sync_org_pending
(
    id           BIGINT      NOT NULL,
    tenant_id    BIGINT      NOT NULL DEFAULT 1,
    platform_id  BIGINT      NOT NULL,
    device_code  VARCHAR(64) NOT NULL,
    parent_code  VARCHAR(64) NULL COMMENT '对端给的父编码，重试时重走组织命中的第 ① 步',
    create_time  DATETIME    NOT NULL COMMENT '登记时间：防抖延迟 N 秒、超上限强制跑都按它算',
    update_time  DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_device (platform_id, device_code),
    KEY idx_platform_time (platform_id, create_time)
) COMMENT '组织缺失设备延迟重试队列：归属成功即删，仍失败等下轮';

-- 4) 校验
SELECT 'sync_round' AS tbl, COUNT(*) AS cnt FROM sync_round
UNION ALL SELECT 'sync_round_snapshot', COUNT(*) FROM sync_round_snapshot
UNION ALL SELECT 'sync_org_pending', COUNT(*) FROM sync_org_pending;
