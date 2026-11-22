package com.solo.gb28181.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.solo.common.ServiceException;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.entity.*;
import com.solo.gb28181.event.catalog.ShareChangedEvent;
import com.solo.gb28181.mapper.GbDeviceChannelMapper;
import com.solo.gb28181.mapper.GbDeviceMapper;
import com.solo.gb28181.mapper.GbPlatformGroupMapper;
import com.solo.gb28181.mapper.GbPlatformShareMapper;
import com.solo.gb28181.request.GbPlatformShareSaveRequest;
import com.solo.gb28181.response.GbPlatformSharedResponse;
import com.solo.gb28181.response.GbShareTreeNodeResponse;
import com.solo.gb28181.service.CatalogShareService;
import com.solo.gb28181.service.GbParentPlatformService;
import com.solo.gb28181.transmit.cmd.PlatformCatalogXml;
import com.solo.gb28181.transmit.cmd.PlatformCommander;
import com.solo.gb28181.utils.GbCodeGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>{@code gb_platform_share} <b>只存通道</b>（{@code gb_device_channel} 中 parental=0 的行）。
 * 分组是本平台内部的目录组织，当前不推送给上级（Catalog XML 只发通道 Item）。</p>
 * <p>share 表与推送分组（{@code gb_platform_group}）关联：UI 强制先在弹窗左侧选中分组才能挂载。
 * 同一通道在同一平台下只能挂载一次（{@code uk_platform_channel}），换组要先卸载旧组。
 * <p><b>本表的真实键是 (device_id, channel_id)</b>：通道重新同步会物理删行换雪花主键，
 * 拿主键当外键会让共享在重报后指向悬空行，因此本表不存通道主键。
 * Catalog 响应 / INVITE 反查走全量（不分组）路径——上级不关心"挂在哪个组里"。</p>
 * <p>撞码检测是<b>平台级</b>的：上级看到扁平 DeviceID 空间，跨组同码也会让上级 INVITE 认错通道。</p>
 * <p>{@link #shareTree} 仍返回三级树（设备 → 分组 → 通道）供旧勾选 UI 用；前端契约保留 g_/c_ 前缀。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogShareServiceImpl implements CatalogShareService {

    private static final String LOG_TAG = "通道推送";

    /** 前端 el-tree node-key 前缀：分组 / 通道，避免主键碰撞 */
    private static final String GROUP_PREFIX = "g_";
    private static final String CHANNEL_PREFIX = "c_";
    /** 设备节点前缀（仅作枝干展示，不可勾选共享） */
    private static final String DEVICE_PREFIX = "d_";

    /** 类型段：业务分组 */
    private static final int TYPE_BUSINESS_GROUP = 215;
    /** 类型段：虚拟组织 */
    private static final int TYPE_VIRTUAL_ORG = 216;

    private final GbDeviceChannelMapper channelMapper;
    private final GbDeviceMapper deviceMapper;
    private final GbPlatformShareMapper shareMapper;
    private final GbPlatformGroupMapper groupMapper;
    private final GbCodeGenerator gbCodeGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final GbParentPlatformService gbParentPlatformService;
    private final PlatformCommander platformCommander;
    private final Gb28181Properties properties;

    @Override
    public List<GbShareTreeNodeResponse> shareTree() {
        // 1) 拉所有 catalog item（分组 parental=1 + 通道 parental=0），按 device_id 聚合
        List<GbDeviceChannel> allItems = channelMapper.selectList(
                new LambdaQueryWrapper<GbDeviceChannel>()
                        .orderByDesc(GbDeviceChannel::getParental)
                        .orderByAsc(GbDeviceChannel::getChannelId));
        if (allItems.isEmpty()) {
            return new ArrayList<>();
        }

        // 2) 拉设备表（作枝干标签）
        Map<String, GbDevice> deviceByGbId = deviceMapper.selectList(
                        new LambdaQueryWrapper<GbDevice>().orderByAsc(GbDevice::getName)).stream()
                .collect(Collectors.toMap(GbDevice::getDeviceId, d -> d, (a, b) -> a, LinkedHashMap::new));

        // 3) 按 device_id 分桶
        Map<String, List<GbDeviceChannel>> itemsByDevice = new LinkedHashMap<>();
        for (GbDeviceChannel it : allItems) {
            if (it.getDeviceId() == null) {
                continue;
            }
            itemsByDevice.computeIfAbsent(it.getDeviceId(), k -> new ArrayList<>()).add(it);
        }

        // 4) 组装
        List<GbShareTreeNodeResponse> roots = new ArrayList<>();
        for (Map.Entry<String, List<GbDeviceChannel>> entry : itemsByDevice.entrySet()) {
            String deviceGbId = entry.getKey();
            GbDevice device = deviceByGbId.get(deviceGbId);
            GbShareTreeNodeResponse deviceNode = new GbShareTreeNodeResponse();
            deviceNode.setId(DEVICE_PREFIX + deviceGbId);
            deviceNode.setLabel(device != null && device.getName() != null
                    ? device.getName() + " (" + deviceGbId + ")"
                    : deviceGbId);
            deviceNode.setType("DEVICE");
            deviceNode.setChildren(new ArrayList<>());

            for (GbDeviceChannel it : entry.getValue()) {
                boolean isGroup = it.getParental() != null && it.getParental() == 1;
                GbShareTreeNodeResponse child = new GbShareTreeNodeResponse();
                child.setId(isGroup
                        ? GROUP_PREFIX + it.getId()
                        : CHANNEL_PREFIX + it.getDeviceId() + "_" + it.getChannelId());
                String name = (it.getName() == null || it.getName().isBlank()) ? "-" : it.getName();
                child.setLabel(name + " (" + it.getChannelId() + ")");
                child.setType(isGroup ? "GROUP" : "CHANNEL");
                deviceNode.getChildren().add(child);
            }
            roots.add(deviceNode);
        }
        return roots;
    }

    @Override
    public GbPlatformSharedResponse getShared(Long parentPlatformId) {
        // 全量（不分组）：本表只存通道，节点 id 用业务键（主键会随目录重同步变化）
        List<GbPlatformShare> shares = shareMapper.selectList(
                new LambdaQueryWrapper<GbPlatformShare>()
                        .eq(GbPlatformShare::getParentPlatformId, parentPlatformId));

        GbPlatformSharedResponse resp = new GbPlatformSharedResponse();
        List<String> nodeIds = shares.stream()
                .map(s -> CHANNEL_PREFIX + s.getDeviceId() + "_" + s.getChannelId())
                .collect(Collectors.toList());
        resp.setChannelIds(nodeIds);
        return resp;
    }

    /**
     * @deprecated 旧 catalog 树"全量覆盖勾选"入口。{@code gb_platform_share.group_id} 已改为 NOT NULL
     *             （必须挂在某个推送分组下），本方法没有 groupId 参数无法正确入库，故直接拒绝。
     *             管理台请走按组接口：{@link #addShares} / {@link #removeShare} / {@link #clearShares}。
     */
    @Deprecated
    @Override
    public void saveShared(Long parentPlatformId, GbPlatformShareSaveRequest request) {
        throw new com.solo.common.ServiceException(
                "该接口已废弃：共享通道必须挂载到具体的推送分组下，请改用按分组的添加/卸载接口");
    }

    @Override
    public List<GbDeviceChannel> loadSharedCatalogItems(Long parentPlatformId) {
        // 推送给上级的目录结构 = 推送分组树（gb_platform_group，parental=1）+ 挂载通道（parental=0）。
        // 分组 DeviceID 用 group.code（215/216），通道 parentId 指向所在分组 code。只推送"有通道的分组
        // 及其祖先分组"——空分组（已清空挂载）不推给上级，避免上级残留空目录节点。
        List<GbPlatformShare> shares = shareMapper.selectList(
                new LambdaQueryWrapper<GbPlatformShare>()
                        .eq(GbPlatformShare::getParentPlatformId, parentPlatformId));

        List<GbPlatformGroup> groups = groupMapper.selectList(
                new LambdaQueryWrapper<GbPlatformGroup>()
                        .eq(GbPlatformGroup::getParentPlatformId, parentPlatformId)
                        .orderByAsc(GbPlatformGroup::getId));
        Map<Long, GbPlatformGroup> groupById = groups.stream()
                .collect(Collectors.toMap(GbPlatformGroup::getId, g -> g, (a, b) -> a));
        Map<Long, String> codeByGroupId = groupById.values().stream()
                .collect(Collectors.toMap(GbPlatformGroup::getId, GbPlatformGroup::getCode, (a, b) -> a));

        // 有通道的分组 + 它们的祖先分组（避免通道 ParentID 指向上级没见过的分组）
        Set<Long> neededGroupIds = new HashSet<>();
        for (GbPlatformShare s : shares) {
            Long gid = s.getGroupId();
            while (gid != null && gid != 0L && groupById.containsKey(gid)) {
                if (!neededGroupIds.add(gid)) {
                    break;
                }
                gid = groupById.get(gid).getParentId();
            }
        }

        List<GbDeviceChannel> result = new ArrayList<>();
        for (GbPlatformGroup g : groups) {
            if (!neededGroupIds.contains(g.getId())) {
                continue;
            }
            GbDeviceChannel node = new GbDeviceChannel();
            node.setChannelId(g.getCode());
            node.setName(g.getName());
            node.setParental(1);
            node.setStatus("ON");
            String parentCode = g.getParentId() != null && g.getParentId() != 0L
                    ? codeByGroupId.getOrDefault(g.getParentId(), "0") : "0";
            node.setParentId(parentCode);
            // 216 虚拟组织必须带 BusinessGroupID（所属 215 业务分组编码），否则上级无法把它挂在正确组织下
            if (typeCode(g.getCode()) == TYPE_VIRTUAL_ORG) {
                node.setBusinessGroupId(findBusinessGroupCode(g, groupById));
            }
            result.add(node);
        }

        if (!shares.isEmpty()) {
            Map<GbPlatformShare, GbDeviceChannel> channelByShare = resolveChannels(shares);
            // 厂商/型号/归属/IP 通常只在设备表维护：批量取回后按 deviceId 回填到通道，
            // 报文里这几项才不会因为通道行为空而整段缺失（上级会当成非法节点）
            Set<String> deviceGbIds = channelByShare.values().stream()
                    .map(GbDeviceChannel::getDeviceId)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toSet());
            Map<String, GbDevice> deviceByGbId = deviceGbIds.isEmpty()
                    ? Map.of()
                    : deviceMapper.selectList(new LambdaQueryWrapper<GbDevice>()
                                    .in(GbDevice::getDeviceId, deviceGbIds)).stream()
                            .collect(Collectors.toMap(GbDevice::getDeviceId, d -> d, (a, b) -> a));
            for (GbPlatformShare s : shares) {
                GbDeviceChannel c = channelByShare.get(s);
                if (c == null) {
                    continue;
                }
                c.setParentId(codeByGroupId.getOrDefault(s.getGroupId(), "0"));
                c.setExposedCode(s.getExposedCode());
                fillChannelMeta(c, deviceByGbId.get(c.getDeviceId()));
                result.add(c);
            }
        }

        // 分组按层级深度升序（父一定排在子之前），同深度按 code；通道统一排在分组之后。
        // 不能只按 code 升序：子分组序号比父小时（如父 …2160000009、子 …2160000002）子会跑到父前面，
        // 上级会把 ParentID 指向未知节点的子分组当孤儿丢弃。
        Map<String, Integer> depthByCode = new HashMap<>();
        for (GbPlatformGroup g : groups) {
            if (neededGroupIds.contains(g.getId())) {
                depthByCode.put(g.getCode(), groupDepth(g.getId(), groupById));
            }
        }
        result.sort(Comparator
                .comparingInt((GbDeviceChannel c) -> c.getParental() != null && c.getParental() == 1 ? 0 : 1)
                .thenComparingInt(c -> c.getParental() != null && c.getParental() == 1
                        ? depthByCode.getOrDefault(c.getChannelId(), 0) : 0)
                .thenComparing(GbDeviceChannel::getChannelId, Comparator.nullsLast(String::compareTo)));
        return result;
    }

    /** 分组在树中的深度（根 = 0），排序时用来保证父节点一定在子节点之前 */
    private static int groupDepth(Long groupId, Map<Long, GbPlatformGroup> groupById) {
        int depth = 0;
        Long cursor = groupId;
        int guard = 0;
        while (cursor != null && cursor != 0L && guard++ < 64) {
            GbPlatformGroup g = groupById.get(cursor);
            if (g == null) {
                break;
            }
            Long pid = g.getParentId();
            if (pid == null || pid == 0L) {
                break;
            }
            depth++;
            cursor = pid;
        }
        return depth;
    }

    @Override
    public List<GbDeviceChannel> loadSharedChannels(Long parentPlatformId) {
        return shareMapper.selectSharedChannels(parentPlatformId);
    }

    /**
     * 216 虚拟组织所属的业务分组（215）编码：沿父链上溯第一个 215；找不到返回 null（由 XML 层兜底）。
     */
    private String findBusinessGroupCode(GbPlatformGroup self, Map<Long, GbPlatformGroup> groupById) {
        Long cursor = self.getParentId();
        int guard = 0;
        while (cursor != null && cursor != 0L && guard++ < 64) {
            GbPlatformGroup p = groupById.get(cursor);
            if (p == null) {
                return null;
            }
            if (typeCode(p.getCode()) == TYPE_BUSINESS_GROUP) {
                return p.getCode();
            }
            cursor = p.getParentId();
        }
        return null;
    }

    /**
     * 通道缺失的厂商/型号/归属/IP 从所属设备回填：这几项一般在设备表维护，通道行为空。
     * 只填空值，不覆盖通道自身的配置。
     */
    private void fillChannelMeta(GbDeviceChannel channel, GbDevice device) {
        if (device == null) {
            return;
        }
        if (!StringUtils.hasText(channel.getManufacturer())) {
            channel.setManufacturer(device.getManufacturer());
        }
        if (!StringUtils.hasText(channel.getModel())) {
            channel.setModel(device.getModel());
        }
        if (!StringUtils.hasText(channel.getOwner())) {
            channel.setOwner(device.getOwner());
        }
        if (!StringUtils.hasText(channel.getIpAddress())) {
            channel.setIpAddress(device.getIp());
        }
    }

    /** 取 20 位国标码的类型段（第 11-13 位）；非法编码返回 0 */
    private static int typeCode(String code) {
        if (code == null || code.length() < 13) {
            return 0;
        }
        try {
            return Integer.parseInt(code.substring(10, 13));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void pushCatalog(Long parentPlatformId) {
        GbParentPlatform platform = gbParentPlatformService.getById(parentPlatformId);
        if (platform == null || !Integer.valueOf(1).equals(platform.getEnabled())) {
            throw new ServiceException("上级平台不存在或已禁用");
        }
        List<GbDeviceChannel> items = loadSharedCatalogItems(parentPlatformId);
        platformCommander.sendNotifyCatalogList(platform, properties.getId(), items,
                String.valueOf(PlatformCommander.nextSn()));
        log.info("[{}] 主动推送 parentPlatformId={} 目录条目={} 结构:{}",
                LOG_TAG, parentPlatformId, items.size(), PlatformCatalogXml.describe(items));
    }

    // ==================== 按组增删（推送目录弹窗用） ====================

    @Override
    public IPage<GbDeviceChannel> pageSharedByGroup(Long parentPlatformId, Long groupId, Page<GbDeviceChannel> page, String keyword) {
        requireOwnedGroup(parentPlatformId, groupId);
        return shareMapper.selectSharedByGroupPage(page, parentPlatformId, groupId, normalize(keyword));
    }

    @Override
    public IPage<GbDeviceChannel> pageUnmountedByGroup(Long parentPlatformId, Long groupId, Page<GbDeviceChannel> page, String keyword) {
        requireOwnedGroup(parentPlatformId, groupId);
        return shareMapper.selectUnmountedByGroupPage(page, parentPlatformId, groupId, normalize(keyword));
    }

    @Override
    @Transactional
    public int addShares(Long parentPlatformId, Long groupId, List<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return 0;
        }
        GbPlatformGroup group = requireOwnedGroup(parentPlatformId, groupId);

        // 1) 拉本次要挂载的通道实体（入参仍是主键：UI 列表刚查出来的，主键一定有效）
        List<GbDeviceChannel> resources = channelMapper.selectBatchIds(resourceIds);
        if (resources.isEmpty()) {
            return 0;
        }
        Map<String, GbDeviceChannel> resourceByKey = resources.stream()
                .collect(Collectors.toMap(CatalogShareServiceImpl::channelKey, c -> c, (a, b) -> a));

        // 2) 该平台已有 share（按平台全量取回；判定一律按业务键 (device_id, channel_id)，
        //    通道重新同步换过主键也能认出"已挂载"）
        List<GbPlatformShare> existing = shareMapper.selectList(
                new LambdaQueryWrapper<GbPlatformShare>()
                        .eq(GbPlatformShare::getParentPlatformId, parentPlatformId));
        Map<GbPlatformShare, GbDeviceChannel> existingChannels = resolveChannels(existing);

        // 记录"已挂本组"与"已挂其他组"——后者要让用户知道在哪组，不能静默跳过
        Set<String> alreadySharedInGroup = new HashSet<>();
        Set<String> alreadySharedInOtherGroup = new HashSet<>();
        Map<String, Long> groupIdByKey = new HashMap<>();
        // 撞码是平台级命名空间的问题：上级平台看到的是扁平的 DeviceID 空间，A 组的 34020...0001
        // 和 B 组另一台设备的同码通道推上去就是两个同码 Item，上级 INVITE 必然认错通道。
        // 分组只是本平台 admin UI 的组织结构，对上级不可见（Catalog XML 靠 channel.parent_id 表达层级）。
        Set<String> historyChannelIds = new HashSet<>();
        for (GbPlatformShare s : existing) {
            GbDeviceChannel c = existingChannels.get(s);
            if (c == null) {
                continue;   // 通道已不存在，share 行交给目录清理逻辑处理
            }
            String key = channelKey(c);
            if (c.getChannelId() != null) {
                historyChannelIds.add(c.getChannelId());
            }
            if (groupId.equals(s.getGroupId())) {
                alreadySharedInGroup.add(key);
            } else {
                alreadySharedInOtherGroup.add(key);
                groupIdByKey.putIfAbsent(key, s.getGroupId());
            }
        }
        if (!alreadySharedInOtherGroup.isEmpty()) {
            // 找一下被占用的组名，给出更友好的错误信息
            List<GbPlatformGroup> occupiedGroups = groupMapper.selectBatchIds(
                    new ArrayList<>(groupIdByKey.values()));
            String groupNames = occupiedGroups.stream()
                    .map(g -> "「" + g.getName() + "」")
                    .collect(Collectors.joining("、"));
            throw new com.solo.common.ServiceException(
                    "所选资源中 " + alreadySharedInOtherGroup.size() + " 项已挂载到其他分组（" + groupNames + "），请先在对应分组中卸载后再添加到「" + group.getName() + "」");
        }

        // 3) 撞码检测：本批按 channelId 分组，size>1 自身撞码；任一与 historyChannelIds 相交视为撞码
        Map<String, List<String>> conflictByChannelId = new HashMap<>();
        for (GbDeviceChannel c : resources) {
            if (c.getChannelId() == null || alreadySharedInGroup.contains(channelKey(c))) {
                continue;
            }
            conflictByChannelId.computeIfAbsent(c.getChannelId(), k -> new ArrayList<>()).add(channelKey(c));
        }
        conflictByChannelId.entrySet().removeIf(e -> !historyChannelIds.contains(e.getKey()) && e.getValue().size() <= 1);
        Set<String> needExposedKeys = conflictByChannelId.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toSet());

        // 4) 复用旧暴露码（"已分码不收回"，ADR-020 决策 4 / ADR-026 决策 6）：同一通道对所有上级复用
        //    同一 exposedCode，上级已缓存的旧 DeviceID 不失效。按业务键跨平台捞（不分 platform_id），
        //    因为历史码可能是老 max+1 版本分配的，不捞会漂移。
        Map<String, String> exposedByKey = new HashMap<>();
        if (!needExposedKeys.isEmpty()) {
            Set<String> needDeviceIds = new HashSet<>();
            Set<String> needChannelIds = new HashSet<>();
            for (GbDeviceChannel c : resources) {
                if (needExposedKeys.contains(channelKey(c))) {
                    needDeviceIds.add(c.getDeviceId());
                    needChannelIds.add(c.getChannelId());
                }
            }
            shareMapper.selectList(new LambdaQueryWrapper<GbPlatformShare>()
                            .isNotNull(GbPlatformShare::getExposedCode)
                            .in(GbPlatformShare::getDeviceId, needDeviceIds)
                            .in(GbPlatformShare::getChannelId, needChannelIds))
                    .forEach(s -> exposedByKey.putIfAbsent(channelKey(s.getDeviceId(), s.getChannelId()), s.getExposedCode()));
        }

        // 5) 批量插（跳过已挂本组的）
        List<GbPlatformShare> toInsert = new ArrayList<>();
        for (GbDeviceChannel res : resources) {
            String key = channelKey(res);
            if (alreadySharedInGroup.contains(key)) {
                continue;
            }
            String exposed = null;
            if (needExposedKeys.contains(key)) {
                // 优先复用旧码（跨平台稳定），无旧码才按 (deviceId, channelId) 哈希派生
                exposed = exposedByKey.get(key);
                if (exposed == null) {
                    exposed = gbCodeGenerator.nextExposedChannelCode(res.getDeviceId(), res.getChannelId());
                }
            }
            toInsert.add(newShare(parentPlatformId, groupId, res, exposed));
        }
        if (!toInsert.isEmpty()) {
            Db.saveBatch(toInsert);
            // 下发 ADD（一次批量、同一任务内串行）：先分组链（根 → 叶子），再通道。
            // 分组链每次都整条补发，不按“该组是否已有挂载”推断：本地无法可靠判断上级是否收到过
            // （UDP 丢包 / 上级重建目录 / 早期只推过全量），而 NOTIFY 是增量 upsert，重复 ADD 幂等。
            // 宁可多推一个分组，也不能让通道的 ParentID 指向上级未知节点。
            List<GbDeviceChannel> addItems = new ArrayList<>(toInsert.size() + 4);
            addItems.addAll(loadGroupChainItems(group));
            for (GbPlatformShare s : toInsert) {
                GbDeviceChannel c = resourceByKey.get(channelKey(s.getDeviceId(), s.getChannelId()));
                if (c == null) {
                    continue;
                }
                // 与卸载/全量推送口径一致：ParentID = 所在分组 GB 码，上报码用挂载时确定的 exposed_code
                c.setParentId(group.getCode());
                c.setExposedCode(s.getExposedCode());
                addItems.add(c);
            }
            // 厂商/型号/归属/IP 通常只在设备表维护，回填后才能与全量推送的 Item 一致
            fillChannelMetaBatch(addItems);
            int groupCount = addItems.size() - toInsert.size();
            sendAddEvents(parentPlatformId, addItems, "分组:" + group.getCode() + (groupCount > 0 ? "(含父链 " + groupCount + " 个分组)" : ""));
            eventPublisher.publishEvent(new ShareChangedEvent(this, parentPlatformId));
        }
        log.info("[{}] 批量挂载 parentPlatformId={} groupId={} groupName={} 输入={} 新增={} 撞码={}（复用旧码={}） 已挂本组跳过={}",
                LOG_TAG, parentPlatformId, groupId, group.getName(), resources.size(), toInsert.size(),
                needExposedKeys.size(), exposedByKey.size(), alreadySharedInGroup.size());
        return toInsert.size();
    }

    @Override
    @Transactional
    public boolean removeShare(Long parentPlatformId, Long groupId, Long resourceId) {
        requireOwnedGroup(parentPlatformId, groupId);
        // 入参是通道主键（UI 当前列表里的），先解析成业务键——share 表只认 (device_id, channel_id)
        GbDeviceChannel channel = resourceId == null ? null : channelMapper.selectById(resourceId);
        if (channel == null) {
            throw new com.solo.common.ServiceException("通道不存在，无法卸载");
        }
        // 先取待撤销节点再执行删除：删完 share 行就构造不出 DEL 报文了
        List<GbDeviceChannel> revoke = loadRevokeItems(parentPlatformId, groupId, List.of(channel));
        int n = shareMapper.delete(new LambdaQueryWrapper<GbPlatformShare>()
                .eq(GbPlatformShare::getParentPlatformId, parentPlatformId)
                .eq(GbPlatformShare::getGroupId, groupId)
                .eq(GbPlatformShare::getDeviceId, channel.getDeviceId())
                .eq(GbPlatformShare::getChannelId, channel.getChannelId()));
        if (n > 0) {
            log.info("[{}] 卸载 parentPlatformId={} groupId={} resourceId={} deviceId={} channelId={}",
                    LOG_TAG, parentPlatformId, groupId, resourceId,
                    channel.getDeviceId(), channel.getChannelId());
            // 上级只对显式 DEL 做删除，仅重推全量快照会让节点永远留在上级目录里
            sendRevokeEvents(parentPlatformId, revoke);
            eventPublisher.publishEvent(new ShareChangedEvent(this, parentPlatformId));
        }
        return n > 0;
    }

    @Override
    @Transactional
    public int clearShares(Long parentPlatformId, Long groupId) {
        GbPlatformGroup group = requireOwnedGroup(parentPlatformId, groupId);
        List<GbDeviceChannel> revoke = loadRevokeItems(parentPlatformId, groupId, null);
        int n = shareMapper.delete(new LambdaQueryWrapper<GbPlatformShare>()
                .eq(GbPlatformShare::getParentPlatformId, parentPlatformId)
                .eq(GbPlatformShare::getGroupId, groupId));
        if (n > 0) {
            sendRevokeEvents(parentPlatformId, revoke);
            eventPublisher.publishEvent(new ShareChangedEvent(this, parentPlatformId));
        }
        log.info("[{}] 清除组内挂载 parentPlatformId={} groupId={} groupName={} 删除条数={}",
                LOG_TAG, parentPlatformId, groupId, group.getName(), n);
        return n;
    }

    @Override
    @Transactional
    public int clearAllSharesByPlatform(Long parentPlatformId) {
        List<GbDeviceChannel> revoke = loadRevokeItems(parentPlatformId, null, null);
        int n = shareMapper.delete(new LambdaQueryWrapper<GbPlatformShare>()
                .eq(GbPlatformShare::getParentPlatformId, parentPlatformId));
        if (n > 0) {
            sendRevokeEvents(parentPlatformId, revoke);
            eventPublisher.publishEvent(new ShareChangedEvent(this, parentPlatformId));
        }
        log.info("[{}] 清除该平台全部分组挂载 parentPlatformId={} 删除条数={}", LOG_TAG, parentPlatformId, n);
        return n;
    }

    @Override
    @Transactional
    public int addAllUnmounted(Long parentPlatformId, Long groupId) {
        // 列出"未挂载"（不在该平台任何 share 里）的所有通道 id——同组/其他组已挂的不算
        List<Long> allUnmounted = shareMapper.selectUnmountedByGroupAllIds(parentPlatformId, groupId);
        if (allUnmounted.isEmpty()) {
            log.info("[{}] 一键添加：无未挂载通道 parentPlatformId={} groupId={}", LOG_TAG, parentPlatformId, groupId);
            return 0;
        }
        int n = addShares(parentPlatformId, groupId, allUnmounted);
        log.info("[{}] 一键添加完成 parentPlatformId={} groupId={} 候选={} 新增={}",
                LOG_TAG, parentPlatformId, groupId, allUnmounted.size(), n);
        return n;
    }

    // ==================== helpers ====================

    /**
     * 构造「删分组」要撤销的节点序列：组内挂载通道（在前）+ 分组自身（最后）。
     *
     * <p>必须在删 share / 删分组之前调用。顺序就是发送顺序：先撤通道再撤分组，
     * 否则通道会变成指向已删除父节点的孤儿，部分上级会直接忽略。</p>
     */
    @Override
    public List<GbDeviceChannel> loadGroupRevokeItems(Long parentPlatformId, Long groupId) {
        GbPlatformGroup group = requireOwnedGroup(parentPlatformId, groupId);
        List<GbDeviceChannel> items = new ArrayList<>(loadRevokeItems(parentPlatformId, groupId, null));
        items.add(toGroupItem(group));
        return items;
    }

    /**
     * 分组链节点（根 → 叶子，含 group 自身），用于“首次挂到该分组”时先补齐上级的目录节点。
     *
     * <p>上级只认已经推过的节点：通道的 ParentID 指向一个它从未见过的分组时，多数实现会把
     * 该通道当孤儿条目丢弃。父链上已推过的分组重复 ADD 是幂等的（upsert），不必逐级判断。</p>
     */
    private List<GbDeviceChannel> loadGroupChainItems(GbPlatformGroup group) {
        List<GbPlatformGroup> chain = new ArrayList<>(4);
        GbPlatformGroup cursor = group;
        int guard = 0;
        while (cursor != null && guard++ < 32) {
            chain.add(cursor);
            Long pid = cursor.getParentId();
            if (pid == null || pid == 0L) {
                break;
            }
            cursor = groupMapper.selectById(pid);
        }
        // 上溯得到的是叶子 → 根，倒序输出成根 → 叶子
        List<GbDeviceChannel> items = new ArrayList<>(chain.size());
        for (int i = chain.size() - 1; i >= 0; i--) {
            items.add(toGroupItem(chain.get(i)));
        }
        return items;
    }

    /** 批量回填通道的厂商/型号/归属/IP（与全量推送口径一致；分组节点没有 deviceId，自动跳过） */
    private void fillChannelMetaBatch(List<GbDeviceChannel> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Set<String> deviceGbIds = items.stream()
                .map(GbDeviceChannel::getDeviceId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (deviceGbIds.isEmpty()) {
            return;
        }
        Map<String, GbDevice> deviceByGbId = deviceMapper.selectList(new LambdaQueryWrapper<GbDevice>()
                        .in(GbDevice::getDeviceId, deviceGbIds)).stream()
                .collect(Collectors.toMap(GbDevice::getDeviceId, d -> d, (a, b) -> a));
        for (GbDeviceChannel c : items) {
            fillChannelMeta(c, deviceByGbId.get(c.getDeviceId()));
        }
    }

    /** 分组自身的目录节点；字段口径与 loadSharedCatalogItems 里的分组节点一致 */
    private GbDeviceChannel toGroupItem(GbPlatformGroup group) {
        GbDeviceChannel node = new GbDeviceChannel();
        node.setChannelId(group.getCode());
        node.setName(group.getName());
        node.setParental(1);
        node.setStatus("ON");
        node.setParentId(parentGroupCode(group));
        if (typeCode(group.getCode()) == TYPE_VIRTUAL_ORG) {
            node.setBusinessGroupId(findBusinessGroupCodeByChain(group));
        }
        return node;
    }

    /** 父分组 GB 码；无父或父已不存在时返回 "0"，由报文层兜底为本平台编码 */
    private String parentGroupCode(GbPlatformGroup group) {
        Long pid = group.getParentId();
        if (pid == null || pid == 0L) {
            return "0";
        }
        GbPlatformGroup parent = groupMapper.selectById(pid);
        return parent == null || parent.getCode() == null ? "0" : parent.getCode();
    }

    /** 沿父链上溯找第一个 215 业务分组编码（216 节点必须带 BusinessGroupID） */
    private String findBusinessGroupCodeByChain(GbPlatformGroup group) {
        Long pid = group.getParentId();
        int guard = 0;
        while (pid != null && pid != 0L && guard++ < 32) {
            GbPlatformGroup p = groupMapper.selectById(pid);
            if (p == null) {
                break;
            }
            if (typeCode(p.getCode()) == TYPE_BUSINESS_GROUP) {
                return p.getCode();
            }
            pid = p.getParentId();
        }
        return null;
    }

    /**
     * 取「即将被撤销」的挂载节点，供下发 {@code Event=DEL} 用。
     *
     * <p><b>必须在删除 share 行之前调用</b>：删完 share 行，拿不到通道编码/名称/父分组，
     * DEL 报文就构造不出来了。上级对 Catalog NOTIFY 是增量 upsert 语义，不发 DEL 的话
     * 目录里的旧节点永远不会消失。</p>
     *
     * @param groupId     限某分组；null = 不限
     * @param channels    限某几条通道（按业务键匹配）；null/空 = 不限
     */
    private List<GbDeviceChannel> loadRevokeItems(Long parentPlatformId, Long groupId, List<GbDeviceChannel> channels) {
        return doLoadChannelItems(parentPlatformId, groupId == null ? null : List.of(groupId), channels);
    }

    /**
     * 按分组集合取挂载通道节点；{@code groupIdSet} 为 null/空 时不限分组（清空平台用）。
     * 分组改码时需要一次性拿整棵子树下的通道。
     */
    private List<GbDeviceChannel> doLoadChannelItems(Long parentPlatformId, List<Long> groupIdSet, List<GbDeviceChannel> channels) {
        LambdaQueryWrapper<GbPlatformShare> w = new LambdaQueryWrapper<GbPlatformShare>()
                .eq(GbPlatformShare::getParentPlatformId, parentPlatformId);
        if (groupIdSet != null && !groupIdSet.isEmpty()) {
            w.in(GbPlatformShare::getGroupId, groupIdSet);
        }
        List<GbPlatformShare> shares = shareMapper.selectList(w);
        if (shares.isEmpty()) {
            return List.of();
        }
        Map<GbPlatformShare, GbDeviceChannel> channelByShare = resolveChannels(shares);
        Set<String> onlyKeys = channels == null || channels.isEmpty()
                ? Set.of()
                : channels.stream().map(CatalogShareServiceImpl::channelKey).collect(Collectors.toSet());
        Set<Long> groupIds = shares.stream().map(GbPlatformShare::getGroupId).collect(Collectors.toSet());
        Map<Long, String> codeByGroupId = groupMapper.selectBatchIds(groupIds).stream()
                .collect(Collectors.toMap(GbPlatformGroup::getId, GbPlatformGroup::getCode, (a, b) -> a));
        List<GbDeviceChannel> items = new ArrayList<>(shares.size());
        for (GbPlatformShare s : shares) {
            GbDeviceChannel c = channelByShare.get(s);
            if (c == null) {
                continue;
            }
            if (!onlyKeys.isEmpty() && !onlyKeys.contains(channelKey(c))) {
                continue;
            }
            // 与 loadSharedCatalogItems 保持一致：通道的 ParentID = 所在分组的 GB 码，
            // 上报码优先用挂载时确定的 exposed_code
            c.setParentId(codeByGroupId.getOrDefault(s.getGroupId(), "0"));
            c.setExposedCode(s.getExposedCode());
            items.add(c);
        }
        return items;
    }

    /** 按入参顺序下发撤销事件（{@code Event=DEL}）；平台不存在或未启用时只告警不发送 */
    @Override
    public void sendRevokeEvents(Long parentPlatformId, List<GbDeviceChannel> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        GbParentPlatform platform = gbParentPlatformService.getById(parentPlatformId);
        if (platform == null || !Integer.valueOf(1).equals(platform.getEnabled())) {
            log.warn("[{}] 撤销事件未发送：上级平台不存在或未启用 parentPlatformId={} 节点:{}",
                    LOG_TAG, parentPlatformId, items.size());
            return;
        }
        // 整批串行发送：删除必须严格「叶子 → 根」，逐条丢线程池会打乱顺序
        platformCommander.sendCatalogEventNotifyBatch(platform, properties.getId(), items, "DEL");
        log.info("[{}] 撤销事件下发 parentPlatformId={} 节点:{}", LOG_TAG, parentPlatformId, items.size());
    }

    @Override
    public void notifyGroupUpdated(Long parentPlatformId, GbPlatformGroup before, GbPlatformGroup after) {
        if (before == null || after == null) {
            return;
        }
        boolean nameChanged = !Objects.equals(before.getName(), after.getName());
        boolean codeChanged = !Objects.equals(before.getCode(), after.getCode());
        boolean parentChanged = !Objects.equals(before.getParentId(), after.getParentId());
        if (!nameChanged && !codeChanged && !parentChanged) {
            return;
        }
        GbParentPlatform platform = gbParentPlatformService.getById(parentPlatformId);
        if (platform == null || !Integer.valueOf(1).equals(platform.getEnabled())) {
            log.warn("[{}] 分组变更未推送：上级平台不存在或未启用 parentPlatformId={} 分组:{}",
                    LOG_TAG, parentPlatformId, after.getCode());
            return;
        }

        // 仅改名：上级按 DeviceID 定位节点、ParentID 未变，一包 UPDATE 即可
        if (!codeChanged && !parentChanged) {
            platformCommander.sendCatalogEventSequence(platform, properties.getId(),
                    List.of(new PlatformCommander.CatalogEvent(toGroupItem(after), "UPDATE")));
            log.info("[{}] 分组改名 UPDATE parentPlatformId={} 编码:{} 新名称:{}",
                    LOG_TAG, parentPlatformId, after.getCode(), after.getName());
            return;
        }

        // 换父 / 改码：先撤旧位置，再按新父链挂回来。改码等于换 DeviceID，
        // 旧 code 下的子分组与通道会成孤儿，必须一并撤掉后用新 code 重发。
        String oldCode = before.getCode();
        String newCode = after.getCode();
        List<GbPlatformGroup> descendants = codeChanged
                ? loadDescendants(parentPlatformId, after.getId()) : List.of();
        List<Long> subtreeGroupIds = new ArrayList<>(descendants.size() + 1);
        subtreeGroupIds.add(after.getId());
        for (GbPlatformGroup d : descendants) {
            subtreeGroupIds.add(d.getId());
        }

        List<PlatformCommander.CatalogEvent> seq = new ArrayList<>();
        if (codeChanged) {
            // 叶子 → 根：先通道（直挂本组的那批 ParentID 要用旧 code），再由深到浅撤子分组。
            // 必须用副本：本方法后面还要再查一次同一批通道做 ADD，MyBatis 一级缓存在同一事务内
            // 返回的是同一批对象引用，会把这里改过的 ParentID 覆盖回新 code，DEL 报文就带错父码。
            for (GbDeviceChannel c : doLoadChannelItems(parentPlatformId, subtreeGroupIds, null)) {
                GbDeviceChannel del = copyChannel(c);
                if (newCode != null && newCode.equals(del.getParentId())) {
                    del.setParentId(oldCode);
                }
                seq.add(new PlatformCommander.CatalogEvent(del, "DEL"));
            }
            for (int i = descendants.size() - 1; i >= 0; i--) {
                GbPlatformGroup d = descendants.get(i);
                GbDeviceChannel node = toGroupItem(d);
                // 直接子级的父码已随本次改码变化，DEL 要定位到旧 code 下
                if (Objects.equals(d.getParentId(), after.getId())) {
                    node.setParentId(oldCode);
                }
                seq.add(new PlatformCommander.CatalogEvent(node, "DEL"));
            }
        }
        seq.add(new PlatformCommander.CatalogEvent(toGroupItem(before), "DEL"));

        // 根 → 叶：新父链（含 after 自身），上级缺哪个分组就补哪个
        for (GbDeviceChannel g : loadGroupChainItems(after)) {
            seq.add(new PlatformCommander.CatalogEvent(g, "ADD"));
        }
        if (codeChanged) {
            // 子节点与通道的 ParentID 都指向新 code，按「浅 → 深 → 通道」重发
            for (GbPlatformGroup d : descendants) {
                seq.add(new PlatformCommander.CatalogEvent(toGroupItem(d), "ADD"));
            }
            List<GbDeviceChannel> addChannels = doLoadChannelItems(parentPlatformId, subtreeGroupIds, null);
            fillChannelMetaBatch(addChannels);
            for (GbDeviceChannel c : addChannels) {
                seq.add(new PlatformCommander.CatalogEvent(c, "ADD"));
            }
        }

        platformCommander.sendCatalogEventSequence(platform, properties.getId(), seq);
        log.info("[{}] 分组变更推送 parentPlatformId={} {} → {} 换父:{} 改码:{} 事件数:{}",
                LOG_TAG, parentPlatformId, oldCode, newCode, parentChanged, codeChanged, seq.size());
    }

    /** 浅拷贝通道节点：事件序列里同一通道可能先 DEL 再 ADD，两份不能共用同一对象 */
    private static GbDeviceChannel copyChannel(GbDeviceChannel src) {
        GbDeviceChannel copy = new GbDeviceChannel();
        BeanUtils.copyProperties(src, copy);
        return copy;
    }

    /** 取某分组的全部后代（BFS，浅 → 深），用于改码时撤销/重发整棵子树 */
    private List<GbPlatformGroup> loadDescendants(Long parentPlatformId, Long rootId) {
        List<GbPlatformGroup> all = groupMapper.selectList(new LambdaQueryWrapper<GbPlatformGroup>()
                .eq(GbPlatformGroup::getParentPlatformId, parentPlatformId));
        Map<Long, List<GbPlatformGroup>> childrenByParent = new HashMap<>();
        for (GbPlatformGroup g : all) {
            Long pid = g.getParentId();
            if (pid == null || pid == 0L) {
                continue;
            }
            childrenByParent.computeIfAbsent(pid, k -> new ArrayList<>()).add(g);
        }
        List<GbPlatformGroup> out = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(rootId);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 10_000) {
            for (GbPlatformGroup child : childrenByParent.getOrDefault(queue.poll(), List.of())) {
                out.add(child);
                queue.add(child.getId());
            }
        }
        return out;
    }

    /** 下发新增事件（{@code Event=ADD}）：入参顺序即发送顺序，分组链必须排在通道之前 */
    private void sendAddEvents(Long parentPlatformId, List<GbDeviceChannel> items, String scene) {
        if (items == null || items.isEmpty()) {
            return;
        }
        GbParentPlatform platform = gbParentPlatformService.getById(parentPlatformId);
        if (platform == null || !Integer.valueOf(1).equals(platform.getEnabled())) {
            log.warn("[{}] 新增事件未发送：上级平台不存在或未启用 parentPlatformId={} 节点:{}",
                    LOG_TAG, parentPlatformId, items.size());
            return;
        }
        platformCommander.sendCatalogEventNotifyBatch(platform, properties.getId(), items, "ADD");
        log.info("[{}] 新增事件下发 parentPlatformId={} {} 节点:{}", LOG_TAG, parentPlatformId, scene, items.size());
    }

    private static String normalize(String keyword) {
        return StringUtils.hasText(keyword) ? keyword.trim() : null;
    }

    /** 业务键：通道的稳定标识（device_id + channel_id；主键会随目录重新同步变化，不能当外键） */
    private static String channelKey(String deviceId, String channelId) {
        return (deviceId == null ? "" : deviceId) + '\u0000' + (channelId == null ? "" : channelId);
    }

    private static String channelKey(GbDeviceChannel c) {
        return channelKey(c.getDeviceId(), c.getChannelId());
    }

    /**
     * 按业务键 (device_id + channel_id) 把 share 行解析成当前通道实体，key = share 对象自身（同一通道可被多个上级共享）。
     *
     * <p>不能按通道主键查：通道重新同步（deleteByDeviceId / 删除失效通道后重报）是物理删行，
     * 主键会变；只有 (device_id, channel_id) 稳定。通道已不存在时该 share 不出现在结果里，
     * 由调用方跳过。</p>
     */
    private Map<GbPlatformShare, GbDeviceChannel> resolveChannels(List<GbPlatformShare> shares) {
        Map<GbPlatformShare, GbDeviceChannel> out = new IdentityHashMap<>();
        if (shares == null || shares.isEmpty()) {
            return out;
        }
        Set<String> deviceIds = new HashSet<>();
        Set<String> channelIds = new HashSet<>();
        for (GbPlatformShare s : shares) {
            deviceIds.add(s.getDeviceId());
            channelIds.add(s.getChannelId());
        }
        Map<String, GbDeviceChannel> byKey = new HashMap<>();
        for (GbDeviceChannel c : channelMapper.selectList(new LambdaQueryWrapper<GbDeviceChannel>()
                .in(GbDeviceChannel::getDeviceId, deviceIds)
                .in(GbDeviceChannel::getChannelId, channelIds))) {
            byKey.putIfAbsent(channelKey(c), c);
        }
        for (GbPlatformShare s : shares) {
            GbDeviceChannel c = byKey.get(channelKey(s.getDeviceId(), s.getChannelId()));
            if (c != null) {
                out.put(s, c);
            }
        }
        return out;
    }

    /**
     * 校验分组存在且属于该 parentPlatformId。抛 ServiceException 让全局异常处理统一翻译。
     */
    private GbPlatformGroup requireOwnedGroup(Long parentPlatformId, Long groupId) {
        GbPlatformGroup g = groupMapper.selectById(groupId);
        if (g == null) {
            throw new com.solo.common.ServiceException("分组不存在");
        }
        if (!parentPlatformId.equals(g.getParentPlatformId())) {
            throw new com.solo.common.ServiceException("分组不属于该上级平台");
        }
        return g;
    }

    private static GbPlatformShare newShare(Long parentPlatformId, Long groupId, GbDeviceChannel channel, String exposedCode) {
        GbPlatformShare row = new GbPlatformShare();
        row.setParentPlatformId(parentPlatformId);
        row.setGroupId(groupId);
        row.setDeviceId(channel.getDeviceId());
        row.setChannelId(channel.getChannelId());
        row.setExposedCode(exposedCode);
        return row;
    }
}
