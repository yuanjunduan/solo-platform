package com.solo.gb28181.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 左树一层数据。
 *
 * <p>{@code parentKey} 为 root；{@code hasMore=true} 表示本层被 limit 截断，
 * 前端应提示"仅显示前 N 项，请用关键字收窄"，而不是静默丢弃。</p>
 */
@Schema(description = "目录左树一层数据")
@Data
public class GbCatalogNavVO {

    @Schema(description = "本次查询的资源域：LOCAL / SUBORDINATE / REMOTE / ALL")
    private String domain;

    @Schema(description = "本次展开的节点 key；根层为 root")
    private String parentKey;

    @Schema(description = "下级节点（已按排序规则排好，直接渲染即可）")
    private List<GbCatalogNavNodeVO> children = new ArrayList<>();

    @Schema(description = "本层是否因 limit 被截断")
    private boolean hasMore;

    @Schema(description = "生效的单层上限")
    private int limit;
}
