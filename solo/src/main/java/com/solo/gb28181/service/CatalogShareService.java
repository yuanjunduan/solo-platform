package com.solo.gb28181.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.entity.GbPlatformGroup;
import com.solo.gb28181.request.GbPlatformShareSaveRequest;
import com.solo.gb28181.response.GbPlatformSharedResponse;
import com.solo.gb28181.response.GbShareTreeNodeResponse;

import java.util.List;

/**
 * <p>ADR-023：本平台作为下级向上级共享通道的编排与持久化。S6.b 的 Catalog 查询响应 / 变更 Notify 以此处
 * 保存的共享关系为数据源。</p>
 *
 * <p>{@code gb_platform_share} <b>只存通道</b>（{@code gb_device_channel} 中 parental=0 的行）。
 * 推送给上级的目录 = 推送分组（{@code gb_platform_group}，215/216）+ 挂载通道（132）。</p>
 *
 * <p>share 与推送分组（{@code gb_platform_group}）关联：UI 强制先在弹窗左侧选中分组才能挂载。
 * 同一通道在同一平台下只能挂载一次（{@code uk_platform_resource}），换组要先卸载旧组。
 * Catalog 响应 / INVITE 反查走全量（不分组）路径。</p>
 *
 * <p>撞码检测是<b>平台级</b>的：上级看到扁平 DeviceID 空间，跨组同码也会让上级 INVITE 认错通道，
 * 故撞码集合按 {@code platform_id} 全量判定（不限定 group_id）。</p>
 */
public interface CatalogShareService {

    List<GbShareTreeNodeResponse> shareTree();

    GbPlatformSharedResponse getShared(Long parentPlatformId);

    @Deprecated
    void saveShared(Long parentPlatformId, GbPlatformShareSaveRequest request);

    List<GbDeviceChannel> loadSharedCatalogItems(Long parentPlatformId);

    List<GbDeviceChannel> loadSharedChannels(Long parentPlatformId);

    IPage<GbDeviceChannel> pageSharedByGroup(Long parentPlatformId, Long groupId, Page<GbDeviceChannel> page, String keyword);

    IPage<GbDeviceChannel> pageUnmountedByGroup(Long parentPlatformId, Long groupId, Page<GbDeviceChannel> page, String keyword);

    int addShares(Long parentPlatformId, Long groupId, List<Long> resourceIds);

    boolean removeShare(Long parentPlatformId, Long groupId, Long resourceId);

    int clearShares(Long parentPlatformId, Long groupId);

    int addAllUnmounted(Long parentPlatformId, Long groupId);

    int clearAllSharesByPlatform(Long parentPlatformId);

    /**
     * 构造「删分组」要撤销的节点序列：组内挂载通道（在前）+ 分组自身（最后）。
     *
     * <p><b>必须在删 share / 删分组之前调用</b>——删完就构造不出 DEL 报文了。
     * 返回顺序即发送顺序，保证「叶子 → 根」：先删父会让子节点变孤儿，
     * 部分上级会直接忽略后续对孤儿节点的删除。</p>
     */
    List<GbDeviceChannel> loadGroupRevokeItems(Long parentPlatformId, Long groupId);

    /**
     * 按入参顺序逐条下发撤销事件（{@code Event=DEL}）；平台不存在或未启用时只告警不发送。
     */
    void sendRevokeEvents(Long parentPlatformId, List<GbDeviceChannel> items);

    /**
     * 分组变更事件：
     * <ul>
     *   <li>仅改名 → 一包 {@code Event=UPDATE}（上级按 DeviceID 定位节点，只改 Name）；</li>
     *   <li>换父 / 改码 → 先把旧位置摘掉（改码时连同旧 code 下的子分组与通道，叶子→根），
     *       再按新父链 ADD 回来（根→叶），最后发自身及其子树。</li>
     * </ul>
     *
     * @param before 变更前的快照（code / name / parentId 必须是旧值）
     * @param after  变更后的实体
     */
    void notifyGroupUpdated(Long parentPlatformId, GbPlatformGroup before, GbPlatformGroup after);

    void pushCatalog(Long parentPlatformId);
}
