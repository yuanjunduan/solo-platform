package com.solo.api.resource;

import com.solo.aop.OperationLog;
import com.solo.aop.RequirePermission;
import com.solo.api.resource.dto.ChannelPlaybackStartRequest;
import com.solo.api.resource.dto.ChannelRecordQueryRequest;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.domain.resource.service.ChannelPlaybackService;
import com.solo.gb28181.request.PlaybackControlRequest;
import com.solo.gb28181.request.PlaybackStopRequest;
import com.solo.gb28181.response.PlaybackResultResponse;
import com.solo.gb28181.response.RecordItemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统一通道入口的回放（3.11.4）。
 *
 * <p>{@code id} 为 {@code res_channel.id}，本地（NATIVE）与入站（INBOUND）同一套端点；
 * 入站通道的录像仍由来源平台提供，本地只做转发、不落存储。</p>
 *
 * <p>停止与回放控制以 {@code streamId} 为准，不带通道 id——起播后实际取流行可能与请求行不同。</p>
 */
@Tag(name = "通道回放", description = "统一通道的历史录像查询与回放（本地 + 入站转发）")
@RestController
@RequestMapping("/api/res/channel")
@RequiredArgsConstructor
public class ChannelPlaybackController {

    private final ChannelPlaybackService playbackService;

    @Operation(summary = "录像查询")
    @PostMapping("/{id}/playback/query")
    @RequirePermission({"res:channel:playback"})
    public Result<List<RecordItemResponse>> query(@PathVariable("id") Long id,
                                                  @Valid @RequestBody ChannelRecordQueryRequest req) {
        return Result.success(playbackService.query(id, req));
    }

    @Operation(summary = "开始回放")
    @OperationLog(module = "通道回放", type = BusinessType.OTHER, description = "开始通道回放")
    @PostMapping("/{id}/playback/start")
    @RequirePermission({"res:channel:playback"})
    public Result<PlaybackResultResponse> start(@PathVariable("id") Long id,
                                                @Valid @RequestBody ChannelPlaybackStartRequest req) {
        return Result.success(playbackService.start(id, req));
    }

    @Operation(summary = "停止回放", description = "以 streamId 为准；同时关闭对应的播放记录")
    @OperationLog(module = "通道回放", type = BusinessType.OTHER, description = "停止通道回放")
    @PostMapping("/playback/stop")
    @RequirePermission({"res:channel:playback"})
    public Result<Void> stop(@Valid @RequestBody PlaybackStopRequest req) {
        playbackService.stop(req.getStreamId());
        return Result.success();
    }

    @Operation(summary = "回放控制", description = "pause / resume / seek / speed")
    @PostMapping("/playback/control")
    @RequirePermission({"res:channel:playback"})
    public Result<Void> control(@Valid @RequestBody PlaybackControlRequest req) {
        playbackService.control(req);
        return Result.success();
    }
}
