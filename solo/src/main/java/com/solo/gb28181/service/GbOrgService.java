package com.solo.gb28181.service;

import com.solo.gb28181.entity.GbOrg;
import com.solo.gb28181.request.GbOrgAssignRequest;
import com.solo.gb28181.request.GbOrgSaveRequest;
import com.solo.gb28181.response.GbOrgAssignResultVO;
import com.solo.gb28181.response.GbOrgTreeVO;

import java.util.List;

/**
 * 本地组织树（仅本地域资源归属）。
 *
 * <p>与面向上级的推送分组 {@code gb_platform_group} 无关：后者是某个上级视角下的
 * 215/216 目录，由"导入生成"从本地组织派生，派生后各自独立演化。</p>
 */
public interface GbOrgService {

    /** 组织树（含每个节点的直接设备数/通道数） */
    List<GbOrgTreeVO> listTree();

    /** 新建组织，返回带 id/path 的实体 */
    GbOrg create(GbOrgSaveRequest req);

    /** 编辑组织；换父时重写整棵子树的 path/level */
    void update(Long id, GbOrgSaveRequest req);

    /** 删除组织；有子组织或仍有设备/通道归属时拒绝 */
    void delete(Long id);

    /**
     * 批量调整设备/通道的组织归属。
     *
     * <p>orgId 为空或 -1 表示清空：设备 org_id 置空即"未归属"，通道置空即"跟随设备"。
     * 非 LOCAL 域的设备/通道（下级镜像、远程资源）只读，命中任意一条即整体拒绝。</p>
     */
    GbOrgAssignResultVO assign(GbOrgAssignRequest req);

    /**
     * 取组织 id 集合，用于设备/通道按组织过滤。
     *
     * @param orgId          组织 id，null 或非正数返回空集合（调用方应先判空）
     * @param includeSubtree 是否带上所有子孙组织；false 时只含自身
     * @return 组织 id 列表（展开子树时用物化路径前缀匹配，不递归查库）
     */
    List<Long> subtreeOrgIds(Long orgId, boolean includeSubtree);
}
