package com.solo.domain.resource.service;

import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResDevice;
import com.solo.domain.resource.model.ResOrg;

/**
 * 资源写入的唯一入口（业务键原地 UPDATE），给同步链路用。
 *
 * <p><b>为什么必须是"原地 UPDATE"而不是"删了重建"</b>：{@code id} 会被共享关系
 * （{@code out_share}）与暴露码（{@code out_code_map}）引用。一旦删了重建，
 * id 变了，对上级的映射全部失联——表现为"上级那边看到一堆新通道，旧的还在"（C3 / 3.6.6）。</p>
 *
 * <p><b>本接口只做一件事</b>：按 {@code (source_type, source_platform_id, code)} 判存在，
 * 命中就更新同一行、否则插入。状态流转（ACTIVE → STALE → REMOVED → 复活）与
 * "要不要清空某列"由调用方决定，本接口不改动调用方没给的字段。</p>
 */
public interface ResourceUpsertService {

    /** 按业务键写入组织，返回行 id（新建或命中的同一个 id） */
    Long upsertOrg(ResOrg incoming);

    /** 按业务键写入设备，返回行 id */
    Long upsertDevice(ResDevice incoming);

    /** 按业务键写入通道，返回行 id */
    Long upsertChannel(ResChannel incoming);
}
