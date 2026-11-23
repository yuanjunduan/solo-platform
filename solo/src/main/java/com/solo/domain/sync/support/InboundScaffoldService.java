package com.solo.domain.sync.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.solo.common.ServiceException;
import com.solo.domain.platform.model.Platform;
import com.solo.domain.resource.model.ResDevice;
import com.solo.domain.resource.model.ResOrg;
import com.solo.domain.resource.model.ResourceStatus;
import com.solo.domain.resource.model.SourceType;
import com.solo.infra.mapper.ResDeviceMapper;
import com.solo.infra.mapper.ResOrgMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 入站同步的脚手架：三行"必须有、但目录里永远不会有"的数据。
 *
 * <ol>
 *   <li><b>[未分类]</b>：4 步回填第 ④ 步的兜底组织（C13）。</li>
 *   <li><b>平台根组织</b>：{@code code = platform.code}，第 ③ 步"parent_code 等于平台域"的落点。</li>
 *   <li><b>平台载体设备</b>：GB28181 下级平台目录里<b>没有设备这一层</b>，通道直接挂在组织/分组下，
 *       而 {@code res_channel.device_id} 是 NOT NULL 的取流载体。这里给每个平台补一行载体设备
 *       （{@code code = platform.code}，SIP 信令目标本来就是它），通道缺省挂到它上面。</li>
 * </ol>
 *
 * <p>三行都在 {@code start} 阶段建好，合并阶段的集合 SQL 才能直接 JOIN 到；
 * 它们也都在"消失标记"里被排除，否则每轮都会被误判消失。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundScaffoldService {

    /** [未分类] 的组织编码；固定值，不要改——改了历史数据会变成两个孤儿组织 */
    public static final String UNCLASSIFIED_CODE = "UNCLASSIFIED";
    public static final String UNCLASSIFIED_NAME = "[未分类]";

    private static final long DEFAULT_TENANT = 1L;

    private final ResOrgMapper orgMapper;
    private final ResDeviceMapper deviceMapper;

    /**
     * 确保三行存在。
     *
     * @return 平台编码（即根组织编码、载体设备编码），后续 staging 归一化要用
     */
    public Scaffold ensure(Platform platform) {
        String platformCode = resolvePlatformCode(platform);
        long tenantId = platform.getTenantId() == null ? DEFAULT_TENANT : platform.getTenantId();
        LocalDateTime now = LocalDateTime.now();

        // ① [未分类]
        ResOrg unclassified = findOrg(platform.getId(), UNCLASSIFIED_CODE);
        if (unclassified == null) {
            unclassified = newOrg(tenantId, UNCLASSIFIED_CODE, null, UNCLASSIFIED_NAME, platform.getId(), now);
            unclassified.setUnclassified(1);
            orgMapper.insert(unclassified);
            orgMapper.updateById(pathOnly(unclassified.getId(), unclassified.getId(), 1, now));
            log.info("[资源同步] 创建 [未分类] 组织 platformId={} orgId={}", platform.getId(), unclassified.getId());
        }

        // ② 平台根组织（可能已由 4 步回填第 ③ 步之外的历史数据建过）
        ResOrg root = findOrg(platform.getId(), platformCode);
        if (root == null) {
            root = newOrg(tenantId, platformCode, null,
                    platform.getName() == null ? platformCode : platform.getName(), platform.getId(), now);
            orgMapper.insert(root);
            orgMapper.updateById(pathOnly(root.getId(), root.getId(), 1, now));
            log.info("[资源同步] 创建平台根组织 platformId={} code={} orgId={}",
                    platform.getId(), platformCode, root.getId());
        }

        // ③ 平台载体设备
        ResDevice carrier = deviceMapper.selectOne(new LambdaQueryWrapper<ResDevice>()
                .eq(ResDevice::getSourceType, SourceType.INBOUND)
                .eq(ResDevice::getSourcePlatformId, platform.getId())
                .eq(ResDevice::getCode, platformCode)
                .last("limit 1"));
        if (carrier == null) {
            carrier = new ResDevice();
            carrier.setId(IdWorker.getId());
            carrier.setTenantId(tenantId);
            carrier.setCode(platformCode);
            carrier.setName(platform.getName() == null ? platformCode : platform.getName());
            carrier.setOrgId(root.getId());
            carrier.setOrgPath("/" + root.getId() + "/");
            carrier.setChannelCount(0);
            carrier.setSourceType(SourceType.INBOUND);
            carrier.setSourcePlatformId(platform.getId());
            carrier.setOriginPlatformId(platform.getId());
            carrier.setOriginCode(platformCode);
            carrier.setOriginChain("");
            carrier.setHopCount(0);
            carrier.setStatus(ResourceStatus.ACTIVE);
            carrier.setOrgMissing(0);
            carrier.setCreateTime(now);
            carrier.setUpdateTime(now);
            deviceMapper.insert(carrier);
            log.info("[资源同步] 创建平台载体设备 platformId={} code={} deviceId={}",
                    platform.getId(), platformCode, carrier.getId());
        }

        return new Scaffold(platformCode, root.getId(), unclassified.getId(), carrier.getId());
    }

    private String resolvePlatformCode(Platform platform) {
        if (platform.getCode() != null && !platform.getCode().isBlank()) {
            return platform.getCode().trim();
        }
        if (platform.getLocalDomain() != null && !platform.getLocalDomain().isBlank()) {
            return platform.getLocalDomain().trim();
        }
        throw new ServiceException("平台未配置编码/域，无法建立入站脚手架");
    }

    private ResOrg findOrg(Long platformId, String code) {
        return orgMapper.selectOne(new LambdaQueryWrapper<ResOrg>()
                .eq(ResOrg::getSourceType, SourceType.INBOUND)
                .eq(ResOrg::getSourcePlatformId, platformId)
                .eq(ResOrg::getCode, code)
                .last("limit 1"));
    }

    private static ResOrg newOrg(long tenantId, String code, String parentCode, String name,
                                 Long platformId, LocalDateTime now) {
        ResOrg org = new ResOrg();
        org.setId(IdWorker.getId());
        org.setTenantId(tenantId);
        org.setCode(code);
        org.setParentCode(parentCode);
        org.setParentId(null);
        org.setPath("/" + org.getId() + "/");
        org.setLevel(1);
        org.setName(name);
        org.setSourceType(SourceType.INBOUND);
        org.setSourcePlatformId(platformId);
        org.setOriginChain("");
        org.setHopCount(0);
        org.setStatus(ResourceStatus.ACTIVE);
        org.setOrgMissing(0);
        org.setUnclassified(0);
        org.setSortNo(0);
        org.setCreateTime(now);
        org.setUpdateTime(now);
        return org;
    }

    /**
     * 只更新 path/level：{@code insert} 之前 path 需要用到自增（雪花）id，
     * 而 id 由 MP 在 insert 时生成，所以只能插入后再补一次。
     */
    private static ResOrg pathOnly(Long id, Long selfId, int level, LocalDateTime now) {
        ResOrg patch = new ResOrg();
        patch.setId(id);
        patch.setPath("/" + selfId + "/");
        patch.setLevel(level);
        patch.setUpdateTime(now);
        return patch;
    }

    /**
     * @param platformCode      平台编码（= 根组织编码 = 载体设备编码）
     * @param rootOrgId         平台根组织 id
     * @param unclassifiedOrgId [未分类] 组织 id
     * @param carrierDeviceId   平台载体设备 id
     */
    public record Scaffold(String platformCode, Long rootOrgId, Long unclassifiedOrgId, Long carrierDeviceId) {
    }
}
