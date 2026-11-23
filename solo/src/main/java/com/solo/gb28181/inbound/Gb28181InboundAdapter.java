package com.solo.gb28181.inbound;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solo.common.ServiceException;
import com.solo.domain.sync.spi.CatalogSession;
import com.solo.domain.sync.spi.CatalogSink;
import com.solo.domain.sync.spi.InboundAdapter;
import com.solo.domain.sync.spi.PlatformConn;
import com.solo.gb28181.entity.GbDevice;
import com.solo.gb28181.mapper.GbDeviceMapper;
import com.solo.gb28181.transmit.cmd.SipCommander;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * GB28181 入站目录适配器（3.7.3）：发 Catalog 查询，靠 {@link Gb28181CatalogTap} 收回分片。
 *
 * <p><b>对端的定位方式</b>：{@code platform.code} 就是对方注册时的国标编码，因此本适配器
 * 不需要额外的连接参数——先在 {@code gb_device} 里找到这一行，才可能发 SIP MESSAGE。
 * 找不到说明平台登记了但设备没注册上来，属于前置条件不满足，直接抛错而不是异步失败
 * （用户点"同步"时当场就该知道原因）。</p>
 *
 * <p><b>为什么用"查询"而不是"订阅"</b>：M2a 只做全量拉取；SUBSCRIBE 增量通知属于 M2b，
 * 那套要维护 dialog 与续订，混进来会让本轮同步的失败面变大。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Gb28181InboundAdapter implements InboundAdapter {

    public static final String PROTOCOL = "SIP_GB28181";

    private final SipCommander sipCommander;
    private final GbDeviceMapper deviceMapper;
    private final Gb28181CatalogTap catalogTap;

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public CatalogSession open(PlatformConn conn, CatalogSink sink) {
        String deviceId = conn.code();
        if (deviceId == null || deviceId.isBlank()) {
            throw new ServiceException("平台未配置国标编码，无法发起目录同步");
        }

        GbDevice device = deviceMapper.selectOne(
                new LambdaQueryWrapper<GbDevice>().eq(GbDevice::getDeviceId, deviceId));
        if (device == null) {
            throw new ServiceException("PLATFORM_NOT_REGISTERED: 对端设备未注册 " + deviceId);
        }
        if (device.getStatus() == null || device.getStatus() != 1) {
            throw new ServiceException("PLATFORM_OFFLINE: 对端设备不在线 " + deviceId);
        }
        if (!catalogTap.register(deviceId, conn.platformId(), sink)) {
            throw new ServiceException("SYNC_ALREADY_RUNNING: 该平台已有活跃的同步轮次 " + deviceId);
        }

        try {
            sipCommander.queryCatalog(device);
        } catch (RuntimeException e) {
            catalogTap.unregister(deviceId);
            throw new ServiceException("SIP_QUERY_FAILED: 目录查询发送失败 " + e.getMessage(), e);
        }

        log.info("[资源同步] 已发起 GB28181 目录查询 platformId={} deviceId={}",
                conn.platformId(), deviceId);

        return new CatalogSession() {
            @Override
            public String ref() {
                return deviceId;
            }

            @Override
            public void close() {
                catalogTap.unregister(deviceId);
            }
        };
    }
}
