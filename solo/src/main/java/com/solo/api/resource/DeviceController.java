package com.solo.api.resource;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solo.aop.OperationLog;
import com.solo.aop.RequirePermission;
import com.solo.aop.ResourceWrite;
import com.solo.api.resource.dto.DeviceSaveRequest;
import com.solo.api.resource.dto.ResourcePageRequest;
import com.solo.common.PageResult;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.domain.audit.model.AuditLog;
import com.solo.domain.audit.service.AuditService;
import com.solo.domain.resource.model.ResDevice;
import com.solo.domain.resource.model.ResourceKind;
import com.solo.domain.resource.service.DeviceService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 资源设备管理。
 */
@Tag(name = "资源设备", description = "统一资源模型下的设备（本地自建 + 下级镜像）")
@RestController
@RequestMapping("/api/res/device")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final AuditService auditService;

    @Operation(summary = "分页查询设备")
    @PostMapping("/page")
    @RequirePermission({"res:device:list"})
    public Result<PageResult<ResDevice>> page(@RequestBody ResourcePageRequest req) {
        Page<ResDevice> page = deviceService.pageQuery(req);
        return Result.success(new PageResult<>(page.getTotal(), page.getRecords()));
    }

    @Operation(summary = "设备详情")
    @GetMapping("/{id}")
    @RequirePermission({"res:device:list"})
    public Result<ResDevice> detail(@PathVariable("id") Long id) {
        return Result.success(deviceService.detail(id));
    }

    @Operation(summary = "新建设备")
    @OperationLog(module = "资源设备", type = BusinessType.ADD, description = "新建资源设备")
    @PostMapping
    @RequirePermission({"res:device:add"})
    public Result<ResDevice> create(@Valid @RequestBody DeviceSaveRequest req) {
        ResDevice created = deviceService.create(req);
        auditService.record("SAVE_RESOURCE", AuditLog.TARGET_DEVICE, String.valueOf(created.getId()),
                "新建设备 code=" + created.getCode());
        return Result.success(created);
    }

    @Operation(summary = "编辑设备")
    @OperationLog(module = "资源设备", type = BusinessType.EDIT, description = "修改资源设备")
    @PutMapping("/{id}")
    @RequirePermission({"res:device:edit"})
    @ResourceWrite(value = ResourceKind.DEVICE, scene = "修改设备")
    public Result<Void> update(@PathVariable("id") Long id,
                              @Valid @RequestBody DeviceSaveRequest req) {
        deviceService.update(id, req);
        auditService.record("UPDATE_RESOURCE", AuditLog.TARGET_DEVICE, String.valueOf(id), "修改设备 id=" + id);
        return Result.success();
    }

    @Operation(summary = "删除设备（名下还有通道时拒绝）")
    @OperationLog(module = "资源设备", type = BusinessType.DELETE, description = "删除资源设备")
    @DeleteMapping("/{id}")
    @RequirePermission({"res:device:delete"})
    @ResourceWrite(value = ResourceKind.DEVICE, scene = "删除设备")
    public Result<Void> delete(@PathVariable("id") Long id) {
        deviceService.delete(id);
        auditService.record("DELETE_RESOURCE", AuditLog.TARGET_DEVICE, String.valueOf(id), "删除设备 id=" + id);
        return Result.success();
    }
}
