package com.solo.domain.resource.service.impl;

import com.solo.api.resource.dto.ChannelPlaybackStartRequest;
import com.solo.api.resource.dto.ChannelRecordQueryRequest;
import com.solo.common.ServiceException;
import com.solo.domain.audit.model.AuditLog;
import com.solo.domain.audit.service.AuditService;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResPlayRecord;
import com.solo.domain.resource.service.ChannelPlaybackService;
import com.solo.domain.resource.service.PlayRecordService;
import com.solo.gb28181.request.PlaybackControlRequest;
import com.solo.gb28181.request.PlaybackStartRequest;
import com.solo.gb28181.request.PlaybackStopRequest;
import com.solo.gb28181.request.RecordInfoQueryRequest;
import com.solo.gb28181.response.PlaybackResultResponse;
import com.solo.gb28181.response.RecordItemResponse;
import com.solo.gb28181.service.GbPlaybackService;
import com.solo.gb28181.support.ChannelEndpointResolver;
import com.solo.gb28181.support.ChannelEndpointResolver.ChannelEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 回放统一入口：解析端点 → 能力位门控 → 复用 {@link GbPlaybackService} → 落播放记录与审计。
 *
 * <p>刻意不做同源换源：实时点播换源只影响"从哪条链路取当前画面"，
 * 回放换到另一来源会拿到不同时间段的录像，用户以为是同一段——宁可失败也不静默换源。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelPlaybackServiceImpl implements ChannelPlaybackService {

    private static final String LOG_TAG = "通道回放";
    private static final String CAP_RECORD = "RECORD";

    private final ChannelEndpointResolver endpointResolver;
    private final GbPlaybackService playbackService;
    private final PlayRecordService playRecordService;
    private final AuditService auditService;

    @Override
    public List<RecordItemResponse> query(Long channelRowId, ChannelRecordQueryRequest req) {
        ChannelEndpoint endpoint = requireRecordCapability(channelRowId);
        RecordInfoQueryRequest query = new RecordInfoQueryRequest();
        query.setDeviceId(endpoint.deviceId());
        query.setChannelId(endpoint.channelId());
        query.setStartTime(req.getStartTime());
        query.setEndTime(req.getEndTime());
        return playbackService.queryRecordInfo(query);
    }

    @Override
    public PlaybackResultResponse start(Long channelRowId, ChannelPlaybackStartRequest req) {
        ChannelEndpoint endpoint = requireRecordCapability(channelRowId);
        ResChannel row = endpoint.row();

        PlaybackStartRequest start = new PlaybackStartRequest();
        start.setDeviceId(endpoint.deviceId());
        start.setChannelId(endpoint.channelId());
        start.setStartTime(req.getStartTime());
        start.setEndTime(req.getEndTime());
        start.setTransport(req.getTransport());
        start.setStreamNumber(req.getStreamNumber());

        try {
            PlaybackResultResponse result = playbackService.start(start);
            playRecordService.recordSuccess(row, ResPlayRecord.ACTION_PLAYBACK, result == null ? null : result.getStreamId());
            auditService.record("PLAYBACK_START", AuditLog.TARGET_CHANNEL, String.valueOf(row.getId()),
                    "开始回放 code=" + row.getCode() + " 时段=" + req.getStartTime() + "~" + req.getEndTime());
            return result;
        } catch (RuntimeException e) {
            playRecordService.recordFailed(row, ResPlayRecord.ACTION_PLAYBACK, e.getMessage());
            log.warn("[{}] 起播失败 通道={} 原因={}", LOG_TAG, row.getId(), e.getMessage());
            throw e;
        }
    }

    @Override
    public void stop(String streamId) {
        if (streamId == null || streamId.isBlank()) {
            throw new ServiceException(400, "streamId 不能为空");
        }
        PlaybackStopRequest stop = new PlaybackStopRequest();
        stop.setStreamId(streamId);
        playbackService.stop(stop);
        playRecordService.closeByStreamId(streamId);
        auditService.record("PLAYBACK_STOP", AuditLog.TARGET_CHANNEL, streamId, "停止回放 流ID=" + streamId);
    }

    @Override
    public void control(PlaybackControlRequest req) {
        if (req == null || req.getStreamId() == null || req.getStreamId().isBlank()) {
            throw new ServiceException(400, "streamId 不能为空");
        }
        playbackService.control(req);
    }

    private ChannelEndpoint requireRecordCapability(Long channelRowId) {
        ChannelEndpoint endpoint = endpointResolver.resolve(channelRowId);
        if (!endpoint.supports(CAP_RECORD)) {
            throw new ServiceException(400, "该通道未声明录像能力（capability 不含 RECORD），无法回放");
        }
        return endpoint;
    }
}
