package com.solo.domain.resource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.solo.api.resource.dto.OrgSaveRequest;
import com.solo.api.resource.dto.OrgTreeNode;
import com.solo.common.ServiceException;
import com.solo.common.utils.TreeUtils;
import com.solo.domain.resource.model.ResOrg;
import com.solo.domain.resource.model.ResourceStatus;
import com.solo.domain.resource.model.SourceType;
import com.solo.domain.resource.service.OrgService;
import com.solo.domain.resource.support.ReadOnlyGuard;
import com.solo.domain.resource.support.ResourcePath;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.ResDeviceMapper;
import com.solo.infra.mapper.ResOrgMapper;
import com.solo.infra.mapper.projection.OrgRefCount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 组织管理实现。
 *
 * <p><b>为什么不在落库后回写 path</b>：{@code id} 是雪花号，可以在 INSERT 之前用
 * {@link IdWorker} 拿到，先算好 path 再一次 INSERT。少一次 UPDATE 就少一个
 * "插入了但路径是空串"的中间态窗口。</p>
 *
 * <p><b>换父只走三条 UPDATE</b>：组织子树重写 path → 层级平移 → 设备/通道的
 * org_path 快照重写。都按物化路径前缀圈定，不递归、不逐条、不把 id 列表塞进 IN。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgServiceImpl implements OrgService {

    private static final String LOG_TAG = "资源组织";
    private static final String ERR_NOT_FOUND = "组织不存在";
    /** 本地自建资源的来源平台固定为 0（platform 表里没有"本地"这条记录） */
    private static final Long NATIVE_PLATFORM_ID = 0L;

    private final ResOrgMapper orgMapper;
    private final ResDeviceMapper deviceMapper;
    private final ResChannelMapper channelMapper;
    private final ReadOnlyGuard readOnlyGuard;

    @Override
    public List<OrgTreeNode> listTree(String sourceType) {
        LambdaQueryWrapper<ResOrg> w = new LambdaQueryWrapper<ResOrg>()
                .ne(ResOrg::getStatus, ResourceStatus.REMOVED)
                .orderByAsc(ResOrg::getLevel)
                .orderByAsc(ResOrg::getSortNo)
                .orderByAsc(ResOrg::getId);
        if (sourceType != null && !sourceType.isBlank()) {
            w.eq(ResOrg::getSourceType, sourceType.trim());
        }
        List<ResOrg> all = orgMapper.selectList(w);
        if (all.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Long> deviceCounts = toCountMap(deviceMapper.countGroupByOrg());
        Map<Long, Long> channelCounts = toCountMap(channelMapper.countGroupByOrg());

        List<OrgTreeNode> nodes = all.stream().map(e -> {
            OrgTreeNode node = new OrgTreeNode();
            node.setId(e.getId());
            node.setParentId(e.getParentId());
            node.setCode(e.getCode());
            node.setName(e.getName());
            node.setLocalName(e.getLocalName());
            node.setPath(e.getPath());
            node.setLevel(e.getLevel());
            node.setSortNo(e.getSortNo());
            node.setSourceType(e.getSourceType());
            node.setStatus(e.getStatus());
            node.setWritable(readOnlyGuard.isWritable(e.getSourceType()));
            node.setDeviceCount(deviceCounts.getOrDefault(e.getId(), 0L));
            node.setChannelCount(channelCounts.getOrDefault(e.getId(), 0L));
            return node;
        }).toList();

        return TreeUtils.build(
                nodes,
                OrgTreeNode::getParentId,
                OrgTreeNode::getId,
                n -> n.getParentId() == null,
                OrgTreeNode::setChildren);
    }

    @Override
    @Transactional
    public ResOrg create(OrgSaveRequest req) {
        String code = req.getCode().trim();
        if (orgMapper.selectByBizKey(SourceType.NATIVE, NATIVE_PLATFORM_ID, code) != null) {
            throw new ServiceException(400, "组织编码已存在：" + code);
        }

        Long parentId = normalizeParentId(req.getParentId());
        ResOrg parent = parentId == null ? null : requireOrg(parentId);
        if (parent != null) {
            readOnlyGuard.requireWritable(parent.getSourceType(), "在它下面新建子组织");
        }

        String name = req.getName().trim();
        long sameName = orgMapper.countSiblingName(parentId, name, null);
        if (sameName > 0) {
            throw new ServiceException(400, "同一上级下已存在同名组织：" + name);
        }

        long id = IdWorker.getId();
        String path = parent == null
                ? ResourcePath.ofRoot(id)
                : ResourcePath.ofChild(parent.getPath(), id);

        ResOrg org = new ResOrg();
        org.setId(id);
        org.setCode(code);
        org.setName(name);
        org.setLocalName(trimToNull(req.getLocalName()));
        org.setParentId(parentId);
        org.setParentCode(parent == null ? null : parent.getCode());
        org.setPath(path);
        org.setLevel(ResourcePath.levelOf(path));
        org.setSourceType(SourceType.NATIVE);
        org.setSourcePlatformId(NATIVE_PLATFORM_ID);
        org.setOriginChain("");
        org.setHopCount(0);
        org.setStatus(ResourceStatus.ACTIVE);
        org.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        orgMapper.insert(org);

        log.info("[{}] 新建组织 id={} code={} name={} parentId={} path={}",
                LOG_TAG, id, code, name, parentId, path);
        return org;
    }

    @Override
    @Transactional
    public void update(Long id, OrgSaveRequest req) {
        ResOrg existing = requireOrg(id);
        readOnlyGuard.requireWritable(existing.getSourceType(), "修改组织");

        String code = req.getCode().trim();
        if (!code.equals(existing.getCode())) {
            // 编码是业务键的第三段，改它等于换一个资源——对上级的映射、对下级的引用会全部失联
            throw new ServiceException(400, "组织编码建立后不可修改");
        }

        Long oldParentId = existing.getParentId();
        Long newParentId = req.getParentId() == null ? oldParentId : normalizeParentId(req.getParentId());
        boolean moved = !Objects.equals(oldParentId, newParentId);

        ResOrg newParent = null;
        if (moved) {
            if (Objects.equals(newParentId, id)) {
                throw new ServiceException(400, "不能把组织设为自己的上级");
            }
            if (newParentId != null) {
                newParent = requireOrg(newParentId);
                readOnlyGuard.requireWritable(newParent.getSourceType(), "移动到它下面");
                if (ResourcePath.isUnder(newParent.getPath(), existing.getPath())) {
                    throw new ServiceException(400, "不能把组织移动到自己的下级组织里");
                }
            }
        }

        String name = req.getName().trim();
        long sameName = orgMapper.countSiblingName(newParentId, name, id);
        if (sameName > 0) {
            throw new ServiceException(400, "同一上级下已存在同名组织：" + name);
        }

        // 用 wrapper 显式 set：localName / parentId 需要支持"清空"，而 updateById 会跳过 null 字段
        orgMapper.update(null, new LambdaUpdateWrapper<ResOrg>()
                .eq(ResOrg::getId, id)
                .set(ResOrg::getName, name)
                .set(ResOrg::getLocalName, trimToNull(req.getLocalName()))
                .set(ResOrg::getSortNo, req.getSortNo() == null
                        ? (existing.getSortNo() == null ? 0 : existing.getSortNo())
                        : req.getSortNo())
                .set(ResOrg::getParentId, newParentId)
                .set(ResOrg::getParentCode, newParent == null ? null : newParent.getCode())
                .set(ResOrg::getUpdateTime, LocalDateTime.now()));

        if (moved) {
            moveSubtree(existing, existing.getPath(), nvlLevel(existing.getLevel()), newParent);
        }
        log.info("[{}] 编辑组织 id={} name={} moved={} newParentId={}",
                LOG_TAG, id, name, moved, newParentId);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ResOrg org = requireOrg(id);
        readOnlyGuard.requireWritable(org.getSourceType(), "删除组织");

        long children = orgMapper.countByParentId(id);
        if (children > 0) {
            throw new ServiceException(400, "该组织下还有 " + children + " 个子组织，请先移走或删除子组织");
        }
        long devices = deviceMapper.countByOrgId(id);
        if (devices > 0) {
            throw new ServiceException(400, "还有 " + devices + " 台设备归属该组织，请先把设备移出");
        }
        long channels = channelMapper.countByOrgId(id);
        if (channels > 0) {
            throw new ServiceException(400, "还有 " + channels + " 路通道归属该组织，请先把通道移出");
        }

        orgMapper.deleteById(id);
        log.info("[{}] 删除组织 id={} code={} name={}", LOG_TAG, id, org.getCode(), org.getName());
    }

    /**
     * 换父后重写整棵子树。
     *
     * <p>顺序不能换：先按旧前缀把 path 全换成新前缀（REPLACE 一次覆盖整棵子树，
     * 含自身），之后才能按新前缀圈定范围去平移 level。</p>
     */
    private void moveSubtree(ResOrg self, String oldPath, int oldLevel, ResOrg newParent) {
        if (oldPath == null || oldPath.isBlank()) {
            log.warn("[{}] 组织 id={} 缺少 path，跳过子树重写", LOG_TAG, self.getId());
            return;
        }
        String newPath = newParent == null
                ? ResourcePath.ofRoot(self.getId())
                : ResourcePath.ofChild(newParent.getPath(), self.getId());
        int delta = ResourcePath.levelOf(newPath) - oldLevel;
        LocalDateTime now = LocalDateTime.now();

        int orgRows = orgMapper.rewriteSubtreePath(
                self.getSourceType(), self.getSourcePlatformId(), oldPath, newPath, now);
        if (delta != 0) {
            orgMapper.shiftSubtreeLevel(
                    self.getSourceType(), self.getSourcePlatformId(), newPath, delta, now);
        }
        int deviceRows = deviceMapper.rewriteOrgPath(oldPath, newPath, now);
        int channelRows = channelMapper.rewriteOrgPath(oldPath, newPath, now);

        log.info("[{}] 移动组织 id={} {} -> {} delta={} 组织={} 设备={} 通道={}",
                LOG_TAG, self.getId(), oldPath, newPath, delta, orgRows, deviceRows, channelRows);
    }

    private ResOrg requireOrg(Long id) {
        ResOrg org = orgMapper.selectById(id);
        if (org == null) {
            throw new ServiceException(ERR_NOT_FOUND);
        }
        return org;
    }

    /** null / 0 / 负数统一视为根节点 */
    private static Long normalizeParentId(Long parentId) {
        return parentId == null || parentId <= 0 ? null : parentId;
    }

    private static int nvlLevel(Integer level) {
        return level == null ? 1 : level;
    }

    private static Map<Long, Long> toCountMap(List<OrgRefCount> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Long> map = new HashMap<>(rows.size());
        for (OrgRefCount row : rows) {
            if (row.getOrgId() != null) {
                map.put(row.getOrgId(), row.getTotal() == null ? 0L : row.getTotal());
            }
        }
        return map;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
