package com.solo.api.resource;

import com.solo.aop.OperationLog;
import com.solo.aop.RequirePermission;
import com.solo.aop.ResourceWrite;
import com.solo.api.resource.dto.OrgSaveRequest;
import com.solo.api.resource.dto.OrgTreeNode;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.domain.audit.model.AuditLog;
import com.solo.domain.audit.service.AuditService;
import com.solo.domain.resource.model.ResOrg;
import com.solo.domain.resource.model.ResourceKind;
import com.solo.domain.resource.service.OrgService;
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
 * 资源组织树。
 *
 * <p>与 {@code /api/gb28181/org}（遗留 gb_org）并存：本接口面向新的统一资源模型
 * （res_org），支持来源列、四态状态与业务键唯一，M2 起下级镜像组织也从这里出。</p>
 */
@Tag(name = "资源组织", description = "统一资源模型下的组织树（本地自建 + 下级镜像）")
@RestController
@RequestMapping("/api/res/org")
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;
    private final AuditService auditService;

    @Operation(summary = "组织树（含每个节点的直接设备数 / 通道数）")
    @GetMapping("/tree")
    @RequirePermission({"res:org:list"})
    public Result<List<OrgTreeNode>> tree(
            @Parameter(description = "来源过滤：NATIVE / INBOUND；不传=全部")
            @RequestParam(required = false) String sourceType) {
        return Result.success(orgService.listTree(sourceType));
    }

    @Operation(summary = "新建组织")
    @OperationLog(module = "资源组织", type = BusinessType.ADD, description = "新建资源组织")
    @PostMapping
    @RequirePermission({"res:org:add"})
    public Result<ResOrg> create(@Valid @RequestBody OrgSaveRequest req) {
        ResOrg created = orgService.create(req);
        auditService.record("SAVE_RESOURCE", AuditLog.TARGET_ORG, String.valueOf(created.getId()),
                "新建组织 code=" + created.getCode());
        return Result.success(created);
    }

    @Operation(summary = "编辑组织（换父时重写整棵子树的路径与层级）")
    @OperationLog(module = "资源组织", type = BusinessType.EDIT, description = "修改资源组织")
    @PutMapping("/{id}")
    @RequirePermission({"res:org:edit"})
    @ResourceWrite(value = ResourceKind.ORG, scene = "修改组织")
    public Result<Void> update(@PathVariable("id") Long id,
                              @Valid @RequestBody OrgSaveRequest req) {
        orgService.update(id, req);
        auditService.record("UPDATE_RESOURCE", AuditLog.TARGET_ORG, String.valueOf(id), "修改组织 id=" + id);
        return Result.success();
    }

    @Operation(summary = "删除组织（有子组织或仍有资源归属时拒绝）")
    @OperationLog(module = "资源组织", type = BusinessType.DELETE, description = "删除资源组织")
    @DeleteMapping("/{id}")
    @RequirePermission({"res:org:delete"})
    @ResourceWrite(value = ResourceKind.ORG, scene = "删除组织")
    public Result<Void> delete(@PathVariable("id") Long id) {
        orgService.delete(id);
        auditService.record("DELETE_RESOURCE", AuditLog.TARGET_ORG, String.valueOf(id), "删除组织 id=" + id);
        return Result.success();
    }
}
