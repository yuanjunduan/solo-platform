package com.solo.domain.share.service;

import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResDevice;
import com.solo.gb28181.entity.GbDeviceChannel;

import java.util.List;

/**
 * 上级视角的目录组装（3.9.1 的第 ② 段）。
 *
 * <p><b>唯一数据源是 {@code out_group + out_share + out_code_map}</b>：上级看到的
 * {@code DeviceID / ParentID} 全部来自这三张表，本地组织树只是"来源"，不能直接当报文用——
 * 同一批通道共享给两个上级时，两棵树可以挂法不同、码也可以不同。</p>
 *
 * <p>这个服务的三个调用方必须拿到同一份视图，否则会出现"上级查目录看到的和推送看到的不一致"：</p>
 * <ol>
 *   <li>上级 {@code Query/Catalog} 应答；</li>
 *   <li>REFRESH 降级与对账补推；</li>
 *   <li>上级点播授权（INVITE 反查）。</li>
 * </ol>
 */
public interface ShareCatalogService {

    /**
     * 全量目录快照：分组（parental=1）在前、通道（parental=0）在后。
     *
     * <p>只包含"有通道落位"的分组及其祖先：空分组推上去只会在上级留下空目录节点。
     * 没有任何可见通道时返回空集合（上级应答会回一片 {@code SumNum=0}）。</p>
     */
    List<GbDeviceChannel> loadCatalogItems(Long platformId);

    /**
     * 对某上级可见的通道（含暴露码与资源主键），供点播授权与对账使用。
     *
     * <p>只取 {@code enabled=1 AND ref_alive=1}：撤销后、以及 DELETE 推送成功前，
     * 这些通道都必须被拒绝点播（C12 第 3 条规则）。</p>
     */
    List<GbDeviceChannel> loadExposedChannels(Long platformId);

    /**
     * 上级 INVITE / DeviceControl / DeviceStatus 反查：上级编码 → 可见的 {@code res_channel} 行；
     * 查不到返回 {@code null}。
     *
     * <p><b>纯查询、不猜</b>：反查链上任何一环缺失都返回 null，由调用方拒绝本次请求。
     * 猜一个"最像的通道"会把画面给错人。</p>
     *
     * <p>返回的是资源模型行而不是报文 Item：点播要看 {@code source_type} 决定走本地点播还是
     * 入站通道点播，控制/状态要看 {@code online_status}，报文里的 DeviceID 由各入口自己拼。</p>
     */
    ExposedChannel resolveForPlay(Long platformId, String upstreamCode);

    /** 反查结果：资源行 + 所属设备（设备缺失时调用方只能看到通道，不能取流） */
    record ExposedChannel(ResChannel channel, ResDevice device, String exposedCode) {
    }
}
