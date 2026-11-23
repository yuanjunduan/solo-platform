-- ============================================================================
-- 2026-11-27  M6 视频业务完善（MySQL）
--
-- 两张表：
--   res_play_record     播放 / 回放请求级记录（谁、哪条通道、实时还是回放、成功失败、多久）
--   res_channel_preset  通道预置位（名称与编号存本地，下发仍走 SIP 云台指令）
--
-- 与 gb_view_record 的分工：gb_view_record 是 ZLM 回调的"观看者级"记录（同一路流 N 个观看者 N 行），
-- 本表是"请求级"记录（一次点播一行，含失败），两者互补不重复。
--
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行。
-- 顺序：res_play_record → res_channel_preset。
-- ============================================================================

-- 1) 播放 / 回放记录：写入点在统一入口（起播、停止、失败），不依赖 ZLM 回调，
--    因此"点播被拒/超时"也能留痕；观看时长以请求开始为起点，与观看者数量无关。
CREATE TABLE IF NOT EXISTS res_play_record
(
    id                  BIGINT        NOT NULL,
    tenant_id           BIGINT        NOT NULL DEFAULT 1,
    channel_row_id      BIGINT        NOT NULL COMMENT 'res_channel.id（同源组按实际取流行记录）',
    channel_code        VARCHAR(64)   NOT NULL COMMENT '取流时使用的通道编码',
    channel_name        VARCHAR(128)  NULL,
    source_type         VARCHAR(16)   NOT NULL DEFAULT 'NATIVE' COMMENT 'NATIVE / INBOUND',
    source_platform_id  BIGINT        NOT NULL DEFAULT 0,
    action              VARCHAR(16)   NOT NULL COMMENT 'PLAY=实时 / PLAYBACK=回放',
    stream_id           VARCHAR(160)  NULL COMMENT 'ZLM 流 ID（回放带时间段后缀）',
    actor_id            VARCHAR(64)   NULL COMMENT '操作者 id（用户/OpenAPI 客户端）',
    actor_name          VARCHAR(64)   NULL,
    client_ip           VARCHAR(64)   NULL,
    result              VARCHAR(16)   NOT NULL COMMENT 'SUCCESS / FAILED',
    fail_reason         VARCHAR(256)  NULL,
    begin_time          DATETIME      NOT NULL COMMENT '请求开始时刻',
    end_time            DATETIME      NULL COMMENT '停止/闭合时刻',
    duration_sec        INT           NULL COMMENT '秒；闭合时按 end-begin 估算',
    create_time         DATETIME      NOT NULL,
    update_time         DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_play_record_channel (channel_row_id, begin_time),
    KEY idx_play_record_actor (actor_id, begin_time),
    KEY idx_play_record_time (begin_time),
    KEY idx_play_record_stream (stream_id)
) COMMENT '播放/回放请求记录：一次点播一行，失败也留痕；观看时长与观看者数量无关';

-- 2) 通道预置位：编号 1-255 由设备侧定义，本表只存"给用户看的名称"与本地顺序；
--    INBOUND 通道同样适用（下发到来源平台），因此不复用 gb_ptz_preset（它按 gb_device_channel.id 关联）。
CREATE TABLE IF NOT EXISTS res_channel_preset
(
    id               BIGINT       NOT NULL,
    tenant_id        BIGINT       NOT NULL DEFAULT 1,
    channel_row_id   BIGINT       NOT NULL COMMENT 'res_channel.id',
    preset_index     INT          NOT NULL COMMENT '设备侧预置位编号 1-255',
    name             VARCHAR(64)  NOT NULL COMMENT '用户命名',
    create_time      DATETIME     NOT NULL,
    update_time      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_preset (channel_row_id, preset_index),
    KEY idx_channel_preset_channel (channel_row_id)
) COMMENT '通道预置位名称：编号下发设备，名称仅本地保存';

-- 3) 校验
SELECT 'res_play_record' AS tbl, COUNT(*) AS cnt FROM res_play_record
UNION ALL SELECT 'res_channel_preset', COUNT(*) FROM res_channel_preset;
