package com.solo.domain.resource.service.impl;

import com.solo.api.resource.dto.ChannelLiveStartRequest;
import com.solo.common.ServiceException;
import com.solo.domain.audit.model.AuditLog;
import com.solo.domain.audit.service.AuditService;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResDevice;
import com.solo.domain.resource.model.ResPlayRecord;
import com.solo.domain.resource.service.ChannelLiveService;
import com.solo.domain.resource.service.PlayRecordService;
import com.solo.gb28181.request.InboundPlayRequest;
import com.solo.gb28181.request.PlayStartRequest;
import com.solo.gb28181.request.PlayStopRequest;
import com.solo.gb28181.response.PlayResultResponse;
import com.solo.gb28181.service.GbPlayService;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.ResDeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 实时点播统一入口：解析来源类型 → 复用既有 GB 点播链路 → 落播放记录与审计。
 *
 * <p><b>不复刻同源选路</b>：入站行直接交给 {@link GbPlayService#startInbound}，选路、互斥、
 * 冷却都在原链路里；本类只补"请求级记录"与"统一入口"两件事，避免同一套逻辑两份实现。</p>
 *
 * <p>本地行的前置校验比回放宽松：不在这里判 PC 是否在线——{@code GbPlayService.start}
 * 会按 {@code gb_device_channel.status} 拒绝；提前判一遍只会让错误文案两处不一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelLiveServiceImpl implements ChannelLiveService {

    private static final String LOG_TAG = "通道点播";

    private final ResChannelMapper channelMapper;
    private final ResDeviceMapper deviceMapper;
    private final GbPlayService playService;
    private final PlayRecordService playRecordService;
    private final AuditService auditService;

    @Override
    public PlayResultResponse start(Long channelRowId, ChannelLiveStartRequest req) {
        ResChannel row = requireRow(channelRowId);
        ChannelLiveStartRequest request = req == null ? new ChannelLiveStartRequest() : req;
        try {
            PlayResultResponse result;
            ResChannel actual = row;
            if ("INBOUND".equals(row.getSourceType())) {
                InboundPlayRequest inbound = new InboundPlayRequest();
                inbound.setChannelRowId(String.valueOf(row.getId()));
                inbound.setTransport(request.getTransport());
                inbound.setStreamNumber(request.getStreamNumber());
                result = playService.startInbound(inbound);
                actual = actualRow(row, result);
            } else {
                PlayStartRequest start = new PlayStartRequest();
                start.setDeviceId(nativeDeviceCode(row));
                start.setChannelId(row.getCode());
                start.setTransport(request.getTransport());
                start.setStreamNumber(request.getStreamNumber());
                result = playService.start(start);
            }
            playRecordService.recordSuccess(actual, ResPlayRecord.ACTION_PLAY,
                    result == null ? null : result.getStreamId());
            auditService.record("PLAY_START", AuditLog.TARGET_CHANNEL, String.valueOf(row.getId()),
                    "开始实时点播 code=" + row.getCode());
            return result;
        } catch (RuntimeException e) {
            playRecordService.recordFailed(row, ResPlayRecord.ACTION_PLAY, e.getMessage());
            log.warn("[{}] 点播失败 通道={} 原因={}", LOG_TAG, row.getId(), e.getMessage());
            throw e;
        }
    }

    @Override
    public void stop(String streamId) {
        if (streamId == null || streamId.isBlank()) {
            throw new ServiceException(400, "streamId 不能为空");
        }
        PlayStopRequest stop = new PlayStopRequest();
        stop.setStreamId(streamId);
        playService.stop(stop);
        playRecordService.closeByStreamId(streamId);
        auditService.record("PLAY_STOP", AuditLog.TARGET_CHANNEL, streamId, "停止实时点播 流ID=" + streamId);
    }

    private ResChannel requireRow(Long channelRowId) {
        if (channelRowId == null) {
            throw new ServiceException(400, "通道 id 不能为空");
        }
        ResChannel row = channelMapper.selectById(channelRowId);
        if (row == null) {
            throw new ServiceException(404, "通道不存在: " + channelRowId);
        }
        if ("REMOVED".equals(row.getStatus())) {
            throw new ServiceException(409, "通道已消失，无法点播");
        }
        if (row.getCode() == null || row.getCode().isBlank()) {
            throw new ServiceException(400, "通道缺少国标编码，无法点播");
        }
        return row;
    }

    /** 入站同源选路可能换行：记录要落在实际取流的那一行上 */
    private ResChannel actualRow(ResChannel requested, PlayResultResponse result) {
        if (result == null || result.getSourceChannelRowId() == null) {
            return requested;
        }
        try {
            ResChannel actual = channelMapper.selectById(Long.parseLong(result.getSourceChannelRowId()));
            return actual == null ? requested : actual;
        } catch (NumberFormatException e) {
            return requested;
        }
    }

    private String nativeDeviceCode(ResChannel row) {
        if (row.getDeviceId() == null || row.getDeviceId() <= 0) {
            throw new ServiceException(400, "通道未挂载取流设备，无法点播");
        }
        ResDevice device = deviceMapper.selectById(row.getDeviceId());
        if (device == null) {
            throw new ServiceException(404, "通道所属设备不存在，无法点播");
        }
        if (device.getCode() == null || device.getCode().isBlank()) {
            throw new ServiceException(400, "通道所属设备缺少国标编码，无法点播");
        }
        return device.getCode();
    }
}
