package com.solo.gb28181.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solo.common.ServiceException;
import com.solo.domain.platform.model.Platform;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResDevice;
import com.solo.gb28181.entity.GbDevice;
import com.solo.gb28181.mapper.GbDeviceMapper;
import com.solo.infra.mapper.PlatformMapper;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.ResDeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把统一资源模型的通道解析成"发 SIP 所需的两段码"（M6）。
 *
 * <p>回放与云台都只认 {@code deviceId + channelId}，但 {@code res_channel} 里没有设备码：
 * NATIVE 行要经 {@code res_device.code} 拿设备码，INBOUND 行要把来源平台行
 * （{@code platform.code}）当设备——它在本平台的 SIP 视图里就是一台设备，与入站播放的既有口径一致。</p>
 *
 * <p>校验按"操作前一次性拒绝"：已消失、明确离线、编码缺失、对端未注册/不在线都不再往下走；
 * 状态为空（未知）放行，交由设备侧决定成败——这与入站播放的既有语义保持一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelEndpointResolver {

    private final ResChannelMapper channelMapper;
    private final ResDeviceMapper deviceMapper;
    private final PlatformMapper platformMapper;
    private final GbDeviceMapper gbDeviceMapper;

    public ChannelEndpoint resolve(Long channelRowId) {
        if (channelRowId == null) {
            throw new ServiceException(400, "通道 id 不能为空");
        }
        ResChannel row = channelMapper.selectById(channelRowId);
        if (row == null) {
            throw new ServiceException(404, "通道不存在: " + channelRowId);
        }
        if ("REMOVED".equals(row.getStatus())) {
            throw new ServiceException(409, "通道已消失，无法操作");
        }
        if (row.getOnlineStatus() != null && !"ON".equalsIgnoreCase(row.getOnlineStatus())) {
            throw new ServiceException(409, "通道离线，无法操作");
        }
        if (row.getCode() == null || row.getCode().isBlank()) {
            throw new ServiceException(400, "通道缺少国标编码，无法下发指令");
        }

        String deviceId = "INBOUND".equals(row.getSourceType())
                ? inboundDeviceId(row) : nativeDeviceId(row);

        GbDevice gb = gbDeviceMapper.selectOne(new LambdaQueryWrapper<GbDevice>()
                .eq(GbDevice::getDeviceId, deviceId));
        if (gb == null) {
            throw new ServiceException(404, "PLATFORM_NOT_REGISTERED: 对端未注册 " + deviceId);
        }
        if (gb.getStatus() == null || gb.getStatus() != 1) {
            throw new ServiceException(409, "PLATFORM_OFFLINE: 对端不在线 " + deviceId);
        }
        return new ChannelEndpoint(gb, deviceId, row.getCode(), row);
    }

    private String inboundDeviceId(ResChannel row) {
        if (row.getSourcePlatformId() == null || row.getSourcePlatformId() <= 0) {
            throw new ServiceException(400, "入站通道缺少来源平台，无法下发指令");
        }
        Platform platform = platformMapper.selectById(row.getSourcePlatformId());
        if (platform == null) {
            throw new ServiceException(404, "通道来源平台不存在，无法下发指令");
        }
        if (Platform.STATUS_DISABLED.equals(platform.getStatus())) {
            throw new ServiceException(409, "来源平台已停用，无法下发指令");
        }
        if (platform.getCode() == null || platform.getCode().isBlank()) {
            throw new ServiceException(400, "来源平台未配置国标编码，无法下发指令");
        }
        return platform.getCode();
    }

    private String nativeDeviceId(ResChannel row) {
        if (row.getDeviceId() == null || row.getDeviceId() <= 0) {
            throw new ServiceException(400, "通道未挂载取流设备，无法下发指令");
        }
        ResDevice device = deviceMapper.selectById(row.getDeviceId());
        if (device == null) {
            throw new ServiceException(404, "通道所属设备不存在，无法下发指令");
        }
        if (device.getCode() == null || device.getCode().isBlank()) {
            throw new ServiceException(400, "通道所属设备缺少国标编码，无法下发指令");
        }
        return device.getCode();
    }

    /**
     * 一条可下发指令的通道端点。
     *
     * @param device     SIP 目标设备（本地设备或来源平台行）
     * @param deviceId   SIP 目标设备码
     * @param channelId  SIP 通道码（本地/入站均取 res_channel.code）
     * @param row        统一模型里的那一行
     */
    public record ChannelEndpoint(GbDevice device, String deviceId, String channelId, ResChannel row) {

        /**
         * 能力位判定：空=未知按放行，明确不含该能力才拒绝。
         *
         * <p>能力码是自由位串（如 {@code "PTZ,RECORD,AUDIO"}），由同步或人工维护；
         * 空串意味着"来源没告诉我们"，此时拒绝会把可用通道误判成不可用。</p>
         */
        public boolean supports(String capability) {
            String bits = row.getCapability();
            if (bits == null || bits.isBlank() || capability == null || capability.isBlank()) {
                return true;
            }
            return bits.toUpperCase().contains(capability.toUpperCase());
        }
    }
}
