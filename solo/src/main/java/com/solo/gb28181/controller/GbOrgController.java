package com.solo.gb28181.controller;

import com.solo.aop.OperationLog;
import com.solo.aop.RequirePermission;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.gb28181.entity.GbOrg;
import com.solo.gb28181.request.GbOrgAssignRequest;
import com.solo.gb28181.request.GbOrgSaveRequest;
import com.solo.gb28181.response.GbOrgAssignResultVO;
import com.solo.gb28181.response.GbOrgTreeVO;
import com.solo.gb28181.service.GbOrgService;
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

import java.util.List;

@Tag(name = "本地组织树", description = "本地域资源归属的组织树（与面向上级的推送分组 gb_platform_group 无关）")
@RestController
@RequestMapping("/api/gb28181/org")
@RequiredArgsConstructor
public class GbOrgController {

    private final GbOrgService orgService;

    @Operation(summary = "组织树（含每个节点的直接设备数/通道数）")
    @GetMapping("/tree")
    @RequirePermission({"gb28181:org:list"})
    public Result<List<GbOrgTreeVO>> tree() {
        return Result.success(orgService.listTree());
    }

    @Operation(summary = "新建组织")
    @OperationLog(module = "本地组织", type = BusinessType.ADD, description = "新建组织")
    @PostMapping
    @RequirePermission({"gb28181:org:add"})
    public Result<GbOrg> create(@Valid @RequestBody GbOrgSaveRequest req) {
        return Result.success(orgService.create(req));
    }

    @Operation(summary = "编辑组织（换父时重写整棵子树的路径）")
    @OperationLog(module = "本地组织", type = BusinessType.EDIT, description = "修改组织")
    @PutMapping("/{id}")
    @RequirePermission({"gb28181:org:edit"})
    public Result<Void> update(@PathVariable("id") Long id,
                               @Valid @RequestBody GbOrgSaveRequest req) {
        orgService.update(id, req);
        return Result.success();
    }

    @Operation(summary = "批量调整设备/通道的组织归属")
    @OperationLog(module = "本地组织", type = BusinessType.EDIT, description = "调整资源组织归属")
    @PostMapping("/assign")
    @RequirePermission({"gb28181:org:assign"})
    public Result<GbOrgAssignResultVO> assign(@Valid @RequestBody GbOrgAssignRequest req) {
        return Result.success(orgService.assign(req));
    }

    @Operation(summary = "删除组织（有子组织或仍有资源归属时拒绝）")
    @OperationLog(module = "本地组织", type = BusinessType.DELETE, description = "删除组织")
    @DeleteMapping("/{id}")
    @RequirePermission({"gb28181:org:delete"})
    public Result<Void> delete(@PathVariable("id") Long id) {
        orgService.delete(id);
        return Result.success();
    }
}
