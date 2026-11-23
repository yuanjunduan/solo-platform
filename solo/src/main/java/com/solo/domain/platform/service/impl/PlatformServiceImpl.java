package com.solo.domain.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solo.api.platform.dto.PlatformListItem;
import com.solo.api.platform.dto.PlatformSaveRequest;
import com.solo.common.ServiceException;
import com.solo.domain.platform.model.Platform;
import com.solo.domain.platform.service.PlatformService;
import com.solo.domain.sync.model.SyncTask;
import com.solo.domain.sync.support.SyncCooldownStore;
import com.solo.infra.mapper.PlatformMapper;
import com.solo.infra.mapper.SyncStagingMapper;
import com.solo.infra.mapper.SyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 平台接入实现。
 *
 * <p><b>新增时不自动触发同步</b>：4.4.1 写的是"保存后自动触发首次全量"，
 * 但 M2a 的触发要走 SIP 注册，自动触发会把"配错了地址"变成"一条莫名失败的任务"。
 * 这里改为由前端在保存成功后调 {@code /api/sync/trigger/{id}}——多一次显式点击，
 * 换来失败原因能对着平台行看到。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformServiceImpl implements PlatformService {

    private static final String LOG_TAG = "平台接入";
    private static final long TENANT_DEFAULT = 1L;

    private final PlatformMapper platformMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncStagingMapper stagingMapper;
    private final SyncCooldownStore cooldownStore;

    @Override
    public List<PlatformListItem> list(String keyword, String direction) {
        LambdaQueryWrapper<Platform> w = new LambdaQueryWrapper<Platform>()
                .orderByAsc(Platform::getName)
                .orderByAsc(Platform::getId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.like(Platform::getName, kw).or().like(Platform::getCode, kw));
        }
        if ("INBOUND".equalsIgnoreCase(direction)) {
            w.eq(Platform::getAcceptInbound, 1);
        } else if ("OUTBOUND".equalsIgnoreCase(direction)) {
            w.eq(Platform::getPushOutbound, 1);
        }

        return platformMapper.selectList(w).stream().map(this::toItem).toList();
    }

    @Override
    public Platform detail(Long id) {
        return requirePlatform(id);
    }

    @Override
    @Transactional
    public Platform create(PlatformSaveRequest req) {
        String code = req.getCode().trim();
        long exists = platformMapper.selectCount(new LambdaQueryWrapper<Platform>()
                .eq(Platform::getCode, code));
        if (exists > 0) {
            throw new ServiceException(400, "平台编码已存在：" + code);
        }

        Platform platform = new Platform();
        platform.setTenantId(TENANT_DEFAULT);
        platform.setCode(code);
        applyEditable(platform, req);
        platform.setStatus(Platform.STATUS_OFFLINE);
        platformMapper.insert(platform);

        log.info("[{}] 新增平台 id={} code={} name={} 收={} 发={}",
                LOG_TAG, platform.getId(), code, platform.getName(),
                platform.getAcceptInbound(), platform.getPushOutbound());
        return platform;
    }

    @Override
    @Transactional
    public Platform update(Long id, PlatformSaveRequest req) {
        Platform existing = requirePlatform(id);
        String code = req.getCode().trim();
        if (!code.equals(existing.getCode())) {
            throw new ServiceException(400, "平台编码建立后不可修改（资源表按它归属）");
        }
        applyEditable(existing, req);
        platformMapper.updateById(existing);
        log.info("[{}] 编辑平台 id={} name={} 收={} 发={}",
                LOG_TAG, id, existing.getName(), existing.getAcceptInbound(), existing.getPushOutbound());
        return existing;
    }

    @Override
    @Transactional
    public void changeStatus(Long id, String status) {
        if (!List.of(Platform.STATUS_ONLINE, Platform.STATUS_OFFLINE,
                Platform.STATUS_UNREACHABLE, Platform.STATUS_DISABLED).contains(status)) {
            throw new ServiceException(400, "未知平台状态：" + status);
        }
        Platform existing = requirePlatform(id);
        existing.setStatus(status);
        platformMapper.updateById(existing);
        log.info("[{}] 平台状态变更 id={} {} -> {}", LOG_TAG, id, existing.getCode(), status);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Platform existing = requirePlatform(id);

        SyncTask active = taskMapper.selectActiveByPlatform(id);
        if (active != null) {
            throw new ServiceException(400, "该平台正在同步（" + active.getTaskSn() + "），请先取消");
        }
        long inbound = stagingMapper.countInboundOrg(id)
                + stagingMapper.countInboundDevice(id)
                + stagingMapper.countInboundChannel(id);
        if (inbound > 0) {
            throw new ServiceException(400,
                    "该平台还有 " + inbound + " 条入站资源，删除平台会让这些资源失去来源；请先清空镜像后再删");
        }

        platformMapper.deleteById(id);
        log.info("[{}] 删除平台 id={} code={} name={}", LOG_TAG, id, existing.getCode(), existing.getName());
    }

    private PlatformListItem toItem(Platform platform) {
        PlatformListItem item = new PlatformListItem();
        item.setPlatform(platform);
        item.setInboundOrg(stagingMapper.countInboundOrg(platform.getId()));
        item.setInboundDevice(stagingMapper.countInboundDevice(platform.getId()));
        item.setInboundChannel(stagingMapper.countInboundChannel(platform.getId()));

        SyncTask active = taskMapper.selectActiveByPlatform(platform.getId());
        if (active != null) {
            item.setActiveTaskId(active.getId());
            item.setActiveProgress(active.getProgress());
        }
        SyncTask latest = taskMapper.selectLatestByPlatform(platform.getId());
        if (latest != null) {
            item.setLastSyncStatus(latest.getStatus());
            item.setLastSyncAt(latest.getEndTime() != null ? latest.getEndTime() : latest.getBeginTime());
            item.setLastDeleteSkipped(latest.getDeleteSkipped());
        }
        item.setCooldownMs(cooldownStore.remainingMs(platform.getId()));
        return item;
    }

    /** 可编辑字段的统一赋值：新增与编辑走同一段，避免两处默认值慢慢漂开 */
    private void applyEditable(Platform platform, PlatformSaveRequest req) {
        platform.setName(req.getName().trim());
        platform.setVendor(req.getVendor() == null ? "GB28181" : req.getVendor());
        platform.setSyncProtocol(nvl(req.getSyncProtocol(), "SIP_GB28181"));
        platform.setPublishProtocol(nvl(req.getPublishProtocol(), "SIP_GB28181"));
        platform.setMediaProtocol(nvl(req.getMediaProtocol(), "SIP_GB28181"));
        // protocol 是旧版单一位，继续跟着 sync 走，供还没拆三层协议的旧代码读
        platform.setProtocol(platform.getSyncProtocol());
        platform.setHost(req.getHost() == null ? null : req.getHost().trim());
        platform.setPort(req.getPort() == null ? 5060 : req.getPort());
        platform.setLocalDomain(req.getLocalDomain() == null ? null : req.getLocalDomain().trim());
        platform.setAcceptInbound(req.getAcceptInbound() == null ? 1 : req.getAcceptInbound());
        platform.setPushOutbound(req.getPushOutbound() == null ? 0 : req.getPushOutbound());
        platform.setSyncPolicy(nvl(req.getSyncPolicy(), Platform.POLICY_BOTH));
        platform.setSyncCron(req.getSyncCron() == null ? null : req.getSyncCron().trim());
        platform.setDeleteGuardRatio(req.getDeleteGuardRatio() == null
                ? BigDecimal.ZERO : req.getDeleteGuardRatio());
        platform.setRemark(req.getRemark());
    }

    private Platform requirePlatform(Long id) {
        Platform platform = platformMapper.selectById(id);
        if (platform == null) {
            throw new ServiceException(404, "平台不存在：" + id);
        }
        return platform;
    }

    private static String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
