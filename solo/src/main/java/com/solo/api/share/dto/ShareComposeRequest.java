package com.solo.api.share.dto;

import com.solo.api.resource.dto.ResourcePageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 共享编排写请求（4.5.1 一屏三段）。
 *
 * <p>统一放在一个容器里而不是一文件一 DTO：这些结构只在编排页出现、字段都很少，
 * 拆成七个文件后反而看不出"它们服务于同一屏"。</p>
 */
@Schema(description = "共享编排请求")
public class ShareComposeRequest {

    /**
     * 编排页"选资源"的通道分页：资源过滤条件 + 目标上级。
     * <p>继承 {@link ResourcePageRequest} 是为了和资源页共用同一套筛选语义（关键词/组织子树/来源/状态），
     * 不另造一套参数名——两套参数名迟早出现"资源页能筛、编排页筛不了"。</p>
     */
    @Data
    @Schema(description = "编排页通道分页")
    public static class ChannelQuery extends ResourcePageRequest {

        @Schema(description = "目标上级平台，用于回填已共享/已剔除标记")
        private Long platformId;
    }

    @Data
    @Schema(description = "一键生成虚拟组织树")
    public static class GroupGenerate {

        @Schema(description = "目标上级平台（gb_parent_platform.id）")
        private Long platformId;

        @Schema(description = "镜像的来源组织（含整棵子树）")
        private Long sourceOrgId;

        @Schema(description = "挂到哪个已有虚拟组织下；空 = 该上级的根")
        private Long mountGroupId;
    }

    @Data
    @Schema(description = "子树规则：该组织含子树")
    public static class Rule {

        private Long platformId;

        @Schema(description = "来源组织 id（res_org.id）")
        private Long orgId;

        @Schema(description = "派生资源挂到哪个虚拟组织；空 = 根")
        private Long groupId;
    }

    @Data
    @Schema(description = "批量共享通道")
    public static class ChannelBatch {

        private Long platformId;

        @Schema(description = "通道 id 列表（res_channel.id）")
        private List<Long> channelIds;

        @Schema(description = "挂到哪个虚拟组织；空 = 根")
        private Long groupId;
    }

    @Data
    @Schema(description = "批量共享设备（等价于共享它的全部通道）")
    public static class DeviceBatch {

        private Long platformId;

        @Schema(description = "设备 id 列表（res_device.id）")
        private List<Long> deviceIds;

        @Schema(description = "挂到哪个虚拟组织；空 = 根")
        private Long groupId;
    }

    @Data
    @Schema(description = "单条通道操作：剔除 / 恢复 / 撤销")
    public static class ChannelOp {

        private Long platformId;

        @Schema(description = "通道 id（res_channel.id）")
        private Long channelId;
    }

    @Data
    @Schema(description = "编排预览：本次提交会发生什么")
    public static class Preview {

        private Long platformId;

        @Schema(description = "勾选的子树组织（只用于估算条数，不落库）")
        private List<Long> orgIds;

        @Schema(description = "勾选的显式通道")
        private List<Long> channelIds;

        @Schema(description = "勾选的设备（展开为通道后估算）")
        private List<Long> deviceIds;
    }
}
