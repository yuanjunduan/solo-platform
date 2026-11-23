package com.solo.domain.share.service;

import com.solo.domain.share.support.SharePreview;

import java.util.List;

/**
 * 共享编排（3.9.1 / 3.9.2 / 4.5.1）。
 *
 * <p>本接口只解决"共享声明怎么变"，不解决"报文怎么发"：每个写方法都在自己的事务里
 * 改 {@code out_group} / {@code out_share} / {@code out_code_map}，并把变更交给
 * {@link ShareOutboxWriter} 同事务落进 {@code outbox}（C15）。投递在
 * {@code publish} 模块（S5），撤销后的墓碑也留到投递成功才置（C12 第 4 步）。</p>
 *
 * <p><b>两类行，一套语义</b>：</p>
 * <ul>
 *   <li>规则行（{@code scope_mode=SUBTREE, ref_type=ORG}）：声明"这个组织含子树"，
 *       不展开；新通道由 {@link #rebuildDerived} 派生。</li>
 *   <li>资源行（{@code ref_type=CHANNEL}）：人工勾选（{@code derived=0}）或派生（{@code derived=1}）。
 *       剔除只把 {@code enabled} 置 0，<b>不删行、也不新增第二条</b>，优先级高于规则。</li>
 * </ul>
 *
 * <p>所有方法都对"重复执行"友好：前端一次提交失败重试、两个管理员先后点同一个按钮，
 * 都不该产生第二份共享或多烧一个暴露码。</p>
 */
public interface ShareComposeService {

    /**
     * 一键生成：按来源组织树镜像出该上级的虚拟组织树（4.5.1 ②）。
     *
     * <p>幂等键是 {@code out_group.source_org_id}：同一个来源组织反复生成只更新名称与位置，
     * 不新建节点（否则上级会看到一堆同名分组）。节点码一经分配不随移动变化（C16）。</p>
     *
     * @param sourceOrgId   镜像的根组织（含整棵子树）
     * @param mountGroupId  新树挂在哪个已有虚拟组织下；{@code null} = 该上级的根
     */
    GroupTreeResult generateGroupTree(Long platformId, Long sourceOrgId, Long mountGroupId);

    /**
     * 新增 / 更新一条子树规则，并立即派生一次。
     *
     * @param groupId 规则派生的资源默认挂到哪个虚拟组织；{@code null} = 根
     */
    DeriveResult addOrgRule(Long platformId, Long orgId, Long groupId);

    /** 人工勾选通道（覆盖派生：同一条通道被勾选后成为显式行） */
    DeriveResult addChannels(Long platformId, List<Long> channelIds, Long groupId);

    /** 撤销子树规则：规则行置死，派生行随之撤销（规则是声明，撤掉就等于整片不可见） */
    DeriveResult removeOrgRule(Long platformId, Long orgId, String operator);

    /** 共享设备 = 共享其全部通道（3.9.1 只对外暴露通道，设备节点由通道的载体推导） */
    DeriveResult addDevices(Long platformId, List<Long> deviceIds, Long groupId);

    /**
     * 剔除：该通道对上级不可见，但保留派生关系。
     * <p>已有行置 {@code enabled=0} 并推 DELETE；尚无行时插一条抑制行，避免"先剔除、后被规则派生成可见"。</p>
     */
    void excludeChannel(Long platformId, Long channelId, String operator);

    /** 取消剔除：显式行恢复可见；抑制行删除后交回规则重新判定 */
    void restoreChannel(Long platformId, Long channelId, String operator);

    /**
     * 撤销单条共享（3.9.5）。
     * <p>只置 {@code ref_alive=0} 与 {@code REVOKING} 并推 DELETE，<b>不删行</b>——
     * 删行会丢掉"这条通道曾共享给谁"，恢复要人工重配。墓碑留到投递成功后置。</p>
     */
    void revokeChannel(Long platformId, Long channelId, String operator);

    /**
     * 撤销整个上级的共享：全部置死 + 清树 + 发一条 REFRESH。
     * <p>不逐条发 DELETE：上级重拉一次目录就会对齐，逐条发在万级通道时是自己打自己（C18）。</p>
     */
    void revokePlatform(Long platformId, String operator);

    /** 由规则行 + 设备行重算派生通道（规则增删、组织移动、通道新入后调用） */
    DeriveResult rebuildDerived(Long platformId);

    /**
     * 提交前预览：算清"已共享 / 新增 / 恢复 / 在剔除清单 / 需重新编号 / 会被拒绝"。
     *
     * <p><b>纯计算、不落库、不分配编码</b>：分配暴露码会烧掉一个不再复用的序号，
     * 用户只是点开看一眼就白烧一批，几天后码段就乱了（C16）。</p>
     *
     * <p>展开口径与真正提交时一致（组织走 {@code org_path} 前缀、设备展开为其全部通道），
     * 否则会出现"预览 10 条、提交 12 条"——用户会以为系统偷偷多推了东西。</p>
     */
    SharePreview preview(Long platformId, List<Long> orgIds, List<Long> channelIds, List<Long> deviceIds);

    /** 一键生成的结果 */
    record GroupTreeResult(int created, int updated, int moved, int total) {
    }

    /** 派生重算的结果；{@code skipped} = 命中防环或编码冲突而未共享的条数 */
    record DeriveResult(int added, int updated, int restored, int removed, int skipped) {

        public static DeriveResult empty() {
            return new DeriveResult(0, 0, 0, 0, 0);
        }

        public boolean changed() {
            return added + updated + restored + removed > 0;
        }
    }
}
