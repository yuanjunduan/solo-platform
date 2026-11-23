package com.solo.gb28181.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 左树懒加载请求：一次只取一层。
 *
 * <p>{@code parentKey} 为空表示取根层；非空时由节点 key 的前缀决定展开哪个数据源：
 * {@code org:{id}} 展开本地子组织，{@code plat:{端点码}} 展开下级平台的顶层分组，
 * {@code grp:{端点码}:{组织码}} 展开镜像分组。key 自带数据源信息，故不再单传 platformId。</p>
 */
@Schema(description = "目录左树懒加载请求")
@Data
public class GbCatalogNavRequest {

    @Schema(description = "资源域：LOCAL / SUBORDINATE / REMOTE / ALL；空=ALL")
    private String domain = "ALL";

    @Schema(description = "要展开的节点 key；空=根层")
    private String parentKey;

    @Schema(description = "名称/编码关键字，只过滤当前层（命中父节点不代表子节点也在）")
    private String keyword;

    @Schema(description = "单层返回上限，默认 200，最大 500")
    private Integer limit = 200;
}
