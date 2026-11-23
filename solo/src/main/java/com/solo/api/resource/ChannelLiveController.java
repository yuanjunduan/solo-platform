package com.solo.api.resource;

import com.solo.aop.OperationLog;
import com.solo.aop.RequirePermission;
import com.solo.api.resource.dto.ChannelLiveStartRequest;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.domain.resource.service.ChannelLiveService;
import com.solo.gb28181.request.PlayStopRequest;
import com.solo.gb28181.response.PlayResultResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一通道入口的实时点播（3.11）。
 *
 * <p>{@code id} 为 {@code res_channel.id}：本地行经 {@code res_device.code} 发 INVITE，
 * 入站行交给既有入站链路（含同源选路）。同一通道同一码流多人观看复用一路 ZLM 流。</p>
 */
@Tag(name = "通道实时点播", description = "统一通道的实时点播（本地 + 入站），自动复用同一路流")
@RestController
@RequestMapping("/api/res/channel")
@RequiredArgsConstructor
public class ChannelLiveController {

    private final ChannelLiveService liveService;

    @Operation(summary = "开始实时点播")
    @OperationLog(module = "通道点播", type = BusinessType.OTHER, description = "开始通道实时点播")
    @PostMapping("/{id}/play/start")
    @RequirePermission({"res:channel:play"})
    public Result<PlayResultResponse> start(@PathVariable("id") Long id,
                                            @RequestBody(required = false) ChannelLiveStartRequest req) {
        return Result.success(liveService.start(id, req));
    }

    @Operation(summary = "停止实时点播", description = "以 streamId 为准；同时关闭对应的播放记录")
    @OperationLog(module = "通道点播", type = BusinessType.OTHER, description = "停止通道实时点播")
    @PostMapping("/play/stop")
    @RequirePermission({"res:channel:play"})
    public Result<Void> stop(@Valid @RequestBody PlayStopRequest req) {
        liveService.stop(req.getStreamId());
        return Result.success();
    }
}
