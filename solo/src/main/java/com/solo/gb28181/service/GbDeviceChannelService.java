package com.solo.gb28181.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.request.GbChannelSubtreePageRequest;
import com.solo.gb28181.request.GbDeviceChannelPageRequest;
import com.solo.gb28181.response.GbChannelStatusCountVO;
import com.solo.gb28181.response.GbChannelTreeNodeVO;
import com.solo.gb28181.response.GbChannelTreeLevelVO;
import com.solo.gb28181.response.GbDeviceChannelResponse;

import java.util.List;

/**
 * <p>简单按字段查询直接走 {@code lambdaQuery()}，本接口只保留"业务动作"。</p>
 */
public interface GbDeviceChannelService extends IService<GbDeviceChannel> {

    Page<GbDeviceChannelResponse> pageQuery(GbDeviceChannelPageRequest request);

    List<GbChannelStatusCountVO> statusCount();

    /**
     * 按设备组装目录树：分组为容器（children 挂下级分组/通道），通道为叶子。
     *
     * @param deviceId 设备国标 ID
     * @return 根节点列表（根分组 + 未挂分组的通道）
     */
    List<GbChannelTreeNodeVO> treeByDevice(String deviceId);

    /**
     * 重建某设备的组织路径 tree_path（分组与通道都写）。目录同步 final flush 成功后调用，
     * 也可在补数据/手动改层级后调用；事务内完成，避免重建期间读到半途的空路径。
     *
     * @param deviceId 设备国标 ID
     */
    void rebuildTreePath(String deviceId);

    /**
     * 目录树懒加载一层：当前节点统计 + 直接下级（分组带子树通道数、是否可展开）。
     * 一次请求只查一层，百万通道也不会把整棵树拉进内存。
     *
     * @param deviceId 设备国标 ID
     * @param orgCode  组织国标码；空/ null 表示设备根
     */
    GbChannelTreeLevelVO treeChildren(String deviceId, String orgCode);

    /**
     * 选中组织下的通道分页：includeSubtree 控制是否包含子组织下的通道。
     */
    Page<GbDeviceChannelResponse> subtreePage(GbChannelSubtreePageRequest request);

    void createByManual(GbDeviceChannel channel);

    void updateByManual(GbDeviceChannel channel);

    void deleteById(Long id);

    void saveOrUpdateBatch(List<GbDeviceChannel> channels);

    void deleteByDeviceId(String deviceId);

    void deleteBatch(List<Long> ids);

}
