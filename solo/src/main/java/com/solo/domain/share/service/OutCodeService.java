package com.solo.domain.share.service;

import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.share.model.OutCodeMap;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 暴露码分配与逆向解析（3.9.3 / C16 / S4）。
 *
 * <p>规则只有三条，但每一条都不能省：</p>
 * <ol>
 *   <li><b>本地透传、入站重编号</b>：本地资源码已在本平台域下，透传即可；
 *       入站资源属于来源平台域，必须换成 {@code 域10+类型3+序号7}。</li>
 *   <li><b>编码一经分配不随资源变化</b>：改名、换组、换挂载位置都不重新分配。</li>
 *   <li><b>序号平台内单调、删除不复用</b>：墓碑行不删，同一资源恢复时复用自己的墓碑码。</li>
 * </ol>
 *
 * <p>反向解析是上级点播的入口，必须是纯查询：查不到就拒绝，不猜。</p>
 */
public interface OutCodeService {

    /** 本平台 SIP 域（10 位），来自 {@code gb28181.domain}；未配置直接抛错 */
    String localDomain();

    /**
     * 分配一个虚拟组织节点码（215/216）。组织节点没有"原始码"，每次都新建。
     *
     * @param nativeKey 节点自身的稳定标识（调用方先用雪花 id 占位），用于唯一键
     */
    String ensureGroupCode(Long platformId, String groupType, String nativeKey);

    /** 单条通道的暴露码（内部会锁平台行；批量场景请用 {@link #ensureChannelCodes}） */
    String ensureChannelCode(Long platformId, ResChannel channel);

    /** 批量：一次锁平台行、一次查已有映射，返回 {@code channel.id → exposedCode} */
    Map<Long, String> ensureChannelCodes(Long platformId, List<ResChannel> channels);

    /** 资源消失：置 TOMBSTONE（**推送成功后**才调，见 C12 第 4 步） */
    void tombstone(Long platformId, String refType, String nativeCode);

    /**
     * 只查不分配：该资源在指定上级已分配的暴露码，没有返回 {@code null}。
     * <p>撤销 / 剔除 / 派生核对时都要拿到"上级看到的那个码"，但那几个场景<b>绝不能顺手分配</b>——
     * 分配会白烧一个序号，而序号删除不复用。</p>
     */
    String exposedCodeOf(Long platformId, String refType, String nativeCode);

    /**
     * 批量只查不分配：{@code nativeCode → exposedCode}。
     * <p>派生重算要核对上千条通道的码，逐条查会变成 N+1；查不到的<b>不在返回值里</b>，
     * 调用方据此决定"这条要不要真的分配"。</p>
     */
    Map<String, String> exposedCodesOf(Long platformId, String refType, Collection<String> nativeCodes);

    /** 资源恢复：墓碑置回 ACTIVE 并复用原码 */
    void restore(Long platformId, String refType, String nativeCode);

    /** 上级 INVITE 反查：暴露码 → 映射行（查不到返回 null，调用方必须拒绝而不是猜） */
    OutCodeMap resolve(Long platformId, String exposedCode);
}
