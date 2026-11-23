package com.solo.api.platform;

import com.solo.aop.OperationLog;
import com.solo.aop.RequirePermission;
import com.solo.api.platform.dto.PlatformListItem;
import com.solo.api.platform.dto.PlatformSaveRequest;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.domain.platform.model.Platform;
import com.solo.domain.platform.service.PlatformService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台接入：一个外部平台一条记录，方向由"收/发"两个能力位表达（3.4.2 / 4.4）。
 *
 * <p>与遗留的 {@code /api/gb28181/platform}（gb_parent_platform）并存：那个是级联注册用的旧模型，
 * 这个面向新资源模型，M5 多级级联时会收敛。</p>
 */
@Tag(name = "平台接入", description = "外部平台连接（收目录 / 发目录两个方向）")
@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformController {

    private final PlatformService platformService;

    @Operation(summary = "平台列表（含入站资源数、最近同步、活跃轮次）")
    @GetMapping
    @RequirePermission({"platform:list"})
    public Result<List<PlatformListItem>> list(
            @Parameter(description = "名称/编码关键词") @RequestParam(required = false) String keyword,
            @Parameter(description = "INBOUND=它给我 / OUTBOUND=我给它；空=全部")
            @RequestParam(required = false) String direction) {
        return Result.success(platformService.list(keyword, direction));
    }

    @Operation(summary = "平台详情")
    @GetMapping("/{id}")
    @RequirePermission({"platform:list"})
    public Result<Platform> detail(@PathVariable("id") Long id) {
        return Result.success(platformService.detail(id));
    }

    @Operation(summary = "新增平台")
    @OperationLog(module = "平台接入", type = BusinessType.ADD, description = "新增平台")
    @PostMapping
    @RequirePermission({"platform:add"})
    public Result<Platform> create(@Valid @RequestBody PlatformSaveRequest req) {
        return Result.success(platformService.create(req));
    }

    @Operation(summary = "编辑平台（编码不可修改）")
    @OperationLog(module = "平台接入", type = BusinessType.EDIT, description = "修改平台")
    @PutMapping("/{id}")
    @RequirePermission({"platform:edit"})
    public Result<Platform> update(@PathVariable("id") Long id,
                                  @Valid @RequestBody PlatformSaveRequest req) {
        return Result.success(platformService.update(id, req));
    }

    @Operation(summary = "启用 / 停用")
    @OperationLog(module = "平台接入", type = BusinessType.EDIT, description = "平台启停")
    @PutMapping("/{id}/status")
    @RequirePermission({"platform:edit"})
    public Result<Void> changeStatus(@PathVariable("id") Long id,
                                     @RequestParam("status") String status) {
        platformService.changeStatus(id, status);
        return Result.success();
    }

    @Operation(summary = "删除平台（还有入站资源或正在同步时拒绝）")
    @OperationLog(module = "平台接入", type = BusinessType.DELETE, description = "删除平台")
    @DeleteMapping("/{id}")
    @RequirePermission({"platform:delete"})
    public Result<Void> delete(@PathVariable("id") Long id) {
        platformService.delete(id);
        return Result.success();
    }
}
