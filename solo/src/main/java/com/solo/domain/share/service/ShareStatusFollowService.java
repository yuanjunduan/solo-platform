package com.solo.domain.share.service;

/**
 * 在线状态跟随（6.6：「上游离线 → 上级收到 OFF」）。
 *
 * <p>上游目录同步只更新 {@code res_channel.online_status}，上级看不到这个变化——
 * 它要么等下一次主动拉目录（可能几十分钟后），要么永远不知道。状态跟随把
 * "本次同步里状态变了的共享通道"补成出站事件，与资源同事务落 outbox。</p>
 *
 * <p><b>为什么必须留"上次推给上级的值"</b>：只看当前状态无法区分"一直离线"与"刚刚离线"，
 * 前者每轮同步都推一次 OFF，既吵又把推送额度烧在没变化的事情上。</p>
 */
public interface ShareStatusFollowService {

    /**
     * 入库后调用：把本次同步中在线状态变化的共享通道写成 ON/OFF 事件。
     *
     * <p>首次见到某条共享（{@code ref_online_status} 为空）只落基线不发事件：
     * 那条通道的 ADD 事件里已经带了当时的 {@code <Status>}，再补一条是重复。</p>
     *
     * @return 实际写入的出站事件条数（被降级成 REFRESH 时算 1 条）
     */
    int afterMerge(Long sourcePlatformId);
}
