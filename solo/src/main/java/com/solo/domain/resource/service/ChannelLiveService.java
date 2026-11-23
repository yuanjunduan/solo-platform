package com.solo.domain.resource.service;

import com.solo.api.resource.dto.ChannelLiveStartRequest;
import com.solo.gb28181.response.PlayResultResponse;

/**
 * 统一通道入口的实时点播（3.11.2 + 3.11.3）。
 *
 * <p>本地（NATIVE）按 {@code res_device.code + res_channel.code} 发 INVITE；
 * 入站（INBOUND）按行 id 交给既有入站链路，由它完成同源选路。两条路径共用同一套
 * ZLM 流（key = streamId = 设备码_通道码[_码流]），多用户观看自动复用同一路流。</p>
 *
 * <p>请求级播放记录在这里落：成功一行、失败也一行（含失败原因），停止时按 streamId 闭合。</p>
 */
public interface ChannelLiveService {

    PlayResultResponse start(Long channelRowId, ChannelLiveStartRequest req);

    /** 停止：以 streamId 为准（同源选路后实际流可能与请求行不同） */
    void stop(String streamId);
}
