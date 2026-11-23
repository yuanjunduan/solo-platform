package com.solo.infra.mapper.projection;

import lombok.Data;

/**
 * 单个上级平台的共享计数（4.2 共享总览卡片）。
 *
 * <p>三个数各自回答一个问题：{@code channelCount} = 上级此刻应该看到多少通道；
 * {@code ruleCount} = 有多少条"含子树"声明在支撑它；{@code excludedCount} = 有多少条被人工剔除。
 * 剔除数不参与"入口通道数"，但必须显示出来——不然用户会以为自己少配了通道。</p>
 */
@Data
public class OutShareCount {

    /** 可见通道行（ref_type=CHANNEL, enabled=1, ref_alive=1） */
    private long channelCount;

    /** 生效子树规则行（ref_type=ORG, scope_mode=SUBTREE） */
    private long ruleCount;

    /** 被剔除的通道行（enabled=0） */
    private long excludedCount;
}
