package com.solo.gb28181.controller;

import com.solo.aop.OperationLog;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.gb28181.request.InboundPlayRequest;
import com.solo.gb28181.request.PlayStartRequest;
import com.solo.gb28181.request.PlayStopRequest;
import com.solo.gb28181.response.PlayResultResponse;
import com.solo.gb28181.service.GbPlayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "实时点播管理", description = "实时点播管理")
@RestController
@RequestMapping("/api/gb28181/play")
@RequiredArgsConstructor
public class GbPlayController {

    private final GbPlayService playService;

    @Operation(summary = "开始点播")
    @OperationLog(module = "实时点播", type = BusinessType.OTHER, description = "开始实时点播")
    @PostMapping("/start")
    public Result<PlayResultResponse> start(@RequestBody PlayStartRequest req) {
        return Result.success(playService.start(req));
    }

    @Operation(summary = "入站通道点播", description = "资源目录里来自下级平台的通道，按行 id 反查来源平台后点播")
    @OperationLog(module = "实时点播", type = BusinessType.OTHER, description = "入站通道点播")
    @PostMapping("/inbound")
    public Result<PlayResultResponse> startInbound(@RequestBody InboundPlayRequest req) {
        return Result.success(playService.startInbound(req));
    }

    @Operation(summary = "停止点播")
    @OperationLog(module = "实时点播", type = BusinessType.OTHER, description = "停止实时点播")
    @PostMapping("/stop")
    public Result<Void> stop(@RequestBody PlayStopRequest req) {
        playService.stop(req);
        return Result.success();
    }
}
