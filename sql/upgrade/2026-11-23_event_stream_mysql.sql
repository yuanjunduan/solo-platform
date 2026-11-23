-- ============================================================================
-- 2026-11-23  M3 资源变更事件流（MySQL）
--
-- 依据：架构文档 3.11.7 资源变更事件的推送与超量降级 / 6.4 第三阶段
-- 与 2026-11-23_event_stream_pg.sql 等价，仅方言差异
--
-- 两张表：
--   change_event        事件流本身（游标源）。SSE 的 Last-Event-ID 就是这里的 seq
--   event_subscription  对外订阅（topic + scope + 游标）
--
-- 为什么事件落库而不是只落 Redis：
--   1. 断线重连要按游标补发，"补发"意味着事件必须还能读出来；
--   2. 一次同步 5 万条变更时只发 1 条 REFRESH_ALL，这条合并决策本身要能回查；
--   3. Redis 只承担"新事件到达"的通知（唤醒长轮询），丢了最多慢一个轮询周期，
--      不会丢事件——与 3.13.3 的旁路纪律一致。
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行。
-- ============================================================================

-- 1) 变更事件流（3.11.7）
--    seq 用自增而非雪花 id：游标只要求"单调且不跳号"，自增天然满足；
--    雪花 id 跨毫秒虽也递增，但同一毫秒内多进程的顺序不可控，做游标会有歧义。
CREATE TABLE IF NOT EXISTS change_event
(
    seq          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '全局游标：SSE 的 Last-Event-ID',
    tenant_id    BIGINT       NOT NULL DEFAULT 1,
    source_type  VARCHAR(16)  NOT NULL COMMENT 'NATIVE/INBOUND，与 res_* 的 source_type 同义',
    platform_id  BIGINT       NULL COMMENT 'INBOUND 的来源平台；NATIVE 为 NULL',
    org_path     VARCHAR(512) NULL COMMENT '写库后回读的组织路径：订阅 scope 二次过滤用',
    item_type    VARCHAR(16)  NOT NULL COMMENT 'ORG/GROUP/DEVICE/CHANNEL',
    code         VARCHAR(64)  NULL COMMENT '资源码；REFRESH_ALL 无明细时为 NULL',
    change_type  VARCHAR(16)  NOT NULL COMMENT 'ADDED/UPDATED/REMOVED/REFRESH',
    shape        VARCHAR(16)  NOT NULL COMMENT 'CHANGED_DETAIL/REFRESH_ALL（3.11.7 的两种形态）',
    payload      TEXT         NULL COMMENT '关键字段 JSON：只带定位不带全量，避免一条事件几百 KB',
    task_id      BIGINT       NULL COMMENT '关联的同步轮次（sync_task.id），便于从事件反查那一轮',
    create_time  DATETIME     NOT NULL,
    PRIMARY KEY (seq),
    KEY idx_tenant_seq (tenant_id, seq),
    KEY idx_platform_seq (platform_id, seq),
    KEY idx_time (create_time)
) COMMENT '资源变更事件流：REFRESH_ALL 也占一行，保证游标连续（事件可合并，游标不能跳号）';

-- 2) 对外订阅（3.5.3 表 14）
--    scope 支持两级（Q10）：platform_id 指定某一来源平台，org_path 指定组织子树前缀；
--    两者都为空 = 全部可见（仍需租户边界）。
--    last_event_id 是服务端记账：客户端没带 Last-Event-ID 时从它续，带了两者取较大值。
CREATE TABLE IF NOT EXISTS event_subscription
(
    id             BIGINT       NOT NULL,
    tenant_id      BIGINT       NOT NULL DEFAULT 1,
    app_id         BIGINT       NOT NULL COMMENT '绑定的开放应用（API Key 归属）',
    app_key        VARCHAR(64)  NOT NULL COMMENT '应用标识快照，应用轮换 Key 后仍能对账',
    topic          VARCHAR(32)  NOT NULL DEFAULT 'resource' COMMENT '订阅主题，首版只有 resource',
    platform_id    BIGINT       NULL COMMENT 'scope 一级：只看这个来源平台',
    org_path       VARCHAR(512) NULL COMMENT 'scope 二级：只看这棵组织子树（path 前缀）',
    last_event_id  BIGINT       NOT NULL DEFAULT 0 COMMENT '服务端已发出的最大游标',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    remark         VARCHAR(255) NULL,
    create_time    DATETIME     NOT NULL,
    update_time    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_key (app_id, topic),
    KEY idx_topic (topic),
    KEY idx_status (status)
) COMMENT '对外订阅：scope 即授权，推送前按 platform_id/org_path 二次过滤';

-- 3) 校验
SELECT 'change_event' AS tbl, COUNT(*) AS cnt FROM change_event
UNION ALL SELECT 'event_subscription', COUNT(*) FROM event_subscription;
