package com.solo.domain.resource.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solo.api.resource.dto.ChannelListItem;
import com.solo.api.resource.dto.ChannelSaveRequest;
import com.solo.api.resource.dto.ResourcePageRequest;
import com.solo.domain.resource.model.ResChannel;

/**
 * 通道管理（本地通道）。
 *
 * <p>通道有两处必填归属：位置（org）与载体（device）。两者都校验存在性，
 * 且组织必须可写——把本地通道挂到外部镜像组织下会让"组织删除"永远无法完成。</p>
 */
public interface ChannelService {

    /** 分页查询（关键词 / 组织子树 / 所属设备 / 来源 / 状态） */
    Page<ResChannel> pageQuery(ResourcePageRequest req);

    /** 分页查询并附带设备名 / 组织名（列表页用，避免前端为每行再查一次） */
    Page<ChannelListItem> pageList(ResourcePageRequest req);

    /** 详情，不存在时报错 */
    ResChannel detail(Long id);

    /** 新建通道 */
    ResChannel create(ChannelSaveRequest req);

    /** 编辑通道；换组织或换设备时同步刷新 org_path 快照 */
    void update(Long id, ChannelSaveRequest req);

    /** 删除通道 */
    void delete(Long id);
}
