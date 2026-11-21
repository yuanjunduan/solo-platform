package com.solo.gb28181.controller;

import com.solo.aop.OperationLog;
import com.solo.aop.RequirePermission;
import com.solo.common.enums.BusinessType;
import com.solo.common.Result;
import com.solo.gb28181.request.GbLocalConfigSaveRequest;
import com.solo.gb28181.response.GbLocalConfigResponse;
import com.solo.gb28181.service.GbLocalConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本平台国标配置（库优先于 application yaml）。
 *
 * <p>与 {@code /parent-platform/local-info}（只读运行值）的区别：这里读库里保存的配置，
 * 身份项可能尚未重启生效，响应中的 running* 与 restartRequired 用于提示。</p>
 */
@Tag(name = "本平台国标配置", description = "本平台国标身份与接入参数（库优先于 yaml，身份项重启生效）")
@RestController
@RequestMapping("/api/gb28181/local-config")
@RequiredArgsConstructor
public class GbLocalConfigController {

    private final GbLocalConfigService gbLocalConfigService;

    @Operation(summary = "获取本平台国标配置")
    @GetMapping
    @RequirePermission({"gb28181:parent-platform:list"})
    public Result<GbLocalConfigResponse> getConfig() {
        return Result.success(gbLocalConfigService.getConfig());
    }

    @Operation(summary = "保存本平台国标配置（身份项重启生效，其余即时生效）")
    @OperationLog(module = "本平台国标配置", type = BusinessType.EDIT, description = "保存本平台国标配置")
    @PutMapping
    @RequirePermission({"gb28181:parent-platform:edit"})
    public Result<GbLocalConfigResponse> save(@RequestBody GbLocalConfigSaveRequest request) {
        return Result.success(gbLocalConfigService.save(request));
    }
}
