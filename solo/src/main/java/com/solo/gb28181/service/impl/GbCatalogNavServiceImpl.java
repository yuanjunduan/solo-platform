package com.solo.gb28181.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solo.common.ServiceException;
import com.solo.gb28181.constant.GbCascadeDirection;
import com.solo.gb28181.constant.GbDomain;
import com.solo.gb28181.entity.GbDevice;
import com.solo.gb28181.entity.GbOrg;
import com.solo.gb28181.entity.GbPlatformRegistry;
import com.solo.gb28181.mapper.GbDeviceMapper;
import com.solo.gb28181.mapper.GbOrgMapper;
import com.solo.gb28181.request.GbCatalogNavRequest;
import com.solo.gb28181.response.GbCatalogNavNodeVO;
import com.solo.gb28181.response.GbCatalogNavVO;
import com.solo.gb28181.response.GbChannelTreeLevelVO;
import com.solo.gb28181.response.GbChannelTreeNodeVO;
import com.solo.gb28181.response.GbNavCountVO;
import com.solo.gb28181.service.GbCatalogNavService;
import com.solo.gb28181.service.GbDeviceChannelService;
import com.solo.gb28181.service.GbPlatformRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 左树懒加载实现。
 *
 * <p>关键取舍：节点 key 自带数据源前缀（org/plat/grp），展开时不需要前端额外告诉后端
 * "这是组织还是平台"，避免两套参数组合出非法状态；每层最多 3 条 SQL
 * （本层列表 + 子节点计数 + 直属设备计数），不做全表内递归。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbCatalogNavServiceImpl implements GbCatalogNavService {

    /** 三域之外的"全部"：只作为查询语义，不落库、不进 gb_device.domain */
    public static final String DOMAIN_ALL = "ALL";

    private static final String NODE_ORG = "ORG";
    private static final String NODE_PLATFORM = "PLATFORM";
    private static final String NODE_GROUP = "GROUP";

    private static final String KEY_ROOT = "root";
    private static final String KEY_ORG = "org:";
    private static final String KEY_PLATFORM = "plat:";
    private static final String KEY_GROUP = "grp:";

    /** gb_org 根节点的 parent_id 约定为 0（历史数据可能是 NULL） */
    private static final long ROOT_ORG_PARENT = 0L;

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;

    private final GbOrgMapper orgMapper;
    private final GbDeviceMapper deviceMapper;
    private final GbPlatformRegistryService platformRegistryService;
    private final GbDeviceChannelService channelService;

    @Override
    public GbCatalogNavVO nav(GbCatalogNavRequest request) {
        String domain = normalizeDomain(request == null ? null : request.getDomain());
        String parentKey = trimToNull(request == null ? null : request.getParentKey());
        String keyword = trimToNull(request == null ? null : request.getKeyword());
        int limit = clampLimit(request == null ? null : request.getLimit());

        GbCatalogNavVO level = new GbCatalogNavVO();
        level.setDomain(domain);
        level.setParentKey(parentKey == null ? KEY_ROOT : parentKey);
        level.setLimit(limit);
        if (GbDomain.REMOTE.equals(domain)) {
            // 第三方共享资源（HTTP 拉取）本轮未接入：返回空层让前端走空态，而不是报错
            return level;
        }

        List<GbCatalogNavNodeVO> children = parentKey == null
                ? rootLevel(domain, keyword, limit)
                : expandLevel(parentKey, keyword, limit);
        if (children.size() > limit) {
            level.setHasMore(true);
            level.setChildren(new ArrayList<>(children.subList(0, limit)));
        } else {
            level.setChildren(children);
        }
        return level;
    }

    /** 根层：按域决定数据源；ALL 域把本地组织根与各平台并列 */
    private List<GbCatalogNavNodeVO> rootLevel(String domain, String keyword, int limit) {
        List<GbCatalogNavNodeVO> nodes = new ArrayList<>();
        if (DOMAIN_ALL.equals(domain) || GbDomain.LOCAL.equals(domain)) {
            nodes.addAll(orgLevel(null, keyword, limit + 1));
        }
        if (DOMAIN_ALL.equals(domain) || GbDomain.SUBORDINATE.equals(domain)) {
            nodes.addAll(platformLevel(domain, keyword));
        }
        return nodes;
    }

    /** 按 key 前缀分派到对应数据源；未知 key 返回空层（前端换域后残留的旧 key 不应报错） */
    private List<GbCatalogNavNodeVO> expandLevel(String parentKey, String keyword, int limit) {
        if (parentKey.startsWith(KEY_ORG)) {
            Long orgId = parseOrgId(parentKey);
            return orgId == null ? new ArrayList<>() : orgLevel(orgId, keyword, limit + 1);
        }
        if (parentKey.startsWith(KEY_PLATFORM)) {
            String gbId = parentKey.substring(KEY_PLATFORM.length()).trim();
            return gbId.isEmpty() ? new ArrayList<>() : groupLevel(gbId, null, keyword, limit + 1);
        }
        if (parentKey.startsWith(KEY_GROUP)) {
            String rest = parentKey.substring(KEY_GROUP.length());
            int sep = rest.indexOf(':');
            if (sep <= 0 || sep == rest.length() - 1) {
                return new ArrayList<>();
            }
            return groupLevel(rest.substring(0, sep), rest.substring(sep + 1), keyword, limit + 1);
        }
        log.debug("[目录左树] 未知节点 key={}", parentKey);
        return new ArrayList<>();
    }

    /**
     * 本地组织一层。
     *
     * <p>展开箭头只看"有没有子组织"，直属设备数单独给计数徽标：组织下没有子组织时
     * 树的这一支就到头了，设备/通道由右侧列表展示。</p>
     */
    private List<GbCatalogNavNodeVO> orgLevel(Long parentOrgId, String keyword, int fetch) {
        LambdaQueryWrapper<GbOrg> wrapper = new LambdaQueryWrapper<GbOrg>()
                .select(GbOrg::getId, GbOrg::getParentId, GbOrg::getLevel, GbOrg::getName, GbOrg::getSort)
                .orderByAsc(GbOrg::getSort)
                .orderByAsc(GbOrg::getId)
                .last("LIMIT " + fetch);
        if (parentOrgId == null) {
            wrapper.and(w -> w.isNull(GbOrg::getParentId).or().eq(GbOrg::getParentId, ROOT_ORG_PARENT));
        } else {
            wrapper.eq(GbOrg::getParentId, parentOrgId);
        }
        if (keyword != null) {
            wrapper.like(GbOrg::getName, keyword);
        }
        List<GbOrg> rows = orgMapper.selectList(wrapper);
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> ids = rows.stream().map(GbOrg::getId).toList();
        Map<Long, Long> childCounts = toCountMap(orgMapper.selectChildCountByParentIds(ids));
        Map<Long, Long> deviceCounts = toCountMap(deviceMapper.selectLocalDeviceCountByOrgIds(ids));

        List<GbCatalogNavNodeVO> nodes = new ArrayList<>(rows.size());
        String parentKey = parentOrgId == null ? KEY_ROOT : KEY_ORG + parentOrgId;
        for (GbOrg org : rows) {
            GbCatalogNavNodeVO node = new GbCatalogNavNodeVO();
            node.setKey(KEY_ORG + org.getId());
            node.setParentKey(parentKey);
            node.setNodeType(NODE_ORG);
            node.setLabel(isBlank(org.getName()) ? String.valueOf(org.getId()) : org.getName());
            node.setDomain(GbDomain.LOCAL);
            node.setOrgId(org.getId());
            node.setHasChildren(childCounts.getOrDefault(org.getId(), 0L) > 0);
            node.setDirectDeviceCount(deviceCounts.getOrDefault(org.getId(), 0L));
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * 端点根层：只读登记簿，不查目录表。
     *
     * <p>登记簿由注册/目录同步钩子维护，保证"没推过目录但在线的端点"也能出现在左树，
     * 用户点进去看到空列表，比在树里根本找不到这个端点更容易定位问题。</p>
     *
     * <p>{@code domain=SUBORDINATE} 时只保留真实下级端点：本地接入设备（domain=LOCAL）
     * 属于本地资源，混进"下级"树会让树的通道数与右侧列表口径对不上。</p>
     */
    private List<GbCatalogNavNodeVO> platformLevel(String domain, String keyword) {
        List<GbPlatformRegistry> rows = platformRegistryService.listByDirection(GbCascadeDirection.DOWNSTREAM);
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> domains = resolveEndpointDomains(rows);
        List<GbCatalogNavNodeVO> nodes = new ArrayList<>(rows.size());
        for (GbPlatformRegistry row : rows) {
            if (isBlank(row.getPlatformGbId())) {
                continue;
            }
            String label = displayName(row);
            if (keyword != null && !containsIgnoreCase(label, keyword)
                    && !containsIgnoreCase(row.getPlatformGbId(), keyword)) {
                continue;
            }
            // 下行端点既可能是下级平台（SUBORDINATE），也可能是本地接入设备（LOCAL），
            // 域必须取 gb_device 的真实值，否则前端拿它会过滤掉自己的数据
            String endpointDomain = domains.getOrDefault(row.getPlatformGbId(),
                    GbCascadeDirection.isPlatformGbId(row.getPlatformGbId())
                            ? GbDomain.SUBORDINATE : GbDomain.LOCAL);
            if (GbDomain.SUBORDINATE.equals(domain) && !GbDomain.SUBORDINATE.equals(endpointDomain)) {
                continue;
            }
            long groupCount = row.getGroupCount() == null ? 0L : row.getGroupCount();
            GbCatalogNavNodeVO node = new GbCatalogNavNodeVO();
            node.setKey(KEY_PLATFORM + row.getPlatformGbId());
            node.setParentKey(KEY_ROOT);
            node.setNodeType(NODE_PLATFORM);
            node.setLabel(label);
            node.setDomain(endpointDomain);
            node.setPlatformGbId(row.getPlatformGbId());
            node.setKind(row.getKind());
            node.setOnlineStatus(row.getOnlineStatus());
            node.setChildGroupCount(groupCount);
            node.setSubtreeChannelCount(row.getChannelCount() == null ? 0L : row.getChannelCount());
            node.setHasChildren(groupCount > 0);
            nodes.add(node);
        }
        return nodes;
    }

    /** 本层端点的真实资源域：一次 IN 查询，查不到的由编码规则兜底 */
    private Map<String, String> resolveEndpointDomains(List<GbPlatformRegistry> rows) {
        List<String> gbIds = rows.stream()
                .map(GbPlatformRegistry::getPlatformGbId)
                .filter(id -> !isBlank(id))
                .toList();
        if (gbIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new HashMap<>(gbIds.size() * 2);
        for (GbDevice device : deviceMapper.selectList(new LambdaQueryWrapper<GbDevice>()
                .select(GbDevice::getDeviceId, GbDevice::getDomain)
                .in(GbDevice::getDeviceId, gbIds))) {
            if (!isBlank(device.getDomain())) {
                map.put(device.getDeviceId(), device.getDomain());
            }
        }
        return map;
    }

    /**
     * 镜像分组一层：直接复用通道页现成的懒加载逻辑（tree_path 前缀 + 子树通道数 + 展开箭头）。
     *
     * <p>只保留 {@code parental=1} 的分组节点——通道行属于右侧列表的内容，
     * 塞进左树只会让几百路通道把一个分组撑爆，且点击后与右侧列表重复。</p>
     */
    private List<GbCatalogNavNodeVO> groupLevel(String platformGbId, String groupCode,
                                                String keyword, int fetch) {
        GbChannelTreeLevelVO level;
        try {
            level = channelService.treeChildren(platformGbId, groupCode == null ? "" : groupCode);
        } catch (Exception e) {
            // 目录缺失、路径补建失败都不该让整棵树 500：降级为空层，问题留给右侧列表暴露
            log.warn("[目录左树] 展开分组失败 platformGbId={} groupCode={} err={}",
                    platformGbId, groupCode, e.getMessage());
            return new ArrayList<>();
        }
        List<GbCatalogNavNodeVO> nodes = new ArrayList<>();
        for (GbChannelTreeNodeVO child : level.getChildren()) {
            if (child.getParental() == null || child.getParental() != 1) {
                continue;
            }
            String label = isBlank(child.getName()) ? child.getChannelId() : child.getName();
            if (keyword != null && !containsIgnoreCase(label, keyword)
                    && !containsIgnoreCase(child.getChannelId(), keyword)) {
                continue;
            }
            GbCatalogNavNodeVO node = new GbCatalogNavNodeVO();
            node.setKey(KEY_GROUP + platformGbId + ":" + child.getChannelId());
            node.setParentKey(groupCode == null ? KEY_PLATFORM + platformGbId
                    : KEY_GROUP + platformGbId + ":" + groupCode);
            node.setNodeType(NODE_GROUP);
            node.setLabel(label);
            node.setDomain(GbDomain.SUBORDINATE);
            node.setPlatformGbId(platformGbId);
            node.setGroupCode(child.getChannelId());
            // 路径供右侧列表做子树过滤；treeChildren 内部已尝试补建，仍为空说明是旧脏数据
            node.setTreePath(trimToNull(child.getTreePath()));
            node.setSubtreeChannelCount(child.getSubtreeChannelCount());
            node.setHasChildren(Boolean.TRUE.equals(child.getHasChildren()));
            nodes.add(node);
            if (nodes.size() >= fetch) {
                break;
            }
        }
        return nodes;
    }

    private static String displayName(GbPlatformRegistry row) {
        String name = trimToNull(row.getName());
        return name == null ? row.getPlatformGbId() : name;
    }

    private static Map<Long, Long> toCountMap(List<GbNavCountVO> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Long> map = new HashMap<>(rows.size() * 2);
        for (GbNavCountVO row : rows) {
            if (row.getRefId() != null) {
                map.put(row.getRefId(), row.getTotal() == null ? 0L : row.getTotal());
            }
        }
        return map;
    }

    private static String normalizeDomain(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return DOMAIN_ALL;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (DOMAIN_ALL.equals(upper)) {
            return DOMAIN_ALL;
        }
        if (!GbDomain.isValid(upper)) {
            throw new ServiceException("未知的资源域：" + raw);
        }
        return upper;
    }

    private static int clampLimit(Integer raw) {
        if (raw == null || raw < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(raw, MAX_LIMIT);
    }

    private static Long parseOrgId(String key) {
        try {
            return Long.valueOf(key.substring(KEY_ORG.length()).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
