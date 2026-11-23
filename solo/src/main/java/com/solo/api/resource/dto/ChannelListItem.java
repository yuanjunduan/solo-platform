package com.solo.api.resource.dto;

import com.solo.domain.resource.model.ResChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 通道列表行：通道本体 + 两个展示用的名字。
 *
 * <p>为什么要带名字：通道表只存 {@code org_id} / {@code device_id}，直接丢给前端就是两串雪花 id；
 * 让前端为每行再查一次设备/组织是 N+1。这里在分页之后按本页用到的 id 各批量查一次，
 * 把名字附在行上——一页 50 条最多两次 IN 查询。</p>
 */
@Schema(description = "通道列表行")
@Data
public class ChannelListItem {

    private ResChannel channel;

    @Schema(description = "所属设备名称（设备已被删时为空）")
    private String deviceName;

    @Schema(description = "所属组织名称（本地别名优先）")
    private String orgName;

    @Schema(description = "同源组成员数（含本行；1 = 未关联）")
    private Long sourceCount;

    @Schema(description = "所在同源组的组主 id（未关联时为自身 id）")
    private Long sourceGroupLeaderId;
}
