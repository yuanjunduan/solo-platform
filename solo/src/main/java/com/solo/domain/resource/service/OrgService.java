package com.solo.domain.resource.service;

import com.solo.api.resource.dto.OrgSaveRequest;
import com.solo.api.resource.dto.OrgTreeNode;
import com.solo.domain.resource.model.ResOrg;

import java.util.List;

/**
 * 组织管理（本地组织树的增删改查 + 换父）。
 *
 * <p>只服务 {@code NATIVE} 资源：所有写方法入口都过 {@code ReadOnlyGuard}，
 * 外部平台镜像进来的组织一律拒写（架构约束 C2）。</p>
 */
public interface OrgService {

    /**
     * 组织树（含每个节点的直接设备数 / 通道数）。
     *
     * @param sourceType 来源过滤，空 = 全部来源
     */
    List<OrgTreeNode> listTree(String sourceType);

    /** 新建组织，返回落库后的实体（含 id / path / level） */
    ResOrg create(OrgSaveRequest req);

    /** 编辑组织；换父时整棵子树重写 path / level，并刷新设备与通道的位置快照 */
    void update(Long id, OrgSaveRequest req);

    /** 删除组织；有子组织或仍有资源归属时拒绝 */
    void delete(Long id);
}
