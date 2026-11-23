package com.solo.gb28181.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.request.GbChannelSubtreePageRequest;
import com.solo.gb28181.request.GbDeviceChannelPageRequest;
import com.solo.gb28181.response.GbCatalogSourceVO;
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

    /**
     * 目录来源列表：来源 = 推过目录的端点编码（{@code gb_device_channel.device_id}），
     * 由目录数据自身聚合，供通道页作为第一维度选择器使用。
     */
    List<GbCatalogSourceVO> catalogSources();

    /**
     * 通道状态统计。
     *
     * @param deviceId       来源端点编码（语义正确，优先使用）
     * @param platformId     历史参数（等价于 deviceId，仅为兼容旧调用保留）
     * @param orgId          组织 id；null=不按组织过滤，-1=只看未归属，正数=按有效组织（自身优先，否则继承设备）
     * @param domain         资源域 LOCAL / SUBORDINATE / REMOTE；null=不限（带 orgId 时后端强制锁定 LOCAL）
     * @param includeSubtree 带 orgId 时是否含子孙组织
     */
    List<GbChannelStatusCountVO> statusCount(String deviceId, String platformId,
                                             Long orgId, String domain, boolean includeSubtree,
                                             String pathPrefix);

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
