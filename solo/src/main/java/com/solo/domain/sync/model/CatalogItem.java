package com.solo.domain.sync.model;

import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 目录项（3.7.2）：适配器与领域层之间唯一的传输结构。
 *
 * <p>领域层只认它，永远看不到 {@code SipMessage} / {@code ONVIF Device} 这类协议对象
 * （原则 8：新增厂商只加适配器，资源/共享/推送/播放零改动）。</p>
 *
 * <p>字段分三组：<b>业务键与位置</b>（code/parentCode/type）、<b>目录内容</b>（name/status/…）、
 * <b>全局身份</b>（origin* / codeChain / hopCount，入站尽力解析，解析不出就留空）。</p>
 */
@Data
public class CatalogItem {

    /** 对端给的编码。入站不改码（3.7.4） */
    private String code;

    /** 对端给的父编码。可能是组织码 / 分组码 / 设备码 / 平台域，合并时按 4 步回填解析 */
    private String parentCode;

    private CatalogItemType type;

    private String name;

    /** 上游目录项状态：ON / OFF（与本地 status 四态不是一回事） */
    private String status;

    /** 通道的载体设备编码；GB28181 下级平台目录不区分设备层时由服务层补平台载体码 */
    private String deviceCode;

    /** MAIN / SUB / THIRD */
    private String streamType;

    /** PTZ/RECORD/AUDIO/TALK 位串 */
    private String capability;

    /** 取流线索（不含凭据） */
    private String mediaHint;

    private String deviceIp;

    private String vendor;

    private String model;

    /** 最上游平台 id；0=未知 */
    private Long originPlatformId;

    /** 最上游平台上的编码；空串=对端未提供 */
    private String originCode;

    /** 码链，从最上游到直接来源 */
    private List<CodeHop> codeChain;

    /**
     * 来源链（平台国标码逗号串，最上游 → 直接来源）。
     *
     * <p>与 {@link #codeChain} 的分工：{@code codeChain} 记"码在每一跳是什么"（展示用），
     * 本字段记"经过了哪些平台"（防环用）。存国标码而不是本地 id，因为对方平台不认识我们的库 id；
     * 上游没提供时只能填直接来源，链长 1（3.10.3 的降级口径）。</p>
     */
    private String originChain;

    /** 对端告知的跳数 */
    private Integer hopCount;

    /** 协议特有字段，落库前不进核心列 */
    private Map<String, String> attrs;

    public List<CodeHop> codeChainOrEmpty() {
        return codeChain == null ? Collections.emptyList() : codeChain;
    }

    public Map<String, String> attrsOrEmpty() {
        return attrs == null ? Collections.emptyMap() : attrs;
    }

    /** 暂存表 item_type */
    public String itemType() {
        return type.itemType();
    }

    public String groupType() {
        return type.groupType();
    }
}
