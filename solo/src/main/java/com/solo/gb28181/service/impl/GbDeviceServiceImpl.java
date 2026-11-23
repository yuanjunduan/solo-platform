package com.solo.gb28181.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.solo.common.ServiceException;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.constant.GbCascadeDirection;
import com.solo.gb28181.constant.GbDomain;
import com.solo.gb28181.constant.GbRedisKeys;
import com.solo.gb28181.constant.GbSource;
import com.solo.gb28181.entity.GbDevice;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.entity.GbInviteSession;
import com.solo.gb28181.event.device.DeviceOfflineEvent;
import com.solo.gb28181.mapper.GbDeviceChannelMapper;
import com.solo.gb28181.mapper.GbDeviceMapper;
import com.solo.gb28181.request.GbDevicePageRequest;
import com.solo.gb28181.response.GbDeviceDetectResponse;
import com.solo.gb28181.response.GbDeviceStatusCountVO;
import com.solo.gb28181.service.GbDeviceService;
import com.solo.gb28181.service.GbInviteSessionCleanupService;
import com.solo.gb28181.service.GbInviteSessionService;
import com.solo.gb28181.service.GbOrgService;
import com.solo.gb28181.service.GbPlatformRegistryService;
import com.solo.gb28181.subscribe.DeviceCatalogSubscribeManager;
import com.solo.gb28181.transmit.cmd.SipCommander;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sip.ResponseEvent;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;


@Slf4j
@Service
@RequiredArgsConstructor
public class GbDeviceServiceImpl extends ServiceImpl<GbDeviceMapper, GbDevice> implements GbDeviceService {

    // 默认心跳周期 60s，国标一般都是这个值
    private static final long DEFAULT_KEEPALIVE_INTERVAL = 60L;

    private final StringRedisTemplate redisTemplate;
    private final Gb28181Properties properties;
    private final GbInviteSessionService inviteSessionService;
    private final GbInviteSessionCleanupService inviteCleanup;
    private final GbDeviceChannelMapper channelMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final GbOrgService orgService;
    private final GbPlatformRegistryService platformRegistryService;


    @Lazy
    @Autowired
    private SipCommander sipCommander;

    @Lazy
    @Autowired
    private DeviceCatalogSubscribeManager catalogSubscribeManager;

    @Override
    public Page<GbDevice> pageQuery(GbDevicePageRequest request) {
        LambdaQueryWrapper<GbDevice> wrapper = new LambdaQueryWrapper<>();
        if (request.getDeviceId() != null && !request.getDeviceId().isBlank()) {
            wrapper.like(GbDevice::getDeviceId, request.getDeviceId());
        }
        if (request.getName() != null && !request.getName().isBlank()) {
            wrapper.like(GbDevice::getName, request.getName());
        }
        if (request.getStatus() != null) {
            wrapper.eq(GbDevice::getStatus, request.getStatus());
        }
        if (request.getSource() != null) {
            wrapper.eq(GbDevice::getSource, request.getSource());
        }
        // 左树选中平台节点：端点编码精确匹配（平台节点就是 gb_device 里的一行镜像设备）
        String platformId = trimToNull(request.getPlatformId());
        if (platformId != null) {
            wrapper.eq(GbDevice::getDeviceId, platformId);
        }
        applyDomainAndOrg(wrapper, request.getDomain(), request.getOrgId(), request.isIncludeSubtree());
        wrapper.orderByDesc(GbDevice::getUpdateTime);
        return page(new Page<>(request.getPage(), request.getPageSize()), wrapper);
    }

    /**
     * 域 + 组织过滤（设备页）。
     *
     * <p>组织只属于本地域，所以一旦带了 orgId 就同时锁定 {@code domain = LOCAL}：
     * 否则"未归属"条件会把下级平台的镜像设备（org_id 恒为 NULL）也搜出来。
     * 显式指定了下级/远程域又带 orgId 时必为空集（两个条件不可能同时成立）。</p>
     */
    private void applyDomainAndOrg(LambdaQueryWrapper<GbDevice> wrapper,
                                   String domainParam, Long orgId, boolean includeSubtree) {
        String domain = normalizeDomain(domainParam);
        if (domain != null && orgId == null) {
            wrapper.eq(GbDevice::getDomain, domain);
        }
        if (orgId == null) {
            return;
        }
        if (domain != null && !GbDomain.LOCAL.equals(domain)) {
            wrapper.apply("1 = 0");
            return;
        }
        wrapper.eq(GbDevice::getDomain, GbDomain.LOCAL);
        if (orgId < 0) {
            wrapper.isNull(GbDevice::getOrgId);
            return;
        }
        List<Long> orgIds = orgService.subtreeOrgIds(orgId, includeSubtree);
        if (orgIds.isEmpty()) {
            wrapper.apply("1 = 0");
        } else {
            wrapper.in(GbDevice::getOrgId, orgIds);
        }
    }

    /**
     * 域参数归一：ALL（左树"全部"页签）与空值都表示不过滤，返回 null。
     * 三个真实域沿用原有校验，拼错仍然报错而不是被当成"全部"静默放行。
     */
    private static String normalizeDomain(String domainParam) {
        String domain = trimToNull(domainParam);
        if (domain == null || "ALL".equalsIgnoreCase(domain)) {
            return null;
        }
        if (!GbDomain.isValid(domain)) {
            throw new ServiceException("未知的资源域：" + domain);
        }
        return domain;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @Override
    public List<GbDeviceStatusCountVO> statusCount(String domain, Long orgId, boolean includeSubtree,
                                                   String platformId) {
        String d = normalizeDomain(domain);
        String pid = trimToNull(platformId);
        if (orgId == null) {
            return getBaseMapper().selectStatusCount(d, null, false, pid);
        }
        // 组织只属于本地域：显式指定了下级/远程又按组织统计，两个条件不可能同时成立
        if (d != null && !GbDomain.LOCAL.equals(d)) {
            return Collections.emptyList();
        }
        // -1 = 未归属；正数走子树展开，与列表同一套组织规则（mapper 在 orgIds 非空时自行锁 LOCAL）
        if (orgId < 0) {
            return getBaseMapper().selectStatusCount(null, null, true, pid);
        }
        return getBaseMapper().selectStatusCount(null,
                orgService.subtreeOrgIds(orgId, includeSubtree), false, pid);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createByManual(GbDevice device, List<GbDeviceChannel> channels) {
        if (device.getDeviceId() == null || device.getDeviceId().isBlank()) {
            throw new ServiceException("设备国标ID不能为空");
        }
        boolean dup = lambdaQuery().eq(GbDevice::getDeviceId, device.getDeviceId()).exists();
        if (dup) {
            throw new ServiceException("设备国标ID已存在：" + device.getDeviceId());
        }
        // 状态和来源由系统决定，前端传的不算
        device.setId(null);
        device.setStatus(GbSource.DEVICE_STATUS_UNREGISTERED);
        device.setSource(GbSource.SYSTEM);
        // 资源域由录入方指定：不传按本地域；REMOTE 用于第三方资源平台的共享目录（仅目录，暂不可点播）
        String domain = device.getDomain() == null ? null : device.getDomain().trim();
        if (domain == null || domain.isEmpty()) {
            device.setDomain(GbDomain.LOCAL);
        } else if (!GbDomain.isValid(domain)) {
            throw new ServiceException("未知的资源域：" + domain);
        } else {
            device.setDomain(domain);
        }
        // SIP 运行期字段清空，别让手工录入带进脏数据
        device.setRegisterTime(null);
        device.setKeepaliveTime(null);
        device.setLastCatalogSyncTime(null);
        save(device);
        // 同步新增通道
        log.info("[设备新增] request.channels size = {}", channels != null ? channels.size() : "null");
        if (channels != null && !channels.isEmpty()) {
            for (GbDeviceChannel ch : channels) {
                ch.setId(null);
                ch.setDeviceId(device.getDeviceId());
                ch.setPlatformId(device.getDeviceId());  // 手工新增设备不存在下级平台，来源平台记为设备自身
                ch.setStatus(GbSource.CHANNEL_STATUS_UNREGISTERED);
                ch.setSource(GbSource.SYSTEM);
                ch.setParental(0);  // 手动新增一定是通道
                ch.setParentId(device.getDeviceId());  // parentId = 所属设备 GB 码（与 GB 协议语义一致）
                int rows = channelMapper.insert(ch);
                log.info("[设备新增] save channel channelId={}, rows={}", ch.getChannelId(), rows);
            }
            log.info("[设备新增] 系统手动新增设备 {}，同步新增 {} 条通道", device.getDeviceId(), channels.size());
        } else {
            log.info("[设备新增] 系统手动新增设备: {}", device.getDeviceId());
        }
    }

    @Override
    public void updateByManual(GbDevice device) {
        GbDevice exist = getById(device.getId());
        if (exist == null) {
            throw new ServiceException("设备不存在");
        }
        // 只更新可编辑资料字段，绝不触碰 status/source 及 SIP 运行期字段
        exist.setName(device.getName());
        exist.setManufacturer(device.getManufacturer());
        exist.setModel(device.getModel());
        exist.setFirmware(device.getFirmware());
        exist.setCharset(device.getCharset());
        exist.setTransport(device.getTransport());
        exist.setExpires(device.getExpires());
        exist.setSubscribeCycleForCatalog(device.getSubscribeCycleForCatalog());
        updateById(exist);
        evictProfileCache(exist.getDeviceId());
        log.info("[设备编辑] 更新设备资料: {}", exist.getDeviceId());
        // 目录订阅周期可能变了，重新加载（在线才需要）
        if (exist.getStatus() != null && exist.getStatus() == 1) {
            catalogSubscribeManager.reload(exist.getDeviceId());
        }
    }

    @Override
    public void online(GbDevice device) {
        LocalDateTime now = LocalDateTime.now();

        GbDevice exist = lambdaQuery().eq(GbDevice::getDeviceId, device.getDeviceId()).one();
        if (exist == null) {
            device.setStatus(1);
            device.setRegisterTime(now);
            device.setKeepaliveTime(now);
            device.setSource(GbSource.REGISTER);
            // 首次注册才判域：平台类端点（国标码 11-13 位 = 200）落 SUBORDINATE，
            // 接入设备落 LOCAL。已有行不改域，避免把人工调整过的归属覆盖掉。
            if (device.getDomain() == null || device.getDomain().isBlank()) {
                device.setDomain(GbCascadeDirection.isPlatformGbId(device.getDeviceId())
                        ? GbDomain.SUBORDINATE : GbDomain.LOCAL);
            }
            save(device);
            log.info("[设备上线] 新设备首次注册: {} {} domain={}",
                    device.getDeviceId(), device.getHostAddress(), device.getDomain());
        } else {
            // 只更新注册/心跳相关字段，不覆盖已有的厂商型号名称等配置
            lambdaUpdate()
                    .eq(GbDevice::getId, exist.getId())
                    .set(GbDevice::getStatus, 1)
                    .set(GbDevice::getRegisterTime, now)
                    .set(GbDevice::getKeepaliveTime, now)
                    .set(GbDevice::getIp, device.getIp())
                    .set(GbDevice::getPort, device.getPort())
                    .set(GbDevice::getHostAddress, device.getHostAddress())
                    .set(GbDevice::getLocalIp, device.getLocalIp())
                    .set(GbDevice::getExpires, device.getExpires())
                    .set(GbDevice::getTransport, device.getTransport())
                    .set(GbDevice::getCharset, device.getCharset())
                    .update();
            log.info("[设备上线] 重新注册: {} {}", device.getDeviceId(), device.getHostAddress());
        }
        refreshOnlineKey(device.getDeviceId());
        evictProfileCache(device.getDeviceId());
        // 登记簿跟注册走：左树根层的名称/在线态/信令地址由此刷新
        platformRegistryService.upsertOnRegister(device);
    }

    @Override
    public void offline(String deviceId, String reason) {
        GbDevice device = lambdaQuery().eq(GbDevice::getDeviceId, deviceId).one();
        if (device != null && device.getStatus() != null && device.getStatus() == 1) {
            device.setStatus(0);
            updateById(device);
        }
        redisTemplate.delete(GbRedisKeys.DEVICE_ONLINE_PREFIX + deviceId);
        evictProfileCache(deviceId);
        // 左树根层的在线徽标跟着掉线
        platformRegistryService.markOffline(deviceId);

        // 设备离线，清扫该设备所有活跃会话，把 RTP 端口和 SSRC 收回来
        cleanupSessions(deviceId);

        log.info("[设备离线] {} reason={}", deviceId, reason);
    }

    @Override
    public void keepalive(GbDevice device) {
        device.setKeepaliveTime(LocalDateTime.now());
        if (device.getStatus() == null || device.getStatus() != 1) {
            device.setStatus(1);
        }
        updateById(device);
        refreshOnlineKey(device.getDeviceId());
    }

    private void refreshOnlineKey(String deviceId) {
        long ttl = DEFAULT_KEEPALIVE_INTERVAL * Math.max(1, properties.getKeepaliveTimeoutMultiplier());
        redisTemplate.opsForValue().set(GbRedisKeys.DEVICE_ONLINE_PREFIX + deviceId, "1", ttl, TimeUnit.SECONDS);
    }

    @Override
    public GbDeviceDetectResponse detectBatch(List<Long> ids) {
        GbDeviceDetectResponse result = new GbDeviceDetectResponse();
        if (ids == null || ids.isEmpty()) {
            return result;
        }

        long detectTimeoutMs = properties.getDetectTimeoutMs();

        for (Long id : ids) {
            GbDevice device = baseMapper.selectById(id);
            if (device == null) {
                continue;
            }
            // 无网络地址（从未注册）无法发包探测，跳过且不改 status
            if (device.getHostAddress() == null || device.getHostAddress().isBlank()) {
                result.setSkipped(result.getSkipped() + 1);
                result.getItems().add(new GbDeviceDetectResponse.Item(id, device.getDeviceId(), "skipped"));
                log.info("[设备检测] 跳过（无网络地址）deviceId={}", device.getDeviceId());
                continue;
            }

            // 保存 deviceId 快照供回调使用（避免 device 对象被后续循环覆盖）
            final String deviceId = device.getDeviceId();
            final Long deviceDbId = device.getId();

            // 收到设备应答 → 在线
            Consumer<ResponseEvent> onOk = ev -> markOnline(deviceId, deviceDbId);
            // 设备回非 2xx 仍是存活（只是查询被拒）
            Consumer<ResponseEvent> onError = ev -> markOnline(deviceId, deviceDbId);
            // 超时无应答 → 离线
            Runnable onTimeout = () -> markOffline(deviceId, deviceDbId);

            sipCommander.queryDeviceInfo(device, onOk, onError, onTimeout, detectTimeoutMs);
            result.getItems().add(new GbDeviceDetectResponse.Item(id, device.getDeviceId(), "detecting"));
            result.setDetecting(result.getDetecting() + 1);
        }
        log.info("[设备检测] 发起 {} 台检测，跳过 {} 台", result.getDetecting(), result.getSkipped());
        return result;
    }

    /** 异步标记设备在线 */
    private void markOnline(String deviceId, Long deviceDbId) {
        try {
            GbDevice device = new GbDevice();
            device.setId(deviceDbId);
            device.setStatus(1);
            device.setKeepaliveTime(LocalDateTime.now());
            baseMapper.updateById(device);
            refreshOnlineKey(deviceId);
            log.info("[设备检测] 在线 deviceId={}", deviceId);
        } catch (Exception e) {
            log.error("[设备检测] 标记在线失败 deviceId={}", deviceId, e);
        }
    }

    /** 异步标记设备离线 */
    private void markOffline(String deviceId, Long deviceDbId) {
        try {
            GbDevice device = new GbDevice();
            device.setId(deviceDbId);
            device.setStatus(0);
            baseMapper.updateById(device);
            redisTemplate.delete(GbRedisKeys.DEVICE_ONLINE_PREFIX + deviceId);
            cleanupSessions(deviceId);
            // 通道置 OFF / 停目录订阅 / 级联 NOTIFY 统一交给 DeviceOfflineEventListener，
            // 不在这里重复写一份，避免与 REGISTER 注销、心跳超时两条离线路径行为漂移
            eventPublisher.publishEvent(new DeviceOfflineEvent(this, deviceId, "设备检测超时"));
            log.info("[设备检测] 离线 deviceId={}", deviceId);
        } catch (Exception e) {
            log.error("[设备检测] 标记离线失败 deviceId={}", deviceId, e);
        }
    }

    /**
     * 清扫设备所有活跃点播/回放会话（释放 SSRC / RTP 端口 / Redis 资源）
     */
    private void cleanupSessions(String deviceId) {
        List<GbInviteSession> allSessions = inviteSessionService.listAllActive();
        int cleaned = 0;
        for (GbInviteSession s : allSessions) {
            if (deviceId.equals(s.getDeviceId())) {
                // 设备都离线了，BYE 发不出去，清理掉会话把资源还回来就行
                inviteCleanup.failSession(s, "设备离线", "设备离线，会话强制清扫");
                cleaned++;
            }
        }
        if (cleaned > 0) {
            log.info("[设备离线] 已同步清理该设备 {} 个活跃会话", cleaned);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBatch(List<Long> ids) {
        for (Long id : ids) {
            GbDevice device = getById(id);
            if (device != null) {
                // 删除该设备下所有通道（按 GB 码）
                channelMapper.delete(new LambdaQueryWrapper<GbDeviceChannel>()
                        .eq(GbDeviceChannel::getDeviceId, device.getDeviceId()));
                // 删除设备
                removeById(id);
                // 删除 Redis 在线状态键
                redisTemplate.delete(GbRedisKeys.DEVICE_ONLINE_PREFIX + device.getDeviceId());
                evictProfileCache(device.getDeviceId());
                log.info("[批量删除] 删除设备: deviceId={}, name={}", device.getDeviceId(), device.getName());
            }
        }
    }

    @Override
    public void evictProfileCache(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(GbRedisKeys.DEVICE_PROFILE_PREFIX + deviceId);
        } catch (Exception e) {
            // 缓存失效失败不阻塞业务，等 TTL 兜底
            log.warn("[设备档案] 缓存失效失败 deviceId={} err={}", deviceId, e.getMessage());
        }
    }

    @Override
    public Page<GbDevice> searchByKeyword(String keyword, Long page, Long pageSize) {
        LambdaQueryWrapper<GbDevice> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w ->
                w.like(GbDevice::getDeviceId, keyword)
                 .or()
                 .like(GbDevice::getName, keyword)
                 .or()
                 .like(GbDevice::getReportName, keyword)
            );
        }
        wrapper.orderByDesc(GbDevice::getUpdateTime);
        return page(new Page<>(page, pageSize), wrapper);
    }
}
