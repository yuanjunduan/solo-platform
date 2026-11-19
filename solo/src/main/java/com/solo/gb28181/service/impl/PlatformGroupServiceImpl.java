package com.solo.gb28181.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.solo.common.ServiceException;
import com.solo.common.utils.TreeUtils;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.entity.GbPlatformGroup;
import com.solo.gb28181.entity.GbPlatformShare;
import com.solo.gb28181.event.catalog.ShareChangedEvent;
import com.solo.gb28181.mapper.GbPlatformGroupMapper;
import com.solo.gb28181.mapper.GbPlatformShareMapper;
import com.solo.gb28181.request.GbPlatformGroupSaveRequest;
import com.solo.gb28181.response.GbPlatformGroupResponse;
import com.solo.gb28181.service.CatalogShareService;
import com.solo.gb28181.service.PlatformGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 推送树 CRUD；share 表由 CatalogShareService 管理。
 *
 * <p>2026-08：删分组时联动清理该组下所有 share，避免出现"分组删了但 share 还挂着 group_id"
 * 孤儿数据。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformGroupServiceImpl implements PlatformGroupService {

    private static final String LOG_TAG = "推送分组";

    /** 20 位纯数字国标码 */
    private static final String CODE_REGEX = "\\d{20}";
    /** 类型段：业务分组 */
    private static final int TYPE_BUSINESS_GROUP = 215;
    /** 类型段：虚拟组织 */
    private static final int TYPE_VIRTUAL_ORG = 216;

    private final GbPlatformGroupMapper groupMapper;
    private final GbPlatformShareMapper shareMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Gb28181Properties properties;
    private final CatalogShareService catalogShareService;

    @Override
    public List<GbPlatformGroupResponse> listGroupTree(Long platformId) {
        List<GbPlatformGroup> all = groupMapper.selectList(
                new LambdaQueryWrapper<GbPlatformGroup>()
                        .eq(GbPlatformGroup::getPlatformId, platformId)
                        .orderByAsc(GbPlatformGroup::getParentId, GbPlatformGroup::getId));
        if (all.isEmpty()) {
            return Collections.emptyList();
        }
        // Entity → VO（不挂 children，由 TreeUtils 在 VO 上构树）
        List<GbPlatformGroupResponse> nodes = all.stream().map(e -> {
            GbPlatformGroupResponse r = new GbPlatformGroupResponse();
            BeanUtils.copyProperties(e, r);
            return r;
        }).toList();
        // 用 TreeUtils 在 VO 上构树，根 = parentId == 0
        return TreeUtils.build(
                nodes,
                GbPlatformGroupResponse::getParentId,
                GbPlatformGroupResponse::getId,
                n -> n.getParentId() != null && n.getParentId() == 0L,
                GbPlatformGroupResponse::setChildren);
    }

    @Override
    @Transactional
    public void createGroup(Long platformId, GbPlatformGroupSaveRequest req) {
        validateCode(req.getCode());
        // 父分组（如果非 0）必须属于同一 platformId
        validateParent(platformId, req.getParentId(), null);
        validateHierarchy(req.getParentId(), req.getCode(), null);
        // code 平台内唯一
        Long same = groupMapper.selectCount(new LambdaQueryWrapper<GbPlatformGroup>()
                .eq(GbPlatformGroup::getPlatformId, platformId)
                .eq(GbPlatformGroup::getCode, req.getCode()));
        if (same != null && same > 0) {
            throw new ServiceException("国标编码在该平台下已存在");
        }
        GbPlatformGroup entity = new GbPlatformGroup();
        entity.setName(req.getName());
        entity.setCode(req.getCode());
        entity.setPlatformId(platformId);
        entity.setParentId(req.getParentId());
        groupMapper.insert(entity);
    }

    @Override
    @Transactional
    public void updateGroup(Long platformId, Long groupId, GbPlatformGroupSaveRequest req) {
        GbPlatformGroup existing = requireOwnedGroup(platformId, groupId);
        // 不能把自己设为自己的父级
        if (req.getParentId() != null && req.getParentId().equals(groupId)) {
            throw new ServiceException("不能把分组设为自己的父分组");
        }
        validateCode(req.getCode());
        // 父分组校验
        validateParent(platformId, req.getParentId(), groupId);
        validateHierarchy(req.getParentId(), req.getCode(), groupId);
        // code 唯一（除自己外）
        Long same = groupMapper.selectCount(new LambdaQueryWrapper<GbPlatformGroup>()
                .eq(GbPlatformGroup::getPlatformId, platformId)
                .eq(GbPlatformGroup::getCode, req.getCode())
                .ne(GbPlatformGroup::getId, groupId));
        if (same != null && same > 0) {
            throw new ServiceException("国标编码在该平台下已被其他分组占用");
        }
        // 事件报文要用变更前的 code / name / parentId 定位旧节点，先拍一份快照
        GbPlatformGroup before = new GbPlatformGroup();
        before.setId(existing.getId());
        before.setPlatformId(existing.getPlatformId());
        before.setCode(existing.getCode());
        before.setName(existing.getName());
        before.setParentId(existing.getParentId());
        existing.setName(req.getName());
        existing.setCode(req.getCode());
        existing.setParentId(req.getParentId());
        groupMapper.updateById(existing);
        // 改名发 UPDATE；换父/改码由服务层展开成「先 DEL 旧位置、再按新父链 ADD」的保序序列
        catalogShareService.notifyGroupUpdated(platformId, before, existing);
        eventPublisher.publishEvent(new ShareChangedEvent(this, platformId));
    }

    @Override
    @Transactional
    public void deleteGroup(Long platformId, Long groupId) {
        requireOwnedGroup(platformId, groupId);
        // 拒绝有子节点
        Long childCount = groupMapper.selectCount(new LambdaQueryWrapper<GbPlatformGroup>()
                .eq(GbPlatformGroup::getPlatformId, platformId)
                .eq(GbPlatformGroup::getParentId, groupId));
        if (childCount != null && childCount > 0) {
            throw new ServiceException("该分组下还有子分组，请先删除子分组");
        }
        // 删之前先把要撤销的节点取出来：组内通道在前、分组自身在后（严格叶子 → 根）。
        // 删完 share 和分组数据就构造不出 DEL 报文了。
        List<GbDeviceChannel> revoke = catalogShareService.loadGroupRevokeItems(platformId, groupId);
        // 联动清理该组下所有 share，避免 group_id 孤儿
        int unmounted = shareMapper.delete(
                new LambdaQueryWrapper<GbPlatformShare>()
                        .eq(GbPlatformShare::getPlatformId, platformId)
                        .eq(GbPlatformShare::getGroupId, groupId));
        groupMapper.deleteById(groupId);
        // 空分组也要撤：上级对全量快照只做 upsert，不会因为"快照里没有"而删除节点
        catalogShareService.sendRevokeEvents(platformId, revoke);
        log.info("[{}] 删分组 platformId={} groupId={} 联动清理挂载={} 撤销节点={}（通道 {} + 分组 1）",
                LOG_TAG, platformId, groupId, unmounted, revoke.size(), Math.max(0, revoke.size() - 1));
        eventPublisher.publishEvent(new ShareChangedEvent(this, platformId));
    }

    @Override
    public GbPlatformGroup requireOwnedGroup(Long platformId, Long groupId) {
        GbPlatformGroup g = groupMapper.selectById(groupId);
        if (g == null) {
            throw new ServiceException("分组不存在");
        }
        if (!platformId.equals(g.getPlatformId())) {
            throw new ServiceException("分组不属于该上级平台");
        }
        return g;
    }

    /**
     * 校验分组国标码：20 位数字、类型段为 215/216、前 10 位必须是本平台域。
     *
     * <p>前 10 位是中心编码 + 行业编码，属于平台自身域；上级按编码归属判断节点是否可信，
     * 前缀不对会被当成其它域的组织节点而丢弃，导致挂在这些节点下的通道“父组织错位”。</p>
     */
    private void validateCode(String code) {
        if (code == null || !code.matches(CODE_REGEX)) {
            throw new ServiceException("国标编码必须为 20 位数字");
        }
        int type = typeCode(code);
        if (type != TYPE_BUSINESS_GROUP && type != TYPE_VIRTUAL_ORG) {
            throw new ServiceException("国标编码第 11-13 位必须是 215（业务分组）或 216（虚拟组织）");
        }
        String domain = properties.getDomain();
        if (domain != null && !domain.isBlank() && !code.startsWith(domain)) {
            throw new ServiceException("国标编码前 " + domain.length() + " 位必须是本平台域 " + domain
                    + "，当前为 " + code.substring(0, Math.min(domain.length(), code.length())));
        }
    }

    /**
     * 层级校验（GB/T 28181-2016 附录 O）：
     * <ul>
     *   <li>215 业务分组只能是根节点（ParentID 由报文层回填为系统 ID），不能挂任何父分组；</li>
     *   <li>216 虚拟组织必须挂在 215 或 216 之下；</li>
     *   <li>父链不能成环（除自环外还要拦住 A→B→A 这类间接环）。</li>
     * </ul>
     */
    private void validateHierarchy(Long parentId, String code, Long selfId) {
        long pid = parentId == null ? 0L : parentId;
        int type = typeCode(code);
        if (type == TYPE_BUSINESS_GROUP) {
            if (pid != 0L) {
                throw new ServiceException("业务分组（215）只能建在根节点下，下级分组请使用虚拟组织（216）");
            }
            if (selfId != null) {
                Long childCount = groupMapper.selectCount(new LambdaQueryWrapper<GbPlatformGroup>()
                        .eq(GbPlatformGroup::getParentId, selfId));
                if (childCount != null && childCount > 0) {
                    throw new ServiceException("该分组下还有子分组，不能改为业务分组（215）");
                }
            }
            return;
        }
        if (pid == 0L) {
            throw new ServiceException("虚拟组织（216）必须挂在业务分组或上级虚拟组织下");
        }
        Long cursor = pid;
        int guard = 0;
        while (cursor != null && cursor != 0L && guard++ < 64) {
            if (selfId != null && cursor.equals(selfId)) {
                throw new ServiceException("父分组不能是自身或其下级分组");
            }
            GbPlatformGroup p = groupMapper.selectById(cursor);
            if (p == null) {
                break;
            }
            cursor = p.getParentId();
        }
    }

    /** 取 20 位国标码的类型段（第 11-13 位）；调用方已保证是 20 位数字 */
    private static int typeCode(String code) {
        return Integer.parseInt(code.substring(10, 13));
    }

    /**
     * 校验父分组（如果非 0）必须存在 + 属于同一 platformId
     */
    private void validateParent(Long platformId, Long parentId, Long excludeGroupId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        GbPlatformGroup parent = groupMapper.selectById(parentId);
        if (parent == null) {
            throw new ServiceException("父分组不存在");
        }
        if (!platformId.equals(parent.getPlatformId())) {
            throw new ServiceException("父分组不属于该上级平台");
        }
        if (excludeGroupId != null && parentId.equals(excludeGroupId)) {
            throw new ServiceException("不能把分组设为自己的父分组");
        }
    }
}
