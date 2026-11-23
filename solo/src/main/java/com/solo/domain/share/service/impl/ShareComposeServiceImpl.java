package com.solo.domain.share.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.solo.common.ServiceException;
import com.solo.domain.platform.model.Platform;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResDevice;
import com.solo.domain.resource.model.ResOrg;
import com.solo.domain.resource.model.ResourceStatus;
import com.solo.domain.resource.model.SourceType;
import com.solo.domain.resource.support.ResourcePath;
import com.solo.domain.share.model.OutGroup;
import com.solo.domain.share.model.OutShare;
import com.solo.domain.share.model.PublishEventType;
import com.solo.domain.share.model.ShareRefType;
import com.solo.domain.share.model.ShareScopeMode;
import com.solo.domain.share.model.ShareStatus;
import com.solo.domain.share.service.OutCodeService;
import com.solo.domain.share.service.ShareComposeService;
import com.solo.domain.share.service.ShareOutboxWriter;
import com.solo.domain.share.support.ShareEvent;
import com.solo.domain.share.support.ShareGuard;
import com.solo.domain.share.support.SharePayloads;
import com.solo.domain.share.support.SharePreview;
import com.solo.domain.share.support.ShareScopeResolver;
import com.solo.domain.share.support.ShareSettings;
import com.solo.gb28181.entity.GbParentPlatform;
import com.solo.gb28181.mapper.GbParentPlatformMapper;
import com.solo.infra.mapper.OutGroupMapper;
import com.solo.infra.mapper.OutShareMapper;
import com.solo.infra.mapper.PlatformMapper;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.ResDeviceMapper;
import com.solo.infra.mapper.ResOrgMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 共享编排实现（3.9.1 / 3.9.2 / 4.5.1）。
 *
 * <p><b>三条口径写在这里，别在别处再写一遍</b>：</p>
 * <ol>
 *   <li><b>目标平台是 {@code gb_parent_platform}</b>（本平台作为下级注册的上级），
 *       不是入站的 {@code platform}。两者 id 空间不同，防环比对必须用国标码（见 {@link ShareGuard}）。</li>
 *   <li><b>撤销只置 {@code ref_alive=0}</b>，剔除只置 {@code enabled=0}，谁都不删行（D2）：
 *       删行会丢掉"曾共享给谁"，恢复要人工重配；暴露码墓碑留到投递成功才置（C12 第 4 步）。</li>
 *   <li><b>规则行不参与投递</b>：{@code scope_mode=SUBTREE} 的 ORG 行只是声明，
 *       上级看得见的是它派生出的通道行，所以规则行状态固定 {@code SYNCED}，不进平台汇总。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareComposeServiceImpl implements ShareComposeService {

    private static final long DEFAULT_TENANT_ID = 1L;
    /** 本地资源的来源平台固定为 0（platform 表里没有"本地"这条记录） */
    private static final long NATIVE_PLATFORM_ID = 0L;
    private static final String LOG_TAG = "[上级共享]";
    /** 虚拟组织节点在 out_code_map 里的稳定标识前缀：用 res_org.id，不随名称/位置变化 */
    private static final String GROUP_KEY_PREFIX = "org:";

    private final OutGroupMapper outGroupMapper;
    private final OutShareMapper outShareMapper;
    private final ResOrgMapper orgMapper;
    private final ResChannelMapper channelMapper;
    private final ResDeviceMapper deviceMapper;
    private final GbParentPlatformMapper parentPlatformMapper;
    private final PlatformMapper platformMapper;
    private final OutCodeService outCodeService;
    private final ShareOutboxWriter outboxWriter;
    private final ShareSettings settings;

    // ------------------------------------------------------------------
    // 一键生成虚拟组织树
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public GroupTreeResult generateGroupTree(Long platformId, Long sourceOrgId, Long mountGroupId) {
        requirePlatform(platformId);
        ResOrg root = requireOrg(sourceOrgId);
        if (ResourceStatus.REMOVED.equals(root.getStatus())) {
            throw new ServiceException(400, "组织已删除，无法生成虚拟组织树");
        }
        if (!StringUtils.hasText(root.getPath())) {
            throw new ServiceException(400, "组织缺少物化路径，无法按树生成");
        }
        OutGroup mount = requireGroupOrNull(platformId, mountGroupId);
        String prefix = ResourcePath.normalize(root.getPath());

        List<ResOrg> orgs = orgMapper.selectList(new LambdaQueryWrapper<ResOrg>()
                .eq(ResOrg::getSourceType, root.getSourceType())
                .eq(ResOrg::getSourcePlatformId, nvlLong(root.getSourcePlatformId()))
                .likeRight(ResOrg::getPath, prefix)
                .ne(ResOrg::getStatus, ResourceStatus.REMOVED)
                .orderByAsc(ResOrg::getPath));
        if (orgs.isEmpty()) {
            orgs = List.of(root);
        }

        Map<Long, OutGroup> byOrg = loadGroupsBySourceOrg(platformId);
        int created = 0;
        int updated = 0;
        int moved = 0;
        int total = 0;
        boolean dirty = false;

        // 按 path 升序遍历 = 父在子前：子节点的挂载位置一定已经算好
        for (ResOrg org : orgs) {
            total++;
            OutGroup parent = Objects.equals(org.getId(), root.getId())
                    ? mount
                    : byOrg.getOrDefault(org.getParentId(), mount);
            Long parentId = parent == null ? null : parent.getId();

            OutGroup node = byOrg.get(org.getId());
            if (node == null) {
                node = createGroup(platformId, org, parent);
                byOrg.put(org.getId(), node);
                created++;
                dirty = true;
                continue;
            }
            if (!Objects.equals(node.getParentId(), parentId)) {
                moveGroup(platformId, node, parent);
                // 子树 path 已被一次批量重写，后续子节点必须看新值，否则会二次平移层级
                byOrg = loadGroupsBySourceOrg(platformId);
                moved++;
                dirty = true;
                node = byOrg.get(org.getId());
                if (node == null) {
                    continue;
                }
            }
            if (refreshGroup(node, org)) {
                updated++;
                dirty = true;
            }
        }

        if (dirty) {
            // 树结构一变，逐条 ADD 对不上父节点：直接让上级重拉（C18 的降级形态）
            submitRefresh(platformId, "虚拟组织树已更新（新增 " + created + " / 移动 " + moved + "）");
        }
        log.info("{} 一键生成 platform={} 根组织={} 新增={} 更新={} 移动={} 合计={}",
                LOG_TAG, platformId, sourceOrgId, created, updated, moved, total);
        return new GroupTreeResult(created, updated, moved, total);
    }

    private OutGroup createGroup(Long platformId, ResOrg org, OutGroup parent) {
        long id = IdWorker.getId();
        String groupType = groupTypeOf(org);
        String path = parent == null
                ? ResourcePath.ofRoot(id)
                : ResourcePath.ofChild(parent.getPath(), id);

        OutGroup node = new OutGroup();
        node.setId(id);
        node.setTenantId(DEFAULT_TENANT_ID);
        node.setPlatformId(platformId);
        node.setCode(outCodeService.ensureGroupCode(platformId, groupType, GROUP_KEY_PREFIX + org.getId()));
        node.setParentId(parent == null ? null : parent.getId());
        node.setParentCode(parent == null ? null : parent.getCode());
        node.setPath(path);
        node.setLevel(ResourcePath.levelOf(path));
        node.setName(displayName(org));
        node.setGroupType(groupType);
        node.setSourceOrgId(org.getId());
        node.setSourceOrgPath(org.getPath());
        node.setSortNo(nvlInt(org.getSortNo()));
        outGroupMapper.insert(node);
        log.info("{} 生成虚拟组织 platform={} org={} code={} path={}",
                LOG_TAG, platformId, org.getId(), node.getCode(), path);
        return node;
    }

    /**
     * 换父：两条前缀 UPDATE 就够了（重写 path → 平移 level），不递归逐条改。
     * <p>{@code parent_code} 是子节点指父节点用的，父节点自身的 code 不变，所以子节点不用动。</p>
     */
    private void moveGroup(Long platformId, OutGroup node, OutGroup newParent) {
        String oldPrefix = node.getPath();
        String newPath = newParent == null
                ? ResourcePath.ofRoot(node.getId())
                : ResourcePath.ofChild(newParent.getPath(), node.getId());
        int delta = ResourcePath.levelOf(newPath) - nvlInt(node.getLevel());
        LocalDateTime now = LocalDateTime.now();

        outGroupMapper.rewriteSubtreePath(platformId, oldPrefix, newPath, now);
        if (delta != 0) {
            outGroupMapper.shiftSubtreeLevel(platformId, newPath, delta, now);
        }
        outGroupMapper.update(null, new LambdaUpdateWrapper<OutGroup>()
                .eq(OutGroup::getId, node.getId())
                .set(OutGroup::getParentId, newParent == null ? null : newParent.getId())
                .set(OutGroup::getParentCode, newParent == null ? null : newParent.getCode())
                .set(OutGroup::getPath, newPath)
                .set(OutGroup::getLevel, ResourcePath.levelOf(newPath))
                .set(OutGroup::getUpdateTime, now));
        log.info("{} 移动虚拟组织 platform={} {} -> {} delta={}",
                LOG_TAG, platformId, oldPrefix, newPath, delta);
    }

    /** 名称/排序/来源路径变了才写；码与类型段不动（C16：编码不随资源变化） */
    private boolean refreshGroup(OutGroup node, ResOrg org) {
        String name = displayName(org);
        int sortNo = nvlInt(org.getSortNo());
        if (Objects.equals(node.getName(), name)
                && Objects.equals(node.getSortNo(), sortNo)
                && Objects.equals(node.getSourceOrgPath(), org.getPath())) {
            return false;
        }
        outGroupMapper.update(null, new LambdaUpdateWrapper<OutGroup>()
                .eq(OutGroup::getId, node.getId())
                .set(OutGroup::getName, name)
                .set(OutGroup::getSortNo, sortNo)
                .set(OutGroup::getSourceOrgPath, org.getPath())
                .set(OutGroup::getUpdateTime, LocalDateTime.now()));
        return true;
    }

    private Map<Long, OutGroup> loadGroupsBySourceOrg(Long platformId) {
        Map<Long, OutGroup> map = new HashMap<>();
        for (OutGroup group : outGroupMapper.selectByPlatform(platformId)) {
            if (group.getSourceOrgId() != null) {
                map.put(group.getSourceOrgId(), group);
            }
        }
        return map;
    }

    /** 顶级组织做业务分组（215），更深的做虚拟组织（216）：与"分组里放通道、目录里放分组"的心智一致 */
    private static String groupTypeOf(ResOrg org) {
        int level = nvlInt(org.getLevel());
        return level <= 1 ? OutGroup.TYPE_BUSINESS : OutGroup.TYPE_VIRTUAL;
    }

    private static String displayName(ResOrg org) {
        if (StringUtils.hasText(org.getLocalName())) {
            return org.getLocalName().trim();
        }
        return StringUtils.hasText(org.getName()) ? org.getName().trim() : org.getCode();
    }

    // ------------------------------------------------------------------
    // 规则：子树共享
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public DeriveResult addOrgRule(Long platformId, Long orgId, Long groupId) {
        GbParentPlatform platform = requirePlatform(platformId);
        ResOrg org = requireOrg(orgId);
        if (ResourceStatus.REMOVED.equals(org.getStatus())) {
            throw new ServiceException(400, "组织已删除，不能共享");
        }
        if (!StringUtils.hasText(org.getCode())) {
            throw new ServiceException(400, "组织缺少编码，无法建立共享规则");
        }
        requireGroupOrNull(platformId, groupId);
        guardPlatform(platform, originOf(org.getSourcePlatformId(), org.getOriginChain(),
                org.getHopCount(), org.getCode()));

        String refSourceType = sourceTypeOf(org.getSourceType(), org.getSourcePlatformId());
        long sourcePlatformId = nvlLong(org.getSourcePlatformId());
        String orgPath = ResourcePath.normalize(org.getPath());
        OutShare existing = outShareMapper.selectByUnique(platformId, refSourceType, sourcePlatformId, org.getCode());
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            OutShare row = new OutShare();
            row.setId(IdWorker.getId());
            row.setTenantId(DEFAULT_TENANT_ID);
            row.setPlatformId(platformId);
            row.setGroupId(groupId);
            row.setRefType(ShareRefType.ORG);
            row.setRefId(org.getId());
            row.setRefSourceType(refSourceType);
            row.setRefSourcePlatformId(sourcePlatformId);
            row.setRefCode(org.getCode());
            row.setOrgPath(orgPath);
            row.setScopeMode(ShareScopeMode.SUBTREE);
            row.setDerived(0);
            row.setEnabled(1);
            row.setRefAlive(1);
            row.setStatus(ShareStatus.SYNCED);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            outShareMapper.insert(row);
            log.info("{} 新增子树规则 platform={} org={} path={}", LOG_TAG, platformId, orgId, orgPath);
        } else {
            if (!existing.isRule()) {
                throw new ServiceException(400, "编码 " + org.getCode()
                        + " 已作为一条资源共享给该平台，请先撤销那条共享再改为组织共享");
            }
            outShareMapper.update(null, new LambdaUpdateWrapper<OutShare>()
                    .eq(OutShare::getId, existing.getId())
                    .set(OutShare::getRefId, org.getId())
                    .set(OutShare::getOrgPath, orgPath)
                    .set(OutShare::getGroupId, groupId)
                    .set(OutShare::getEnabled, 1)
                    .set(OutShare::getRefAlive, 1)
                    .set(OutShare::getStatus, ShareStatus.SYNCED)
                    .set(OutShare::getUpdateTime, now));
            log.info("{} 更新子树规则 platform={} org={} path={}", LOG_TAG, platformId, orgId, orgPath);
        }
        return rebuildDerived(platformId);
    }

    @Override
    @Transactional
    public DeriveResult removeOrgRule(Long platformId, Long orgId, String operator) {
        requirePlatform(platformId);
        ResOrg org = requireOrg(orgId);
        OutShare existing = outShareMapper.selectByUnique(platformId,
                sourceTypeOf(org.getSourceType(), org.getSourcePlatformId()),
                nvlLong(org.getSourcePlatformId()), org.getCode());
        if (existing == null || !existing.isRule()) {
            throw new ServiceException(404, "该组织尚未建立子树共享规则");
        }
        if (nvlInt(existing.getRefAlive()) == 0) {
            return rebuildDerived(platformId);
        }
        outShareMapper.markRefDeadById(existing.getId(), ShareStatus.REVOKING, trimToNull(operator),
                LocalDateTime.now());
        log.info("{} 撤销子树规则 platform={} org={} 操作人={}", LOG_TAG, platformId, orgId, operator);
        // 规则死了，它派生出来的通道行会在重算里失去覆盖并被撤销（逐条 DELETE）
        return rebuildDerived(platformId);
    }

    // ------------------------------------------------------------------
    // 显式共享：通道 / 设备
    // ------------------------------------------------------------------
    @Override
    @Transactional
    public DeriveResult addChannels(Long platformId, List<Long> channelIds, Long groupId) {
        GbParentPlatform platform = requirePlatform(platformId);
        OutGroup mount = requireGroupOrNull(platformId, groupId);
        List<ResChannel> channels = loadChannels(channelIds);
        if (channels.isEmpty()) {
            return DeriveResult.empty();
        }
        String localDomain = outCodeService.localDomain();
        Map<Long, String> sourceCodes = loadSourceCodes(channels);
        Map<Long, ResDevice> devices = loadDevices(channels);
        Map<Long, String> exposedCodes = outCodeService.ensureChannelCodes(platformId, channels);

        List<ShareEvent> events = new ArrayList<>();
        int added = 0;
        int updated = 0;
        int restored = 0;
        int skipped = 0;
        LocalDateTime now = LocalDateTime.now();

        for (ResChannel channel : channels) {
            ShareGuard.Violation violation = ShareGuard.check(platformId, platform.getServerGbId(),
                    originOf(channel, sourceCodes), localDomain, settings.getMaxHop());
            if (violation != null) {
                skipped++;
                log.warn("{} 拒绝显式共享 platform={} channel={} 原因={}",
                        LOG_TAG, platformId, channel.getCode(), violation);
                continue;
            }
            String exposed = exposedCodes.get(channel.getId());
            if (!StringUtils.hasText(exposed)) {
                skipped++;
                continue;
            }
            long sourcePlatformId = nvlLong(channel.getSourcePlatformId());
            OutShare row = outShareMapper.selectByUnique(platformId,
                    sourceTypeOf(channel.getSourceType(), channel.getSourcePlatformId()),
                    sourcePlatformId, channel.getCode());
            ResDevice device = devices.get(channel.getDeviceId());
            if (row == null) {
                OutShare created = channelRow(platformId, channel, mount, now);
                created.setDerived(0);
                created.setStatus(ShareStatus.PENDING);
                outShareMapper.insert(created);
                added++;
                events.add(channelEvent(PublishEventType.ADD, channel, device, mount, exposed));
                continue;
            }
            if (!ShareRefType.CHANNEL.equals(row.getRefType())
                    || !Objects.equals(row.getRefId(), channel.getId())) {
                skipped++;
                log.warn("{} 编码冲突 platform={} code={} 已属于另一种资源，跳过",
                        LOG_TAG, platformId, channel.getCode());
                continue;
            }

            boolean visible = nvlInt(row.getEnabled()) == 1 && nvlInt(row.getRefAlive()) == 1;
            LambdaUpdateWrapper<OutShare> update = new LambdaUpdateWrapper<OutShare>()
                    .eq(OutShare::getId, row.getId());
            boolean dirty = false;
            if (nvlInt(row.getDerived()) != 0) {
                update.set(OutShare::getDerived, 0);
                dirty = true;
            }
            if (nvlInt(row.getEnabled()) != 1) {
                update.set(OutShare::getEnabled, 1);
                dirty = true;
            }
            if (nvlInt(row.getRefAlive()) != 1) {
                update.set(OutShare::getRefAlive, 1);
                dirty = true;
            }
            if (!Objects.equals(row.getGroupId(), mount == null ? null : mount.getId())) {
                update.set(OutShare::getGroupId, mount == null ? null : mount.getId());
                dirty = true;
            }
            if (!dirty) {
                continue;
            }
            update.set(OutShare::getScopeMode, ShareScopeMode.EXPLICIT)
                    .set(OutShare::getStatus, ShareStatus.PENDING)
                    .set(OutShare::getUpdateTime, now);
            outShareMapper.update(null, update);
            if (visible) {
                updated++;
                events.add(channelEvent(PublishEventType.UPDATE, channel, device, mount, exposed));
            } else {
                restored++;
                events.add(channelEvent(PublishEventType.ADD, channel, device, mount, exposed));
            }
        }

        outboxWriter.submit(platformId, events, false);
        log.info("{} 显式共享 platform={} 新增={} 更新={} 恢复={} 跳过={}",
                LOG_TAG, platformId, added, updated, restored, skipped);
        return new DeriveResult(added, updated, restored, 0, skipped);
    }

    @Override
    @Transactional
    public DeriveResult addDevices(Long platformId, List<Long> deviceIds, Long groupId) {
        requirePlatform(platformId);
        if (deviceIds == null || deviceIds.isEmpty()) {
            return DeriveResult.empty();
        }
        // 共享设备 = 共享其全部通道：不单独建 DEVICE 行，避免同一条通道既算设备又算通道而重复推送
        List<Long> channelIds = new ArrayList<>();
        for (Long deviceId : new HashSet<>(deviceIds)) {
            if (deviceId == null) {
                continue;
            }
            for (ResChannel channel : channelMapper.selectAliveByDeviceId(deviceId)) {
                channelIds.add(channel.getId());
            }
        }
        return addChannels(platformId, channelIds, groupId);
    }

    // ------------------------------------------------------------------
    // 剔除 / 恢复 / 撤销
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public void excludeChannel(Long platformId, Long channelId, String operator) {
        requirePlatform(platformId);
        ResChannel channel = requireChannel(channelId);
        List<OutShare> rows = outShareMapper.selectByRefInPlatform(platformId, ShareRefType.CHANNEL, channelId);
        LocalDateTime now = LocalDateTime.now();

        if (rows.isEmpty()) {
            // 还没有行：插一条抑制行，否则下一条规则会把刚刚剔除的通道重新派生成可见
            OutShare suppress = channelRow(platformId, channel, null, now);
            suppress.setDerived(1);
            suppress.setEnabled(0);
            suppress.setStatus(ShareStatus.SYNCED);
            outShareMapper.insert(suppress);
            log.info("{} 剔除（抑制行） platform={} channel={}", LOG_TAG, platformId, channel.getCode());
            return;
        }

        OutShare row = rows.get(0);
        if (nvlInt(row.getEnabled()) == 0) {
            return;
        }
        boolean wasVisible = nvlInt(row.getRefAlive()) == 1;
        outShareMapper.updateEnabled(row.getId(), 0, trimToNull(operator), now);
        if (!wasVisible) {
            return;
        }
        outShareMapper.updateStatus(row.getId(), ShareStatus.PENDING, null, now);
        String exposed = outCodeService.exposedCodeOf(platformId, ShareRefType.CHANNEL, row.getRefCode());
        OutGroup mount = row.getGroupId() == null ? null : outGroupMapper.selectById(row.getGroupId());
        ResDevice device = channel.getDeviceId() == null ? null : deviceMapper.selectById(channel.getDeviceId());
        outboxWriter.submit(platformId,
                List.of(deleteEvent(row, channel, device, mount, exposed)), false);
        log.info("{} 剔除 platform={} channel={} 已推 DELETE", LOG_TAG, platformId, channel.getCode());
    }

    @Override
    @Transactional
    public void restoreChannel(Long platformId, Long channelId, String operator) {
        requirePlatform(platformId);
        ResChannel channel = requireChannel(channelId);
        List<OutShare> rows = outShareMapper.selectByRefInPlatform(platformId, ShareRefType.CHANNEL, channelId);
        if (rows.isEmpty()) {
            return;
        }
        OutShare row = rows.get(0);
        if (nvlInt(row.getEnabled()) == 1) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        // 规则还覆盖它 → 回到派生行；否则转成显式行，免得下一次派生重算又把它撤掉
        int derived = coveredByRule(platformId, channel) ? 1 : 0;
        outShareMapper.update(null, new LambdaUpdateWrapper<OutShare>()
                .eq(OutShare::getId, row.getId())
                .set(OutShare::getEnabled, 1)
                .set(OutShare::getRefAlive, 1)
                .set(OutShare::getDerived, derived)
                .set(OutShare::getStatus, ShareStatus.PENDING)
                .set(OutShare::getOperator, trimToNull(operator))
                .set(OutShare::getUpdateTime, now));

        String exposed = outCodeService.exposedCodeOf(platformId, ShareRefType.CHANNEL, row.getRefCode());
        if (!StringUtils.hasText(exposed)) {
            // 从未分配过（纯抑制行）：现在要露出来了，才允许消耗序号
            exposed = outCodeService.ensureChannelCode(platformId, channel);
        }
        OutGroup mount = row.getGroupId() == null ? null : outGroupMapper.selectById(row.getGroupId());
        ResDevice device = channel.getDeviceId() == null ? null : deviceMapper.selectById(channel.getDeviceId());
        outboxWriter.submit(platformId,
                List.of(channelEvent(PublishEventType.ADD, channel, device, mount, exposed)), false);
        log.info("{} 取消剔除 platform={} channel={} derived={}", LOG_TAG, platformId, channel.getCode(), derived);
    }

    @Override
    @Transactional
    public void revokeChannel(Long platformId, Long channelId, String operator) {
        requirePlatform(platformId);
        List<OutShare> rows = outShareMapper.selectByRefInPlatform(platformId, ShareRefType.CHANNEL, channelId);
        if (rows.isEmpty()) {
            throw new ServiceException(404, "该通道尚未共享给此上级平台");
        }
        OutShare row = rows.get(0);
        if (nvlInt(row.getRefAlive()) == 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String exposed = outCodeService.exposedCodeOf(platformId, ShareRefType.CHANNEL, row.getRefCode());
        ResChannel channel = channelMapper.selectById(channelId);
        ResDevice device = channel == null || channel.getDeviceId() == null
                ? null : deviceMapper.selectById(channel.getDeviceId());
        OutGroup mount = row.getGroupId() == null ? null : outGroupMapper.selectById(row.getGroupId());

        outShareMapper.markRefDeadById(row.getId(), ShareStatus.REVOKING, trimToNull(operator), now);
        outboxWriter.submit(platformId, List.of(deleteEvent(row, channel, device, mount, exposed)), false);
        log.info("{} 撤销单条共享 platform={} channelId={} code={} 操作人={}",
                LOG_TAG, platformId, channelId, row.getRefCode(), operator);
    }

    @Override
    @Transactional
    public void revokePlatform(Long platformId, String operator) {
        requirePlatform(platformId);
        LocalDateTime now = LocalDateTime.now();
        // 分组节点的删除素材必须在删表之前快照：删完 out_group 就拿不到名称与父子码了
        List<Map<String, Object>> groupItems = new ArrayList<>();
        List<OutGroup> groups = outGroupMapper.selectByPlatform(platformId);
        for (OutGroup group : groups) {
            groupItems.add(SharePayloads.group(group));
        }
        // 按路径升序取到的顺序是父在前，删除必须反过来（叶子 → 根），否则上级会留下孤儿子节点
        Collections.reverse(groupItems);

        int revoked = outShareMapper.revokeByPlatform(platformId, ShareStatus.REVOKING, now);
        outShareMapper.clearGroupIdByPlatform(platformId, now);
        outGroupMapper.deleteByPlatform(platformId);
        if (revoked > 0) {
            // 万级通道逐条 DELETE 是自己打自己：让上级重拉一次，目录自然对齐（C18）。
            // 投递阶段会顺手把 REVOKING 的通道逐条补 DEL（快照是 upsert，删不掉节点，见 S5）
            submitRefresh(platformId, "撤销该上级的全部共享（" + revoked + " 条）");
        }
        if (!groupItems.isEmpty()) {
            // 快照删不掉分组节点，撤销后必须显式删；排在 REFRESH 之后 = 通道先删、分组后删
            outboxWriter.submit(platformId, List.of(ShareEvent.batchDelete(groupItems)), false);
        }
        log.info("{} 撤销整平台 platform={} 条数={} 分组={} 操作人={}",
                LOG_TAG, platformId, revoked, groupItems.size(), operator);
    }

    // ------------------------------------------------------------------
    // 派生重算
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public DeriveResult rebuildDerived(Long platformId) {
        GbParentPlatform platform = requirePlatform(platformId);
        String localDomain = outCodeService.localDomain();
        List<OutShare> all = outShareMapper.selectByPlatform(platformId);
        List<OutShare> rules = outShareMapper.selectRules(platformId);

        Map<Long, OutShare> channelRows = new HashMap<>();
        for (OutShare row : all) {
            if (ShareRefType.CHANNEL.equals(row.getRefType()) && row.getRefId() != null) {
                channelRows.putIfAbsent(row.getRefId(), row);
            }
        }
        Map<Long, OutGroup> groupById = new HashMap<>();
        Map<Long, OutGroup> groupByOrg = new HashMap<>();
        for (OutGroup group : outGroupMapper.selectByPlatform(platformId)) {
            groupById.put(group.getId(), group);
            if (group.getSourceOrgId() != null) {
                groupByOrg.put(group.getSourceOrgId(), group);
            }
        }

        // 候选 = 规则命中的通道 ∪ 设备行带出的通道；putIfAbsent 保证同一条通道只处理一次
        Map<Long, ResChannel> candidates = new LinkedHashMap<>();
        Map<Long, OutGroup> mounts = new HashMap<>();
        for (OutShare rule : rules) {
            if (!StringUtils.hasText(rule.getOrgPath())) {
                continue;
            }
            OutGroup fallback = rule.getGroupId() == null ? null : groupById.get(rule.getGroupId());
            for (ResChannel channel :
                    channelMapper.selectAliveByOrgPathPrefix(ResourcePath.normalize(rule.getOrgPath()))) {
                if (candidates.putIfAbsent(channel.getId(), channel) == null) {
                    mounts.put(channel.getId(), mountFor(channel, fallback, groupByOrg));
                }
            }
        }
        for (OutShare row : all) {
            if (!ShareRefType.DEVICE.equals(row.getRefType()) || row.getRefId() == null) {
                continue;
            }
            if (nvlInt(row.getEnabled()) != 1 || nvlInt(row.getRefAlive()) != 1) {
                continue;
            }
            OutGroup mount = row.getGroupId() == null ? null : groupById.get(row.getGroupId());
            for (ResChannel channel : channelMapper.selectAliveByDeviceId(row.getRefId())) {
                if (candidates.putIfAbsent(channel.getId(), channel) == null) {
                    mounts.put(channel.getId(), mount);
                }
            }
        }

        // 防环：自动派生同样不能把资源推回它的来源（C20 保存时校验）
        Map<Long, String> sourceCodes = loadSourceCodes(candidates.values());
        int skipped = 0;
        List<Long> blocked = new ArrayList<>();
        for (ResChannel channel : candidates.values()) {
            ShareGuard.Violation violation = ShareGuard.check(platformId, platform.getServerGbId(),
                    originOf(channel, sourceCodes), localDomain, settings.getMaxHop());
            if (violation != null) {
                blocked.add(channel.getId());
                skipped++;
                log.warn("{} 派生跳过 platform={} channel={} 原因={}",
                        LOG_TAG, platformId, channel.getCode(), violation);
            }
        }
        for (Long id : blocked) {
            candidates.remove(id);
            mounts.remove(id);
        }

        List<ResChannel> needCode = new ArrayList<>();
        List<String> nativeCodes = new ArrayList<>();
        for (ResChannel channel : candidates.values()) {
            nativeCodes.add(channel.getCode());
            if (!channelRows.containsKey(channel.getId())) {
                needCode.add(channel);
            }
        }
        for (OutShare row : channelRows.values()) {
            if (StringUtils.hasText(row.getRefCode())) {
                nativeCodes.add(row.getRefCode());
            }
        }
        Map<String, String> codeByNative = outCodeService.exposedCodesOf(
                platformId, ShareRefType.CHANNEL, nativeCodes);
        Map<Long, String> allocated = needCode.isEmpty()
                ? Map.of() : outCodeService.ensureChannelCodes(platformId, needCode);
        Map<Long, ResDevice> devices = loadDevices(candidates.values());

        // 超过配置阈值就别组 payload 了：写库照做，变更改成一条 REFRESH（C18）
        boolean degraded = settings.getDetailMax() > 0 && candidates.size() > settings.getDetailMax();
        List<ShareEvent> events = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        int added = 0;
        int updated = 0;
        int restored = 0;

        for (ResChannel channel : candidates.values()) {
            OutShare row = channelRows.get(channel.getId());
            OutGroup mount = mounts.get(channel.getId());
            Long mountId = mount == null ? null : mount.getId();
            ResDevice device = devices.get(channel.getDeviceId());

            if (row == null) {
                String exposed = allocated.get(channel.getId());
                if (!StringUtils.hasText(exposed)) {
                    skipped++;
                    continue;
                }
                OutShare created = channelRow(platformId, channel, mount, now);
                created.setDerived(1);
                created.setStatus(ShareStatus.PENDING);
                outShareMapper.insert(created);
                channelRows.put(channel.getId(), created);
                added++;
                if (!degraded) {
                    events.add(channelEvent(PublishEventType.ADD, channel, device, mount, exposed));
                }
                continue;
            }
            if (nvlInt(row.getEnabled()) == 0) {
                // 剔除优先于规则：抑制行既不复活也不推送
                continue;
            }
            String exposed = codeByNative.get(row.getRefCode());
            if (!StringUtils.hasText(exposed)) {
                exposed = outCodeService.ensureChannelCode(platformId, channel);
            }
            if (nvlInt(row.getRefAlive()) == 0) {
                if (nvlInt(row.getDerived()) != 1) {
                    // 人工撤销过的显式行：规则不能替用户把它复活
                    continue;
                }
                // 按行恢复而不是按资源：同一条通道在别的上级可能被用户单独撤销过，
                // 按 {@code ref_type + ref_id} 批量置活会把它一起复活
                outShareMapper.markRestored(row.getId(), now);
                restored++;
                if (!degraded) {
                    events.add(channelEvent(PublishEventType.ADD, channel, device, mount, exposed));
                }
                continue;
            }
            if (!Objects.equals(row.getGroupId(), mountId)) {
                outShareMapper.updateGroup(row.getId(), mountId, now);
                outShareMapper.updateStatus(row.getId(), ShareStatus.PENDING, null, now);
                updated++;
                if (!degraded) {
                    events.add(channelEvent(PublishEventType.UPDATE, channel, device, mount, exposed));
                }
            }
        }

        int removed = 0;
        for (OutShare row : all) {
            if (!ShareRefType.CHANNEL.equals(row.getRefType())) {
                continue;
            }
            if (nvlInt(row.getDerived()) != 1 || nvlInt(row.getRefAlive()) != 1
                    || nvlInt(row.getEnabled()) != 1) {
                continue;
            }
            if (row.getRefId() != null && candidates.containsKey(row.getRefId())) {
                continue;
            }
            // 规则不再覆盖 / 关联组织被移出树：撤销这条派生共享，但保留行（D2）
            outShareMapper.markRefDeadById(row.getId(), ShareStatus.REVOKING, null, now);
            removed++;
            if (!degraded) {
                ResChannel channel = row.getRefId() == null ? null : channelMapper.selectById(row.getRefId());
                ResDevice device = channel == null || channel.getDeviceId() == null
                        ? null : deviceMapper.selectById(channel.getDeviceId());
                OutGroup mount = row.getGroupId() == null ? null : groupById.get(row.getGroupId());
                events.add(deleteEvent(row, channel, device, mount, codeByNative.get(row.getRefCode())));
            }
        }

        int changed = added + updated + restored + removed;
        if (degraded) {
            if (changed > 0) {
                outboxWriter.submit(platformId,
                        List.of(ShareEvent.refresh("派生变更超过明细上限")), false);
            }
        } else if (!events.isEmpty()) {
            outboxWriter.submit(platformId, events, false);
        }
        log.info("{} 派生重算 platform={} 新增={} 更新={} 恢复={} 撤销={} 跳过={} 候选={}",
                LOG_TAG, platformId, added, updated, restored, removed, skipped, candidates.size());
        return new DeriveResult(added, updated, restored, removed, skipped);
    }

    // ------------------------------------------------------------------
    // 提交前预览
    // ------------------------------------------------------------------

    /**
     * 提交前预览（不落库、不分配编码）。
     *
     * <p>候选集展开顺序与真正提交一致：组织子树 → 设备 → 显式通道，
     * {@code putIfAbsent} 保证同一条通道只统计一次。</p>
     */
    @Override
    public SharePreview preview(Long platformId, List<Long> orgIds, List<Long> channelIds, List<Long> deviceIds) {
        GbParentPlatform platform = requirePlatform(platformId);
        Map<Long, ResChannel> candidates = new LinkedHashMap<>();

        if (orgIds != null) {
            for (Long orgId : new HashSet<>(orgIds)) {
                if (orgId == null) {
                    continue;
                }
                ResOrg org = orgMapper.selectById(orgId);
                if (org == null || ResourceStatus.REMOVED.equals(org.getStatus())
                        || !StringUtils.hasText(org.getPath())) {
                    continue;
                }
                for (ResChannel channel :
                        channelMapper.selectAliveByOrgPathPrefix(ResourcePath.normalize(org.getPath()))) {
                    candidates.putIfAbsent(channel.getId(), channel);
                }
            }
        }
        if (deviceIds != null) {
            for (Long deviceId : new HashSet<>(deviceIds)) {
                if (deviceId == null) {
                    continue;
                }
                for (ResChannel channel : channelMapper.selectAliveByDeviceId(deviceId)) {
                    candidates.putIfAbsent(channel.getId(), channel);
                }
            }
        }
        for (ResChannel channel : loadChannels(channelIds)) {
            candidates.putIfAbsent(channel.getId(), channel);
        }
        if (candidates.isEmpty()) {
            return SharePreview.empty();
        }

        String localDomain = outCodeService.localDomain();
        Map<Long, String> sourceCodes = loadSourceCodes(candidates.values());
        int shared = 0;
        int added = 0;
        int restored = 0;
        int excluded = 0;
        int renumber = 0;
        List<SharePreview.Conflict> conflicts = new ArrayList<>();

        for (ResChannel channel : candidates.values()) {
            ShareGuard.Violation violation = ShareGuard.check(platformId, platform.getServerGbId(),
                    originOf(channel, sourceCodes), localDomain, settings.getMaxHop());
            if (violation != null) {
                conflicts.add(new SharePreview.Conflict(channel.getId(), channel.getCode(),
                        channelName(channel),
                        ShareGuard.message(violation, platform.getName(), sourceNameOf(channel, sourceCodes))));
                continue;
            }
            List<OutShare> rows = outShareMapper.selectByRefInPlatform(
                    platformId, ShareRefType.CHANNEL, channel.getId());
            if (rows == null || rows.isEmpty()) {
                added++;
                // 只有新分配编码的入站资源才算"需重新编号"：恢复行沿用墓碑码
                if (SourceType.INBOUND.equals(channel.getSourceType())) {
                    renumber++;
                }
                continue;
            }
            OutShare row = rows.get(0);
            if (nvlInt(row.getEnabled()) == 0) {
                excluded++;
            } else if (nvlInt(row.getRefAlive()) == 1) {
                shared++;
            } else {
                restored++;
            }
        }
        log.info("{} 预览 platform={} 候选={} 已共享={} 新增={} 恢复={} 剔除中={} 需重编号={} 冲突={}",
                LOG_TAG, platformId, candidates.size(), shared, added, restored, excluded, renumber,
                conflicts.size());
        return new SharePreview(candidates.size(), shared, added, restored, excluded, renumber, conflicts);
    }

    /** 来源平台名（冲突文案用）；本地资源返回 {@code null}，文案里会退化成"来源平台" */
    private String sourceNameOf(ResChannel channel, Map<Long, String> sourceCodes) {
        long source = nvlLong(channel.getSourcePlatformId());
        if (source == NATIVE_PLATFORM_ID) {
            return null;
        }
        Platform platform = platformMapper.selectById(source);
        return platform == null ? sourceCodes.get(source) : platform.getName();
    }

    private static String channelName(ResChannel channel) {
        if (StringUtils.hasText(channel.getLocalName())) {
            return channel.getLocalName().trim();
        }
        return StringUtils.hasText(channel.getName()) ? channel.getName().trim() : channel.getCode();
    }

    /**
     * 通道挂到哪一层虚拟组织：沿它自己的组织路径从深到浅，取第一个已镜像的节点。
     * <p>为什么不用规则行上的 group_id：一条规则覆盖整棵子树时，子组织如果有自己的
     * 镜像节点，通道应该挂在子节点下，否则上级看到的目录会全挤在根上。</p>
     */
    private static OutGroup mountFor(ResChannel channel, OutGroup fallback, Map<Long, OutGroup> groupByOrg) {
        List<Long> orgIds = orgIdsOf(channel.getOrgPath());
        for (int i = orgIds.size() - 1; i >= 0; i--) {
            OutGroup group = groupByOrg.get(orgIds.get(i));
            if (group != null) {
                return group;
            }
        }
        return fallback;
    }

    private boolean coveredByRule(Long platformId, ResChannel channel) {
        for (OutShare rule : outShareMapper.selectRules(platformId)) {
            if (ShareScopeResolver.covers(rule.getOrgPath(), channel.getOrgPath())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 行构造 / 事件构造
    // ------------------------------------------------------------------

    private OutShare channelRow(Long platformId, ResChannel channel, OutGroup mount, LocalDateTime now) {
        OutShare row = new OutShare();
        row.setId(IdWorker.getId());
        row.setTenantId(DEFAULT_TENANT_ID);
        row.setPlatformId(platformId);
        row.setGroupId(mount == null ? null : mount.getId());
        row.setRefType(ShareRefType.CHANNEL);
        row.setRefId(channel.getId());
        row.setRefSourceType(sourceTypeOf(channel.getSourceType(), channel.getSourcePlatformId()));
        row.setRefSourcePlatformId(nvlLong(channel.getSourcePlatformId()));
        row.setRefCode(channel.getCode());
        row.setOrgPath(ResourcePath.normalize(channel.getOrgPath()));
        row.setScopeMode(ShareScopeMode.EXPLICIT);
        row.setDerived(1);
        row.setEnabled(1);
        row.setRefAlive(1);
        row.setStatus(ShareStatus.PENDING);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        return row;
    }

    private ShareEvent channelEvent(String eventType, ResChannel channel, ResDevice device,
                                    OutGroup mount, String exposed) {
        return new ShareEvent(eventType, ShareRefType.CHANNEL, channel.getId(), channel.getCode(), exposed,
                SharePayloads.channel(channel, device, mount, exposed));
    }

    /** DELETE 时资源可能已经 REMOVED（设备同步删掉了），payload 只能从 out_share 行补齐定位字段 */
    private ShareEvent deleteEvent(OutShare row, ResChannel channel, ResDevice device,
                                   OutGroup mount, String exposed) {
        if (channel != null) {
            return channelEvent(PublishEventType.DELETE, channel, device, mount, exposed);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "CHANNEL");
        payload.put("exposedCode", exposed);
        payload.put("nativeCode", row.getRefCode());
        payload.put("name", null);
        payload.put("parentCode", mount == null ? null : mount.getCode());
        payload.put("parentId", mount == null ? null : String.valueOf(mount.getId()));
        payload.put("orgPath", row.getOrgPath());
        return new ShareEvent(PublishEventType.DELETE, ShareRefType.CHANNEL,
                row.getRefId(), row.getRefCode(), exposed, payload);
    }

    private void submitRefresh(Long platformId, String reason) {
        outboxWriter.submit(platformId, List.of(ShareEvent.refresh(reason)), true);
    }

    // ------------------------------------------------------------------
    // 防环
    // ------------------------------------------------------------------

    private void guardPlatform(GbParentPlatform platform, ShareGuard.Origin origin) {
        ShareGuard.Violation violation = ShareGuard.check(platform.getId(), platform.getServerGbId(),
                origin, outCodeService.localDomain(), settings.getMaxHop());
        if (violation == null) {
            return;
        }
        String sourceName = null;
        if (origin.sourcePlatformId() != null && origin.sourcePlatformId() > NATIVE_PLATFORM_ID) {
            Platform source = platformMapper.selectById(origin.sourcePlatformId());
            sourceName = source == null ? origin.sourcePlatformCode() : source.getName();
        }
        throw new ServiceException(400, ShareGuard.message(violation, platform.getName(), sourceName));
    }

    private ShareGuard.Origin originOf(Long sourcePlatformId, String originChain,
                                       Integer hopCount, String code) {
        long source = nvlLong(sourcePlatformId);
        String platformCode = null;
        if (source != NATIVE_PLATFORM_ID) {
            Platform platform = platformMapper.selectById(source);
            platformCode = platform == null ? null : platform.getCode();
        }
        return new ShareGuard.Origin(source, platformCode, originChain, nvlInt(hopCount), code);
    }

    private static ShareGuard.Origin originOf(ResChannel channel, Map<Long, String> sourceCodes) {
        long source = nvlLong(channel.getSourcePlatformId());
        return new ShareGuard.Origin(source,
                source == NATIVE_PLATFORM_ID ? null : sourceCodes.get(source),
                channel.getOriginChain(), nvlInt(channel.getHopCount()), channel.getCode());
    }

    // ------------------------------------------------------------------
    // 查询辅助
    // ------------------------------------------------------------------

    private GbParentPlatform requirePlatform(Long platformId) {
        if (platformId == null) {
            throw new ServiceException("上级平台不能为空");
        }
        GbParentPlatform platform = parentPlatformMapper.selectById(platformId);
        if (platform == null) {
            throw new ServiceException("上级平台不存在：" + platformId);
        }
        return platform;
    }

    private ResOrg requireOrg(Long orgId) {
        if (orgId == null) {
            throw new ServiceException("组织不能为空");
        }
        ResOrg org = orgMapper.selectById(orgId);
        if (org == null) {
            throw new ServiceException("组织不存在：" + orgId);
        }
        return org;
    }

    private ResChannel requireChannel(Long channelId) {
        if (channelId == null) {
            throw new ServiceException("通道不能为空");
        }
        ResChannel channel = channelMapper.selectById(channelId);
        if (channel == null || ResourceStatus.REMOVED.equals(channel.getStatus())) {
            throw new ServiceException("通道不存在或已删除：" + channelId);
        }
        return channel;
    }

    private OutGroup requireGroupOrNull(Long platformId, Long groupId) {
        if (groupId == null || groupId <= 0) {
            return null;
        }
        OutGroup group = outGroupMapper.selectById(groupId);
        if (group == null || !Objects.equals(group.getPlatformId(), platformId)) {
            throw new ServiceException(400, "虚拟组织不存在或不属于该上级平台：" + groupId);
        }
        return group;
    }

    private List<ResChannel> loadChannels(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<Long> clean = new HashSet<>();
        for (Long id : ids) {
            if (id != null) {
                clean.add(id);
            }
        }
        if (clean.isEmpty()) {
            return List.of();
        }
        List<ResChannel> out = new ArrayList<>(clean.size());
        for (ResChannel channel : channelMapper.selectBatchIds(clean)) {
            if (channel == null || ResourceStatus.REMOVED.equals(channel.getStatus())) {
                continue;
            }
            if (!StringUtils.hasText(channel.getCode())) {
                continue;
            }
            out.add(channel);
        }
        return out;
    }

    private Map<Long, ResDevice> loadDevices(Collection<ResChannel> channels) {
        Set<Long> ids = new HashSet<>();
        for (ResChannel channel : channels) {
            if (channel.getDeviceId() != null) {
                ids.add(channel.getDeviceId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, ResDevice> out = new HashMap<>(ids.size() * 2);
        for (ResDevice device : deviceMapper.selectBatchIds(ids)) {
            out.put(device.getId(), device);
        }
        return out;
    }

    /** 来源平台国标码：防环比对要用它，因为"上级"与"下级"在本系统是两张表、两套 id */
    private Map<Long, String> loadSourceCodes(Collection<ResChannel> channels) {
        Set<Long> ids = new HashSet<>();
        for (ResChannel channel : channels) {
            long source = nvlLong(channel.getSourcePlatformId());
            if (source != NATIVE_PLATFORM_ID) {
                ids.add(source);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new HashMap<>(ids.size() * 2);
        for (Platform platform : platformMapper.selectBatchIds(ids)) {
            out.put(platform.getId(), platform.getCode());
        }
        return out;
    }

    private static List<Long> orgIdsOf(String orgPath) {
        if (!StringUtils.hasText(orgPath)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String seg : ResourcePath.normalize(orgPath).split("/")) {
            if (seg.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(seg));
            } catch (NumberFormatException ignored) {
                // 路径里混进了非数字段：跳过，不影响其余层级命中
            }
        }
        return ids;
    }

    private static String sourceTypeOf(String sourceType, Long sourcePlatformId) {
        if (SourceType.NATIVE.equals(sourceType)) {
            return SourceType.NATIVE;
        }
        if (SourceType.INBOUND.equals(sourceType)) {
            return SourceType.INBOUND;
        }
        return nvlLong(sourcePlatformId) > NATIVE_PLATFORM_ID ? SourceType.INBOUND : SourceType.NATIVE;
    }

    private static int nvlInt(Integer value) {
        return value == null ? 0 : value;
    }

    private static long nvlLong(Long value) {
        return value == null ? 0L : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
