-- ============================================================
-- M5 状态跟随：out_share 记录"上次推给上级的在线状态"
--
-- 背景（6.6 功能范围）：上游通道离线时，上级必须收到 OFF，而不是等它
-- 主动来拉目录才发现。要判断"状态变了没有"，必须留一个上次推送值；
-- 只看 res_channel.online_status 无法区分"一直离线"与"刚刚离线"——
-- 前者不该每轮同步都推一次。
--
-- 本脚本可重复执行；全新安装直接跑 2026-11-24_share_core_pg.sql（已含该列）。
-- ============================================================

ALTER TABLE out_share
    ADD COLUMN IF NOT EXISTS ref_online_status VARCHAR(8) NULL;

COMMENT ON COLUMN out_share.ref_online_status IS '上次推给上级的在线状态 ON/OFF（6.6 状态跟随）';

-- 校验
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_name = 'out_share' AND column_name = 'ref_online_status';

-- 回滚：
--   ALTER TABLE out_share DROP COLUMN ref_online_status;
