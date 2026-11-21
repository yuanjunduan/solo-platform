package com.solo.gb28181.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.solo.common.ServiceException;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.constant.GbSource;
import com.solo.gb28181.entity.GbDevice;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.mapper.GbDeviceChannelMapper;
import com.solo.gb28181.mapper.GbDeviceMapper;
import com.solo.gb28181.request.GbChannelSubtreePageRequest;
import com.solo.gb28181.request.GbDeviceChannelPageRequest;
import com.solo.gb28181.response.GbChannelStatusCountVO;
import com.solo.gb28181.response.GbChannelTreeNodeVO;
import com.solo.gb28181.response.GbChannelTreeLevelVO;
import com.solo.gb28181.response.GbDeviceChannelResponse;
import com.solo.gb28181.response.GbTreeCountVO;
import com.solo.gb28181.service.GbDeviceChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class GbDeviceChannelServiceImpl extends ServiceImpl<GbDeviceChannelMapper, GbDeviceChannel>
        implements GbDeviceChannelService {

    private static final int MAX_TREE_DEPTH = 32;

    /** GB/T 28181 根哨兵：<ParentID>0</ParentID> 表无父 */
    private static final String ROOT_PARENT = "0";

    /** 通道分页上限：前端默认 200，再大也没人看 */
    private static final long MAX_SUBTREE_PAGE_SIZE = 500L;

    private final GbDeviceMapper deviceMapper;
    private final Gb28181Properties properties;

    @Override
    public Page<GbDeviceChannelResponse> pageQuery(GbDeviceChannelPageRequest request) {
        LambdaQueryWrapper<GbDeviceChannel> wrapper = new LambdaQueryWrapper<>();
        // 默认展示全部（通道 + 分组）；request.parental 不为空时按类型过滤
        if (request.getParental() != null) {
            wrapper.eq(GbDeviceChannel::getParental, request.getParental());
        }
        if (request.getDeviceId() != null && !request.getDeviceId().isBlank()) {
            wrapper.like(GbDeviceChannel::getDeviceId, request.getDeviceId());
        }
        // 通道页以「下级平台」为第一维度：按上报目录的平台编码精确过滤
        if (request.getPlatformId() != null && !request.getPlatformId().isBlank()) {
            wrapper.eq(GbDeviceChannel::getPlatformId, request.getPlatformId().trim());
        }
        if (request.getChannelId() != null && !request.getChannelId().isBlank()) {
            wrapper.like(GbDeviceChannel::getChannelId, request.getChannelId());
        }
        if (request.getName() != null && !request.getName().isBlank()) {
            wrapper.like(GbDeviceChannel::getName, request.getName());
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            // status 是通道字段，分组固定为 "ON"——按 status 过滤时只看通道行
            wrapper.eq(GbDeviceChannel::getParental, 0)
                    .eq(GbDeviceChannel::getStatus, request.getStatus());
        }
        if (request.getParentId() != null && !request.getParentId().isBlank()) {
            // 分组编码：LIKE 包含匹配（'215' 匹配 '...215...'），按 GB 码任意段模糊
            wrapper.like(GbDeviceChannel::getParentId, request.getParentId());
        }
        if (request.getSource() != null) {
            wrapper.eq(GbDeviceChannel::getSource, request.getSource());
        }
        if (request.getPtzType() != null) {
            // ptzType 是通道字段，分组行该列为 NULL——按类型过滤时只看通道行（同 status 的处理）
            wrapper.eq(GbDeviceChannel::getParental, 0)
                    .eq(GbDeviceChannel::getPtzType, request.getPtzType());
        }
        wrapper.orderByAsc(GbDeviceChannel::getParental)  // 分组（1）排在通道（0）之前
                .orderByAsc(GbDeviceChannel::getDeviceId)
                .orderByAsc(GbDeviceChannel::getChannelId);
        Page<GbDeviceChannel> raw = page(new Page<>(request.getPage(), request.getPageSize()), wrapper);

        // 转 response + 直接透传 entity 字段（不回填任何冗余字段）
        List<GbDeviceChannelResponse> rows = raw.getRecords().stream()
                .map(GbDeviceChannelServiceImpl::toResponse)
                .collect(Collectors.toCollection(ArrayList::new));

        Page<GbDeviceChannelResponse> resp = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        resp.setRecords(rows);
        return resp;
    }

    private static GbDeviceChannelResponse toResponse(GbDeviceChannel src) {
        GbDeviceChannelResponse vo = new GbDeviceChannelResponse();
        BeanUtils.copyProperties(src, vo);
        return vo;
    }

    @Override
    public List<GbChannelStatusCountVO> statusCount(String platformId) {
        return getBaseMapper().selectStatusCount(trimToNull(platformId));
    }

    @Override
    public List<GbChannelTreeNodeVO> treeByDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return Collections.emptyList();
        }
        // 单设备一次取全量（海康侧实测 1024 分组 + 201 通道，量级可控），在内存里按 parentId 拼树
        List<GbDeviceChannel> rows = lambdaQuery()
                .eq(GbDeviceChannel::getDeviceId, deviceId)
                .list();
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, GbChannelTreeNodeVO> byCode = new LinkedHashMap<>(rows.size() * 2);
        for (GbDeviceChannel row : rows) {
            GbChannelTreeNodeVO node = new GbChannelTreeNodeVO();
            BeanUtils.copyProperties(row, node);
            byCode.put(node.getChannelId(), node);
        }
        List<GbChannelTreeNodeVO> roots = new ArrayList<>();
        for (GbChannelTreeNodeVO node : byCode.values()) {
            GbChannelTreeNodeVO parent = node.getParentId() == null ? null : byCode.get(node.getParentId());
            // 父节点缺失（指向设备本身/上级未同步）、自指或父子成环时按根节点处理，保证不丢节点
            if (parent == null || parent == node || isCyclic(byCode, node)) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        sortTree(roots);
        return roots;
    }

    /** 沿 parentId 向上回溯，回到自身说明成环（异常数据兜底，最多 64 跳） */
    private static boolean isCyclic(Map<String, GbChannelTreeNodeVO> byCode, GbChannelTreeNodeVO node) {
        String cursor = node.getParentId();
        for (int hop = 0; cursor != null && hop < 64; hop++) {
            if (cursor.equals(node.getChannelId())) {
                return true;
            }
            GbChannelTreeNodeVO up = byCode.get(cursor);
            cursor = up == null ? null : up.getParentId();
        }
        return false;
    }

    /** 分组在前（parental 1 > 0），同类型按名称、编码升序，递归排序子层 */
    private static void sortTree(List<GbChannelTreeNodeVO> nodes) {
        nodes.sort(Comparator
                .comparingInt((GbChannelTreeNodeVO n) -> n.getParental() == null ? 0 : -n.getParental())
                .thenComparing(n -> n.getName() == null ? "" : n.getName())
                .thenComparing(GbChannelTreeNodeVO::getChannelId));
        for (GbChannelTreeNodeVO node : nodes) {
            if (!node.getChildren().isEmpty()) {
                sortTree(node.getChildren());
            }
        }
    }

    /**
     * 重建组织路径：reset → 根落路径 → 逐层传播（最多 MAX_TREE_DEPTH 轮）→ 孤儿兜底再传播一轮。
     * 全程走 SQL 批量 UPDATE，不对分组做内存递归，十万级分组也只是一百多次小范围更新。
     * 放在一个事务里：重建期间读请求仍看到旧路径（不出现半途为空的窗口），失败整体回滚。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rebuildTreePath(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        long start = System.currentTimeMillis();
        GbDeviceChannelMapper mapper = getBaseMapper();
        mapper.resetTreePath(deviceId);
        int roots = mapper.initRootTreePath(deviceId);
        int rounds = 0;
        for (; rounds < MAX_TREE_DEPTH; rounds++) {
            if (mapper.propagateTreePath(deviceId) == 0) {
                break;
            }
        }
        // 父节点缺失（孤儿）、自指/成环、超 512 字符深度的行：各自成为设备根的下级，再往下传一轮
        int rescued = mapper.rescueOrphanTreePath(deviceId);
        if (rescued > 0) {
            for (int i = 0; i < MAX_TREE_DEPTH; i++) {
                if (mapper.propagateTreePath(deviceId) == 0) {
                    break;
                }
            }
        }
        log.info("[组织路径重建] deviceId={} 根节点={} 传播轮次={} 孤立/超深兜底={} 耗时={}ms",
                deviceId, roots, rounds, rescued, System.currentTimeMillis() - start);
    }

    @Override
    public GbChannelTreeLevelVO treeChildren(String deviceId, String orgCode) {
        GbChannelTreeLevelVO level = new GbChannelTreeLevelVO();
        if (deviceId == null || deviceId.isBlank()) {
            return level;
        }
        String deviceIdTrim = deviceId.trim();
        String code = orgCode == null ? "" : orgCode.trim();
        GbDeviceChannelMapper mapper = getBaseMapper();

        // 设备根：前缀固定 /设备码；选中分组：读它的 tree_path（旧数据/改层级后为空就先补建）
        String prefix;
        if (code.isEmpty()) {
            ensureTreePath(deviceIdTrim);
            prefix = "/" + deviceIdTrim;
        } else {
            prefix = resolveTreePath(deviceIdTrim, code);
            if (prefix == null) {
                // 组织已被删（或不属于该设备），返回空层
                return level;
            }
        }
        level.setOrgCode(code);
        level.setTreePath(prefix);

        List<String> parentIds = code.isEmpty()
                ? Arrays.asList(deviceIdTrim, ROOT_PARENT, "")
                : Collections.singletonList(code);
        List<GbChannelTreeNodeVO> children = mapper.selectChildren(deviceIdTrim, parentIds);

        // 一次聚合扫描：本级子树通道总数 + 每个直接下级分组各自的子树通道数
        Map<String, Long> subtreeByCode = new HashMap<>();
        long subtreeTotal = 0L;
        for (GbTreeCountVO row : mapper.selectSubtreeChannelCount(deviceIdTrim, prefix)) {
            long cnt = row.getCnt() == null ? 0L : row.getCnt();
            if (row.getCode() != null) {
                subtreeByCode.put(row.getCode(), cnt);
            }
            subtreeTotal += cnt;
        }
        level.setSubtreeChannelCount(subtreeTotal);

        List<String> groupCodes = new ArrayList<>();
        for (GbChannelTreeNodeVO node : children) {
            if (node.getParental() != null && node.getParental() == 1) {
                Long cnt = subtreeByCode.get(node.getChannelId());
                node.setSubtreeChannelCount(cnt == null ? 0L : cnt);
                groupCodes.add(node.getChannelId());
            }
        }
        level.setChildGroupCount(groupCodes.size());
        level.setChildChannelCount(children.size() - groupCodes.size());

        // 子分组是否还有直接下级（决定展开箭头），一次 IN 分组统计
        if (!groupCodes.isEmpty()) {
            Map<String, Long> directByCode = new HashMap<>();
            for (GbTreeCountVO row : mapper.selectChildCountByParent(deviceIdTrim, groupCodes)) {
                if (row.getCode() != null && row.getCnt() != null) {
                    directByCode.put(row.getCode(), row.getCnt());
                }
            }
            for (GbChannelTreeNodeVO node : children) {
                if (node.getParental() != null && node.getParental() == 1) {
                    node.setHasChildren(directByCode.getOrDefault(node.getChannelId(), 0L) > 0);
                }
            }
        }
        level.setChildren(children);
        return level;
    }

    @Override
    public Page<GbDeviceChannelResponse> subtreePage(GbChannelSubtreePageRequest request) {
        if (request.getDeviceId() == null || request.getDeviceId().isBlank()) {
            throw new ServiceException("所属设备不能为空");
        }
        String deviceId = request.getDeviceId().trim();
        String code = request.getOrgCode() == null ? "" : request.getOrgCode().trim();
        boolean includeSubtree = Boolean.TRUE.equals(request.getIncludeSubtree());
        long current = request.getPage() == null || request.getPage() < 1 ? 1L : request.getPage();
        long size = request.getPageSize() == null || request.getPageSize() < 1
                ? 200L : Math.min(request.getPageSize(), MAX_SUBTREE_PAGE_SIZE);
        Page<GbDeviceChannelResponse> page = new Page<>(current, size);

        String prefix = null;
        if (includeSubtree) {
            // 整棵子树靠 tree_path 前缀一次区间扫描，不递归、不 IN 大列表
            ensureTreePath(deviceId);
            prefix = code.isEmpty() ? "/" + deviceId : resolveTreePath(deviceId, code);
            if (prefix == null) {
                page.setTotal(0L);
                return page;
            }
        }
        // includeSubtree=false 只取直接挂在该组织（或设备根）下的通道
        String parentId = code.isEmpty() ? deviceId : code;
        return getBaseMapper().selectSubtreePage(page, deviceId, includeSubtree, prefix, parentId,
                trimToNull(request.getKeyword()), trimToNull(request.getStatus()));
    }

    /** 旧数据/首次访问：路径全空时补建一次，避免上线后必须先等一次目录同步 */
    private void ensureTreePath(String deviceId) {
        try {
            if (getBaseMapper().countTreePath(deviceId) > 0) {
                return;
            }
            if (lambdaQuery().eq(GbDeviceChannel::getDeviceId, deviceId).exists()) {
                log.info("[组织路径] deviceId={} 检出未建路径的数据，首次访问触发补建", deviceId);
                rebuildTreePath(deviceId);
            }
        } catch (Exception e) {
            log.warn("[组织路径] deviceId={} 补建失败: {}", deviceId, e.getMessage());
        }
    }

    /**
     * 取组织路径；返回 null 表示该码在本设备下不存在，空串表示行存在但路径未建（已尝试补建）。
     */
    private String resolveTreePath(String deviceId, String code) {
        String path = selectTreePath(deviceId, code);
        if (path == null || !path.isBlank()) {
            return path;
        }
        rebuildTreePath(deviceId);
        return selectTreePath(deviceId, code);
    }

    private String selectTreePath(String deviceId, String code) {
        GbDeviceChannel row = lambdaQuery()
                .select(GbDeviceChannel::getTreePath)
                .eq(GbDeviceChannel::getDeviceId, deviceId)
                .eq(GbDeviceChannel::getChannelId, code)
                .one();
        return row == null ? null : (row.getTreePath() == null ? "" : row.getTreePath());
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @Override
    public void createByManual(GbDeviceChannel channel) {
        if (channel.getDeviceId() == null || channel.getDeviceId().isBlank()) {
            throw new ServiceException("所属设备不能为空");
        }
        if (channel.getChannelId() == null || channel.getChannelId().isBlank()) {
            throw new ServiceException("通道国标ID不能为空");
        }
        GbDevice device = deviceMapper.selectOne(
                new LambdaQueryWrapper<GbDevice>().eq(GbDevice::getDeviceId, channel.getDeviceId()));
        if (device == null) {
            throw new ServiceException("所属设备不存在");
        }
        // 通道国标ID在同一所属设备下唯一（跟 uk_device_channel 对齐）。GB/T 28181 语义上
        // 通道号由所属物理设备自身分配，不同厂商 NVR 出现相同末位序号是正常情况。
        boolean dup = lambdaQuery()
                .eq(GbDeviceChannel::getDeviceId, channel.getDeviceId())
                .eq(GbDeviceChannel::getChannelId, channel.getChannelId())
                .exists();
        if (dup) {
            throw new ServiceException("通道国标ID已存在：" + channel.getChannelId());
        }
        // 回填冗余字段，状态与来源由系统决定
        channel.setId(null);
        channel.setParental(0);  // 手动新增一定是通道
        channel.setStatus(GbSource.CHANNEL_STATUS_UNREGISTERED);
        channel.setSource(GbSource.SYSTEM);
        // 来源平台：手工新增设备本身不存在下级平台，与 GbDeviceServiceImpl 手工建设备路径
        // 保持一致记为设备自身，否则 platform_id 落空串会导致按平台筛选/统计时看不见该通道
        channel.setPlatformId(device.getDeviceId());
        // parentId = 所属设备 GB 码：与 GB/T 28181 语义一致（NVR/IPC 推 Catalog 时通道
        // <ParentID> 就是设备自己的 DeviceID），也与 CatalogSyncSupport 孤儿回落规则统一
        channel.setParentId(device.getDeviceId());
        // 经纬度原样存取，不做坐标系转换
        save(channel);
        log.info("[通道新增] 系统手动新增通道: device={} channel={}", device.getDeviceId(), channel.getChannelId());
    }

    @Override
    public void updateByManual(GbDeviceChannel channel) {
        GbDeviceChannel exist = getById(channel.getId());
        if (exist == null) {
            throw new ServiceException("通道不存在");
        }
        // 只更新可编辑资料字段，绝不触碰 status/source 及设备归属关系
        // parentId 不接受外部修改——手动新增时已绑定设备 GB 码，运行期不变
        exist.setName(channel.getName());
        exist.setManufacturer(channel.getManufacturer());
        exist.setModel(channel.getModel());
        exist.setOwner(channel.getOwner());
        exist.setCivilCode(channel.getCivilCode());
        exist.setBlock(channel.getBlock());
        exist.setAddress(channel.getAddress());
        exist.setPtzType(channel.getPtzType());
        // 经纬度原样存取，不做坐标系转换；显式置空时也清空
        exist.setLongitude(channel.getLongitude());
        exist.setLatitude(channel.getLatitude());
        updateById(exist);
        log.info("[通道编辑] 更新通道资料: {}", exist.getChannelId());
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        GbDeviceChannel channel = getById(id);
        if (channel == null) {
            return;
        }
        removeById(id);
        log.info("[通道删除] id={}", id);
    }

    @Override
    public void saveOrUpdateBatch(List<GbDeviceChannel> channels) {
        if (channels == null || channels.isEmpty()) return;
        // 业务预处理：原始名/默认名 + 预填雪花 id
        // parentId 不在此处兜底——保持 null（手动新增）或 Catalog 同步传入的值（XML <ParentID>）
        // 经纬度原样入库：设备 / 下级平台上报什么就存什么，不做坐标系转换
        for (GbDeviceChannel ch : channels) {
            String reportedName = ch.getName();
            if (reportedName != null && !reportedName.isBlank()) {
                ch.setReportName(reportedName);
                if (ch.getName() == null || ch.getName().isBlank()) {
                    ch.setName(reportedName);
                }
            }
            if (ch.getId() == null) {
                ch.setId(IdWorker.getId());
            }
        }
        // 走 mapper 的批量 upsert（MySQL / PG 各自方言，批内重复键先合并）：单 SQL 多行
        // 4 万通道场景必须用批量——逐行 4 万次网络往返 = 4-20 秒，生产不可接受
        // 条件更新在 SQL 端用 COALESCE 实现，行为等价于 MyBatis <if>，但单 SQL 内执行
        // 业务唯一键 uk_device_channel (device_id, channel_id) 同时覆盖通道和分组：
        //   - 通道：设备内 channel_id 唯一
        //   - 分组：device_id 标识推送源设备，同设备同 GB 码的分组自动 upsert
        int batchSize = Math.max(1, properties.getCatalog().getBatchSize());
        int total = channels.size();
        long start = System.currentTimeMillis();
        for (int i = 0; i < total; i += batchSize) {
            int end = Math.min(i + batchSize, total);
            baseMapper.batchUpsert(channels.subList(i, end));
        }
        long cost = System.currentTimeMillis() - start;
        log.info("[通道目录] 批量 upsert 完成 device={} rows={} batches={} costMs={}",
                channels.getFirst().getDeviceId(), total, (total + batchSize - 1) / batchSize, cost);
    }

    @Override
    @Transactional
    public void deleteByDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        lambdaUpdate()
                .eq(GbDeviceChannel::getDeviceId, deviceId)
                .remove();
    }

    @Override
    @Transactional
    public void deleteBatch(List<Long> ids) {
        // 收集所有待删除通道所属的 deviceId
        List<GbDeviceChannel> channels = listByIds(ids);
        if (channels.isEmpty()) {
            return;
        }
        // 批量删除
        removeByIds(ids);
        log.info("[通道批量删除] 删除 {} 条通道", ids.size());
    }

}
