package com.solo.gb28181.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.solo.common.ServiceException;
import com.solo.common.utils.TreeUtils;
import com.solo.gb28181.constant.GbDomain;
import com.solo.gb28181.entity.GbDevice;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.entity.GbOrg;
import com.solo.gb28181.mapper.GbDeviceChannelMapper;
import com.solo.gb28181.mapper.GbDeviceMapper;
import com.solo.gb28181.mapper.GbOrgMapper;
import com.solo.gb28181.request.GbOrgAssignRequest;
import com.solo.gb28181.request.GbOrgSaveRequest;
import com.solo.gb28181.response.GbOrgAssignResultVO;
import com.solo.gb28181.response.GbOrgCountVO;
import com.solo.gb28181.response.GbOrgTreeVO;
import com.solo.gb28181.service.GbOrgService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 本地组织树 CRUD。
 *
 * <p>path 用物化路径 {@code /根id/.../自身id/}：子树查询靠前缀 LIKE，换父时整棵子树
 * 重写 path/level。选择在 Java 层逐条重算而不是写方言 SQL（CONCAT/|| 不通用），
 * 组织规模是百级，代价可忽略。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbOrgServiceImpl implements GbOrgService {

    private static final String LOG_TAG = "本地组织";
    /** 预置根组织主键，不允许删除 */
    private static final long DEFAULT_ROOT_ID = 1L;
    private static final String CODE_REGEX = "\\d{20}";

    private final GbOrgMapper orgMapper;
    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;

    @Override
    public List<GbOrgTreeVO> listTree() {
        List<GbOrg> all = orgMapper.selectList(new LambdaQueryWrapper<GbOrg>()
                .orderByAsc(GbOrg::getParentId)
                .orderByAsc(GbOrg::getSort)
                .orderByAsc(GbOrg::getId));
        if (all.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Long> deviceCounts = toCountMap(orgMapper.selectDeviceCountByOrg());
        Map<Long, Long> channelCounts = toCountMap(orgMapper.selectChannelCountByOrg());

        List<GbOrgTreeVO> nodes = all.stream().map(e -> {
            GbOrgTreeVO vo = new GbOrgTreeVO();
            BeanUtils.copyProperties(e, vo);
            vo.setDirectDeviceCount(deviceCounts.getOrDefault(e.getId(), 0L));
            vo.setDirectChannelCount(channelCounts.getOrDefault(e.getId(), 0L));
            return vo;
        }).toList();

        return TreeUtils.build(
                nodes,
                GbOrgTreeVO::getParentId,
                GbOrgTreeVO::getId,
                n -> n.getParentId() == null || n.getParentId() == 0L,
                GbOrgTreeVO::setChildren);
    }

    @Override
    @Transactional
    public GbOrg create(GbOrgSaveRequest req) {
        String name = req.getName().trim();
        long parentId = req.getParentId() == null ? 0L : req.getParentId();
        GbOrg parent = parentId == 0L ? null : requireOrg(parentId);
        validateName(parentId, name, null);
        String code = trimToNull(req.getCode());
        validateCode(code, null);

        GbOrg org = new GbOrg();
        org.setParentId(parentId);
        org.setName(name);
        org.setCode(code);
        org.setSort(req.getSort() == null ? 0 : req.getSort());
        org.setRemark(req.getRemark());
        org.setLevel(parent == null ? 1 : nvlInt(parent.getLevel()) + 1);
        // path 依赖自身 id，只能先落库再回写
        org.setPath("");
        orgMapper.insert(org);

        org.setPath((parent == null ? "/" : parent.getPath()) + org.getId() + "/");
        orgMapper.updateById(org);
        log.info("[{}] 新建组织 id={} name={} parentId={} path={}",
                LOG_TAG, org.getId(), name, parentId, org.getPath());
        return org;
    }

    @Override
    @Transactional
    public void update(Long id, GbOrgSaveRequest req) {
        GbOrg existing = requireOrg(id);
        String name = req.getName().trim();
        long oldParentId = nvl(existing.getParentId());
        long newParentId = req.getParentId() == null ? oldParentId : req.getParentId();
        String code = trimToNull(req.getCode());
        boolean moved = newParentId != oldParentId;

        validateName(newParentId, name, id);
        validateCode(code, id);

        String oldPath = existing.getPath();
        int oldLevel = nvlInt(existing.getLevel());
        if (moved) {
            if (newParentId == id) {
                throw new ServiceException("不能把组织设为自己的父组织");
            }
            if (newParentId != 0L) {
                GbOrg newParent = requireOrg(newParentId);
                // 目标父节点落在自己子树里 → 会成环
                if (oldPath != null && !oldPath.isBlank()
                        && newParent.getPath() != null
                        && newParent.getPath().startsWith(oldPath)) {
                    throw new ServiceException("不能把组织移动到自己的下级组织里");
                }
            }
        }

        existing.setName(name);
        existing.setCode(code);
        existing.setParentId(newParentId);
        if (req.getSort() != null) {
            existing.setSort(req.getSort());
        }
        existing.setRemark(req.getRemark());
        orgMapper.updateById(existing);

        if (moved) {
            moveSubtree(existing, oldPath, oldLevel);
        }
    }

    @Override
    @Transactional
    public void delete(Long id) {
        GbOrg org = requireOrg(id);
        if (id == DEFAULT_ROOT_ID) {
            throw new ServiceException("默认组织是根节点，不能删除");
        }
        Long childCount = orgMapper.selectCount(
                new LambdaQueryWrapper<GbOrg>().eq(GbOrg::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new ServiceException("该组织下还有 " + childCount + " 个子组织，请先处理子组织");
        }
        Long deviceCount = deviceMapper.selectCount(
                new LambdaQueryWrapper<GbDevice>().eq(GbDevice::getOrgId, id));
        if (deviceCount != null && deviceCount > 0) {
            throw new ServiceException("还有 " + deviceCount + " 台设备归属该组织，请先把设备移出");
        }
        Long channelCount = channelMapper.selectCount(
                new LambdaQueryWrapper<GbDeviceChannel>().eq(GbDeviceChannel::getOrgId, id));
        if (channelCount != null && channelCount > 0) {
            throw new ServiceException("还有 " + channelCount + " 路通道单独归属该组织，请先把通道移出");
        }
        orgMapper.deleteById(org.getId());
        log.info("[{}] 删除组织 id={} name={}", LOG_TAG, id, org.getName());
    }

    @Override
    @Transactional
    public GbOrgAssignResultVO assign(GbOrgAssignRequest req) {
        List<Long> deviceIds = distinctIds(req.getDeviceIds());
        List<Long> channelIds = distinctIds(req.getChannelIds());
        if (deviceIds.isEmpty() && channelIds.isEmpty()) {
            throw new ServiceException("请选择要调整归属的设备或通道");
        }
        GbOrg targetOrg = null;
        Long target = req.getOrgId();
        if (target != null && target > 0) {
            targetOrg = requireOrg(target);
        } else {
            // null / 0 / -1 统一视为清空
            target = null;
        }

        int devices = 0;
        if (!deviceIds.isEmpty()) {
            devices = assignDevices(deviceIds, target);
        }
        int channels = 0;
        if (!channelIds.isEmpty()) {
            channels = assignChannels(channelIds, target);
        }
        log.info("[{}] 调整归属 orgId={} 设备={} 通道={}", LOG_TAG, target, devices, channels);

        GbOrgAssignResultVO vo = new GbOrgAssignResultVO();
        vo.setOrgName(targetOrg == null ? null : targetOrg.getName());
        vo.setDevices(devices);
        vo.setChannels(channels);
        return vo;
    }

    /** 设备归属：只允许本地域；orgId 为 null 即置空（未归属） */
    private int assignDevices(List<Long> ids, Long orgId) {
        List<GbDevice> devices = deviceMapper.selectList(new LambdaQueryWrapper<GbDevice>()
                .select(GbDevice::getId, GbDevice::getDomain)
                .in(GbDevice::getId, ids));
        if (devices.isEmpty()) {
            return 0;
        }
        long notLocal = devices.stream()
                .filter(d -> !GbDomain.LOCAL.equals(d.getDomain()))
                .count();
        if (notLocal > 0) {
            throw new ServiceException("有 " + notLocal
                    + " 台设备不属于本地域（下级/远程镜像只读），不能调整归属");
        }
        deviceMapper.update(null, new LambdaUpdateWrapper<GbDevice>()
                .in(GbDevice::getId, devices.stream().map(GbDevice::getId).toList())
                .set(GbDevice::getOrgId, orgId)
                .set(GbDevice::getUpdateTime, LocalDateTime.now()));
        return devices.size();
    }

    /** 通道归属：域随所属设备；orgId 为 null 即清空覆盖值，回落为继承设备 */
    private int assignChannels(List<Long> ids, Long orgId) {
        List<GbDeviceChannel> channels = channelMapper.selectList(new LambdaQueryWrapper<GbDeviceChannel>()
                .select(GbDeviceChannel::getId, GbDeviceChannel::getDeviceId)
                .in(GbDeviceChannel::getId, ids));
        if (channels.isEmpty()) {
            return 0;
        }
        Set<String> deviceIds = channels.stream()
                .map(GbDeviceChannel::getDeviceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!deviceIds.isEmpty()) {
            Long notLocal = deviceMapper.selectCount(new LambdaQueryWrapper<GbDevice>()
                    .in(GbDevice::getDeviceId, deviceIds)
                    .and(w -> w.isNull(GbDevice::getDomain)
                            .or()
                            .ne(GbDevice::getDomain, GbDomain.LOCAL)));
            if (notLocal != null && notLocal > 0) {
                throw new ServiceException("选中通道所在的 " + notLocal
                        + " 个来源不属于本地域（下级/远程镜像只读），不能调整归属");
            }
        }
        channelMapper.update(null, new LambdaUpdateWrapper<GbDeviceChannel>()
                .in(GbDeviceChannel::getId, channels.stream().map(GbDeviceChannel::getId).toList())
                .set(GbDeviceChannel::getOrgId, orgId)
                .set(GbDeviceChannel::getUpdateTime, LocalDateTime.now()));
        return channels.size();
    }

    private static List<Long> distinctIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    @Override
    public List<Long> subtreeOrgIds(Long orgId, boolean includeSubtree) {
        if (orgId == null || orgId <= 0) {
            return Collections.emptyList();
        }
        if (!includeSubtree) {
            return List.of(orgId);
        }
        GbOrg self = orgMapper.selectById(orgId);
        if (self == null || self.getPath() == null || self.getPath().isBlank()) {
            // 组织不存在/历史脏数据没有 path：退回单节点，避免把整表当子树
            return List.of(orgId);
        }
        // path 形如 /1/A/，前缀匹配天然含自身，且不会误命中 /1/AB/
        return orgMapper.selectList(new LambdaQueryWrapper<GbOrg>()
                        .select(GbOrg::getId)
                        .likeRight(GbOrg::getPath, self.getPath()))
                .stream().map(GbOrg::getId).toList();
    }

    /**
     * 换父后重写自身与整棵子树的 path/level。
     *
     * @param self     已更新 parentId 的实体
     * @param oldPath  移动前的自身 path（子树前缀）
     * @param oldLevel 移动前的自身层级
     */
    private void moveSubtree(GbOrg self, String oldPath, int oldLevel) {
        if (oldPath == null || oldPath.isBlank()) {
            return;
        }
        GbOrg parent = nvl(self.getParentId()) == 0L ? null : orgMapper.selectById(self.getParentId());
        String newPath = (parent == null ? "/" : parent.getPath()) + self.getId() + "/";
        int newLevel = parent == null ? 1 : nvlInt(parent.getLevel()) + 1;
        self.setPath(newPath);
        self.setLevel(newLevel);
        orgMapper.updateById(self);

        List<GbOrg> subtree = orgMapper.selectList(new LambdaQueryWrapper<GbOrg>()
                .likeRight(GbOrg::getPath, oldPath)
                .ne(GbOrg::getId, self.getId()));
        int delta = newLevel - oldLevel;
        for (GbOrg node : subtree) {
            node.setPath(newPath + node.getPath().substring(oldPath.length()));
            node.setLevel(nvlInt(node.getLevel()) + delta);
            orgMapper.updateById(node);
        }
        log.info("[{}] 移动组织 id={} {} -> {} 子树节点={}", LOG_TAG, self.getId(), oldPath, newPath, subtree.size());
    }

    private void validateName(long parentId, String name, Long selfId) {
        Long same = orgMapper.selectCount(new LambdaQueryWrapper<GbOrg>()
                .eq(GbOrg::getParentId, parentId)
                .eq(GbOrg::getName, name)
                .ne(selfId != null, GbOrg::getId, selfId));
        if (same != null && same > 0) {
            throw new ServiceException("同一父组织下已存在同名组织：" + name);
        }
    }

    private void validateCode(String code, Long selfId) {
        if (code == null) {
            return;
        }
        if (!code.matches(CODE_REGEX)) {
            throw new ServiceException("组织国标编码必须是 20 位数字");
        }
        Long same = orgMapper.selectCount(new LambdaQueryWrapper<GbOrg>()
                .eq(GbOrg::getCode, code)
                .ne(selfId != null, GbOrg::getId, selfId));
        if (same != null && same > 0) {
            throw new ServiceException("组织国标编码已被占用：" + code);
        }
    }

    private GbOrg requireOrg(Long id) {
        GbOrg org = orgMapper.selectById(id);
        if (org == null) {
            throw new ServiceException("组织不存在");
        }
        return org;
    }

    private static Map<Long, Long> toCountMap(List<GbOrgCountVO> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Long> map = new HashMap<>(rows.size());
        for (GbOrgCountVO row : rows) {
            if (row.getOrgId() != null) {
                map.put(row.getOrgId(), row.getTotal() == null ? 0L : row.getTotal());
            }
        }
        return map;
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }

    private static int nvlInt(Integer v) {
        return v == null ? 1 : v;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
