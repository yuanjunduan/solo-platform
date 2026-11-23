package com.solo.api.resource;

import com.solo.aop.OperationLog;
import com.solo.aop.RequirePermission;
import com.solo.api.resource.dto.ChannelPresetSaveRequest;
import com.solo.api.resource.dto.ChannelPtzCommandRequest;
import com.solo.api.resource.dto.ChannelPtzDragZoomRequest;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.domain.resource.model.ResChannelPreset;
import com.solo.domain.resource.service.ChannelPtzService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统一通道入口的云台与预置位（3.11.5）。
 *
 * <p>{@code id} 为 {@code res_channel.id}，本地设备与下级平台通道共用；
 * 入站通道的指令发到来源平台，由它转发给真实设备。</p>
 */
@Tag(name = "通道云台", description = "统一通道的云台控制与预置位（本地 + 入站）")
@RestController
@RequestMapping("/api/res/channel")
@RequiredArgsConstructor
public class ChannelPtzController {

    private final ChannelPtzService ptzService;

    @Operation(summary = "云台方向控制", description = "left/right/up/down/upleft/upright/downleft/downright/zoomin/zoomout/stop")
    @OperationLog(module = "通道云台", type = BusinessType.OTHER, description = "云台方向控制")
    @PostMapping("/{id}/ptz/move")
    @RequirePermission({"res:channel:ptz"})
    public Result<Void> move(@PathVariable("id") Long id, @Valid @RequestBody ChannelPtzCommandRequest req) {
        ptzService.move(id, req);
        return Result.success();
    }

    @Operation(summary = "云台聚焦", description = "near / far / stop")
    @PostMapping("/{id}/ptz/focus")
    @RequirePermission({"res:channel:ptz"})
    public Result<Void> focus(@PathVariable("id") Long id,
                              @Parameter(description = "near / far / stop") @RequestParam String direction,
                              @RequestParam(defaultValue = "5") int speed) {
        ptzService.focus(id, direction, speed);
        return Result.success();
    }

    @Operation(summary = "云台光圈", description = "open / close / stop")
    @PostMapping("/{id}/ptz/iris")
    @RequirePermission({"res:channel:ptz"})
    public Result<Void> iris(@PathVariable("id") Long id,
                             @Parameter(description = "open / close / stop") @RequestParam String direction,
                             @RequestParam(defaultValue = "5") int speed) {
        ptzService.iris(id, direction, speed);
        return Result.success();
    }

    @Operation(summary = "云台雨刷", description = "on / off")
    @PostMapping("/{id}/ptz/wiper")
    @RequirePermission({"res:channel:ptz"})
    public Result<Void> wiper(@PathVariable("id") Long id,
                              @Parameter(description = "on / off") @RequestParam String action) {
        ptzService.wiper(id, action);
        return Result.success();
    }

    @Operation(summary = "云台拉框缩放", description = "in=放大 / out=缩小")
    @PostMapping("/{id}/ptz/drag-zoom")
    @RequirePermission({"res:channel:ptz"})
    public Result<Void> dragZoom(@PathVariable("id") Long id,
                                 @Valid @RequestBody ChannelPtzDragZoomRequest req) {
        ptzService.dragZoom(id, req);
        return Result.success();
    }

    @Operation(summary = "云台看守位", description = "开启后设备在指定秒数无操作时自动回到预置位")
    @PostMapping("/{id}/ptz/home-position")
    @RequirePermission({"res:channel:ptz"})
    public Result<Void> homePosition(@PathVariable("id") Long id,
                                     @RequestParam boolean enabled,
                                     @RequestParam(required = false) Integer resetTime,
                                     @RequestParam(required = false) Integer presetIndex) {
        ptzService.homePosition(id, enabled, resetTime, presetIndex);
        return Result.success();
    }

    /* ==================== 预置位 ==================== */

    @Operation(summary = "预置位列表", description = "只读本地名称，设备离线时也可查看")
    @GetMapping("/{id}/presets")
    @RequirePermission({"res:channel:ptz"})
    public Result<List<ResChannelPreset>> presets(@PathVariable("id") Long id) {
        return Result.success(ptzService.listPresets(id));
    }

    @Operation(summary = "设置预置位", description = "同编号已存在则覆盖该槽位，可同时改名")
    @OperationLog(module = "通道云台", type = BusinessType.OTHER, description = "设置预置位")
    @PostMapping("/{id}/presets")
    @RequirePermission({"res:channel:ptz"})
    public Result<ResChannelPreset> savePreset(@PathVariable("id") Long id,
                                               @Valid @RequestBody ChannelPresetSaveRequest req) {
        return Result.success(ptzService.savePreset(id, req));
    }

    @Operation(summary = "调用预置位")
    @OperationLog(module = "通道云台", type = BusinessType.OTHER, description = "调用预置位")
    @PostMapping("/{id}/presets/{presetId}/call")
    @RequirePermission({"res:channel:ptz"})
    public Result<Void> callPreset(@PathVariable("id") Long id, @PathVariable("presetId") Long presetId) {
        ptzService.callPreset(id, presetId);
        return Result.success();
    }

    @Operation(summary = "删除预置位", description = "先通知设备删除，成功后再删本地记录")
    @OperationLog(module = "通道云台", type = BusinessType.DELETE, description = "删除预置位")
    @DeleteMapping("/{id}/presets/{presetId}")
    @RequirePermission({"res:channel:ptz"})
    public Result<Void> deletePreset(@PathVariable("id") Long id, @PathVariable("presetId") Long presetId) {
        ptzService.deletePreset(id, presetId);
        return Result.success();
    }
}
