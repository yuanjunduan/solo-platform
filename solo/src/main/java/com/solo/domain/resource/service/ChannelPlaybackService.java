package com.solo.domain.resource.service;

import com.solo.api.resource.dto.ChannelPlaybackStartRequest;
import com.solo.api.resource.dto.ChannelRecordQueryRequest;
import com.solo.gb28181.request.PlaybackControlRequest;
import com.solo.gb28181.response.PlaybackResultResponse;
import com.solo.gb28181.response.RecordItemResponse;

import java.util.List;

/**
 * 统一通道入口的回放（M6，3.11.4）。
 *
 * <p>本地（NATIVE）与入站（INBOUND）走同一条路：由 {@code res_channel} 解析出 SIP 的
 * 设备码/通道码，再复用既有回放链路。来源侧不落本地存储，只做转发（Q6）。</p>
 *
 * <p>能力位不含 {@code RECORD} 时直接拒绝；能力位为空按未知放行——由设备侧决定。</p>
 */
public interface ChannelPlaybackService {

    /** 录像查询：起止时间均为 yyyy-MM-dd HH:mm:ss */
    List<RecordItemResponse> query(Long channelRowId, ChannelRecordQueryRequest req);

    PlaybackResultResponse start(Long channelRowId, ChannelPlaybackStartRequest req);

    /** 停止：以 streamId 为准（同源选路后实际流可能不是请求行） */
    void stop(String streamId);

    /** 暂停 / 恢复 / 拖动 / 倍速 */
    void control(PlaybackControlRequest req);
}
