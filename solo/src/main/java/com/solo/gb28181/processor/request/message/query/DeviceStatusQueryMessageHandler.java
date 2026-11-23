package com.solo.gb28181.processor.request.message.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.SourceType;
import com.solo.domain.share.service.ShareCatalogService;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.entity.GbDevice;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.entity.GbParentPlatform;
import com.solo.gb28181.mapper.GbDeviceChannelMapper;
import com.solo.gb28181.mapper.GbDeviceMapper;
import com.solo.gb28181.processor.SipRequestProcessorBase;
import com.solo.gb28181.processor.request.message.MessageHandler;
import com.solo.gb28181.transmit.cmd.PlatformCommander;
import com.solo.gb28181.utils.XmlUtils;
import gov.nist.javax.sip.message.SIPRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dom4j.Element;
import org.springframework.stereotype.Component;

import javax.sip.RequestEvent;
import javax.sip.message.Response;


/**
 * 上级平台的 DeviceStatus 查询（设备 / 平台 / 通道三个维度）。
 *
 * <p>通道维度走 M4 出站三表反查（{@link ShareCatalogService#resolveForPlay}）：
 * 撤销后、以及 DELETE 推送成功前，未共享的通道不能向上级报告状态（C12 / C27）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceStatusQueryMessageHandler extends SipRequestProcessorBase implements MessageHandler {

    private static final String LOG_TAG = "[级联设备状态]";

    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final ShareCatalogService shareCatalogService;
    private final PlatformCommander platformCommander;
    private final Gb28181Properties properties;

    @Override
    public String getRootType() { return "Query"; }

    @Override
    public String getCmdType() { return "DeviceStatus"; }

    @Override
    public void handleForDevice(RequestEvent event, GbDevice device, Element root) {
        // 下级设备不会向本平台发 DeviceStatus Query（设备不会反过来查本平台），忽略即可。
        // 200 OK 已由 MessageRequestProcessor 在分发前统一回复。
    }

    @Override
    public void handleForPlatform(RequestEvent event, GbParentPlatform platform, Element root) {
        SIPRequest request = (SIPRequest) event.getRequest();
        String sn = XmlUtils.childText(root, "SN");
        String deviceId = XmlUtils.childText(root, "DeviceID");

        try {
            responseAck(request, Response.OK);
        } catch (Exception e) {
            log.warn("{} 回 200 失败 platform={} SN={}: {}",
                    LOG_TAG, platform.getServerGbId(), sn, e.getMessage());
            return;
        }

        if (deviceId == null || deviceId.isBlank()) {
            log.warn("{} 收到上级查询但 DeviceID 为空 platform={} SN={}",
                    LOG_TAG, platform.getServerGbId(), sn);
            platformCommander.sendDeviceStatusError(platform, "", sn, "DeviceID 为空");
            return;
        }

        // 1) 设备维度
        GbDevice device = deviceMapper.selectOne(
                new LambdaQueryWrapper<GbDevice>().eq(GbDevice::getDeviceId, deviceId));
        if (device != null) {
            log.info("{} 设备命中下发 platform={} SN={} deviceId={} status={}",
                    LOG_TAG, platform.getServerGbId(), sn, deviceId, device.getStatus());
            platformCommander.sendDeviceStatusResponse(platform, sn, device);
            return;
        }

        // 2) 本级平台自身
        if (deviceId.equals(properties.getId())) {
            log.info("{} 本级平台状态下发 platform={} SN={} deviceId={}",
                    LOG_TAG, platform.getServerGbId(), sn, deviceId);
            platformCommander.sendDeviceStatusResponse(platform, sn, deviceId, "ON");
            return;
        }

        // 3) 通道维度：国标 DeviceStatus 按通道，上级常传通道国标码（可能是暴露码）
        String channelStatus = resolveChannelStatus(platform, deviceId);
        if (channelStatus != null) {
            log.info("{} 通道命中下发 platform={} SN={} channelId={} status={}",
                    LOG_TAG, platform.getServerGbId(), sn, deviceId, channelStatus);
            platformCommander.sendDeviceStatusResponse(platform, sn, deviceId, channelStatus);
            return;
        }

        log.warn("{} 设备/通道未登记 platform={} SN={} deviceId={}",
                LOG_TAG, platform.getServerGbId(), sn, deviceId);
        platformCommander.sendDeviceStatusError(platform, deviceId, sn, "设备/通道未在本平台登记");
    }

    /**
     * 反查共享通道的在线状态：本地通道以 SIP 侧通道表为准（{@code res_channel.online_status}
     * 只是镜像，未必实时），入站通道没有本地通道行，只能信同步写入的 online_status。
     *
     * @return ON / OFF；未共享或不存在返回 null（调用方回"未登记"）
     */
    private String resolveChannelStatus(GbParentPlatform platform, String deviceId) {
        if (platform.getId() == null) {
            return null;
        }
        ShareCatalogService.ExposedChannel resolved =
                shareCatalogService.resolveForPlay(platform.getId(), deviceId);
        if (resolved == null) {
            return null;
        }
        ResChannel channel = resolved.channel();
        if (SourceType.NATIVE.equals(channel.getSourceType())) {
            if (resolved.device() == null || resolved.device().getCode() == null) {
                return null;
            }
            GbDeviceChannel row = channelMapper.selectOne(new LambdaQueryWrapper<GbDeviceChannel>()
                    .eq(GbDeviceChannel::getDeviceId, resolved.device().getCode())
                    .eq(GbDeviceChannel::getChannelId, channel.getCode()));
            return row == null ? null : row.getStatus();
        }
        return channel.getOnlineStatus() == null ? "OFF" : channel.getOnlineStatus();
    }
}
