package com.solo.api.share;

import com.solo.aop.OperationLog;
import com.solo.aop.RequirePermission;
import com.solo.api.resource.dto.OrgTreeNode;
import com.solo.api.share.dto.ShareComposeRequest;
import com.solo.api.share.dto.ShareQueryRequest;
import com.solo.api.share.dto.ShareView;
import com.solo.common.PageResult;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.common.utils.UserHolderUtils;
import com.solo.domain.resource.service.OrgService;
import com.solo.domain.share.model.OutGroup;
import com.solo.domain.share.model.OutShare;
import com.solo.domain.share.service.ShareComposeService;
import com.solo.domain.share.service.ShareQueryService;
import com.solo.domain.share.support.SharePreview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 共享编排（4.2 / 4.5.1 一屏三段）。
 *
 * <p><b>目标平台是 {@code gb_parent_platform}</b>（本平台作为下级注册的上级），
 * 不是入站同步的 {@code platform}。两者 id 空间不同，前端不要把平台接入页的 id 传进来。</p>
 *
 * <p>所有写操作都带 {@code @OperationLog} 与 {@code share:compose:edit}：
 * 共享变更会直接改变上级看到的目录，出问题时必须能回答"谁在什么时候改的"。</p>
 */
@Tag(name = "共享编排", description = "选资源 → 挂到上级虚拟组织 → 预览提交")
@RestController
@RequestMapping("/api/share/compose")
@RequiredArgsConstructor
public class ShareComposeController {

    private final ShareComposeService shareComposeService;
    private final ShareQueryService shareQueryService;
    private final OrgService orgService;

    @Operation(summary = "目标上级平台（含共享汇总）")
    @GetMapping("/platforms")
    @RequirePermission({"share:compose:list"})
    public Result<List<ShareView.Target>> platforms() {
        return Result.success(shareQueryService.listTargets());
    }

    @Operation(summary = "该上级的虚拟组织树（扁平，按 path 排序）")
    @GetMapping("/groups")
    @RequirePermission({"share:compose:list"})
    public Result<List<OutGroup>> groups(
            @Parameter(description = "目标上级平台 id") @RequestParam("platformId") Long platformId) {
        return Result.success(shareQueryService.listGroups(platformId));
    }

    @Operation(summary = "一键生成：按来源组织树镜像出该上级的虚拟组织树（幂等）")
    @OperationLog(module = "资源共享", type = BusinessType.ADD, description = "一键生成虚拟组织树")
    @PostMapping("/groups/generate")
    @RequirePermission({"share:compose:edit"})
    public Result<ShareComposeService.GroupTreeResult> generate(
            @RequestBody ShareComposeRequest.GroupGenerate req) {
        return Result.success(shareComposeService.generateGroupTree(
                req.getPlatformId(), req.getSourceOrgId(), req.getMountGroupId()));
    }

    @Operation(summary = "可选来源组织树（本地 + 下级镜像）")
    @GetMapping("/org/tree")
    @RequirePermission({"share:compose:list"})
    public Result<List<OrgTreeNode>> orgTree() {
        return Result.success(orgService.listTree(null));
    }

    @Operation(summary = "可选通道分页（回填已共享/已剔除/已分配暴露码）")
    @PostMapping("/channel/page")
    @RequirePermission({"share:compose:list"})
    public Result<PageResult<ShareView.ChannelOption>> channelPage(
            @RequestBody ShareComposeRequest.ChannelQuery req) {
        return Result.success(shareQueryService.pageChannelOptions(req.getPlatformId(), req));
    }

    @Operation(summary = "已共享清单分页（编排页下半区）")
    @PostMapping("/items/page")
    @RequirePermission({"share:compose:list"})
    public Result<PageResult<OutShare>> items(@RequestBody ShareQueryRequest.Item req) {
        return Result.success(shareQueryService.pageItems(req));
    }

    @Operation(summary = "提交前预览（不落库、不分配编码）")
    @PostMapping("/preview")
    @RequirePermission({"share:compose:list"})
    public Result<SharePreview> preview(@RequestBody ShareComposeRequest.Preview req) {
        return Result.success(shareComposeService.preview(
                req.getPlatformId(), req.getOrgIds(), req.getChannelIds(), req.getDeviceIds()));
    }

    @Operation(summary = "新增/更新子树规则并立即派生一次")
    @OperationLog(module = "资源共享", type = BusinessType.ADD, description = "新增子树共享规则")
    @PostMapping("/rules")
    @RequirePermission({"share:compose:edit"})
    public Result<ShareComposeService.DeriveResult> addRule(@RequestBody ShareComposeRequest.Rule req) {
        return Result.success(shareComposeService.addOrgRule(
                req.getPlatformId(), req.getOrgId(), req.getGroupId()));
    }

    @Operation(summary = "撤销子树规则（派生通道随之撤销）")
    @OperationLog(module = "资源共享", type = BusinessType.DELETE, description = "撤销子树共享规则")
    @DeleteMapping("/rules")
    @RequirePermission({"share:compose:edit"})
    public Result<ShareComposeService.DeriveResult> removeRule(
            @Parameter(description = "目标上级平台 id") @RequestParam("platformId") Long platformId,
            @Parameter(description = "来源组织 id") @RequestParam("orgId") Long orgId) {
        return Result.success(shareComposeService.removeOrgRule(
                platformId, orgId, UserHolderUtils.getCurrentUsername()));
    }

    @Operation(summary = "批量共享通道（人工勾选优先于规则）")
    @OperationLog(module = "资源共享", type = BusinessType.ADD, description = "共享通道")
    @PostMapping("/channels")
    @RequirePermission({"share:compose:edit"})
    public Result<ShareComposeService.DeriveResult> addChannels(
            @RequestBody ShareComposeRequest.ChannelBatch req) {
        return Result.success(shareComposeService.addChannels(
                req.getPlatformId(), req.getChannelIds(), req.getGroupId()));
    }

    @Operation(summary = "批量共享设备（= 共享其全部通道）")
    @OperationLog(module = "资源共享", type = BusinessType.ADD, description = "共享设备")
    @PostMapping("/devices")
    @RequirePermission({"share:compose:edit"})
    public Result<ShareComposeService.DeriveResult> addDevices(
            @RequestBody ShareComposeRequest.DeviceBatch req) {
        return Result.success(shareComposeService.addDevices(
                req.getPlatformId(), req.getDeviceIds(), req.getGroupId()));
    }

    @Operation(summary = "剔除通道（保留派生关系，推 DELETE）")
    @OperationLog(module = "资源共享", type = BusinessType.EDIT, description = "剔除共享通道")
    @PostMapping("/exclude")
    @RequirePermission({"share:compose:edit"})
    public Result<Void> exclude(@RequestBody ShareComposeRequest.ChannelOp req) {
        shareComposeService.excludeChannel(
                req.getPlatformId(), req.getChannelId(), UserHolderUtils.getCurrentUsername());
        return Result.success();
    }

    @Operation(summary = "取消剔除（恢复可见）")
    @OperationLog(module = "资源共享", type = BusinessType.EDIT, description = "恢复被剔除的通道")
    @PostMapping("/restore")
    @RequirePermission({"share:compose:edit"})
    public Result<Void> restore(@RequestBody ShareComposeRequest.ChannelOp req) {
        shareComposeService.restoreChannel(
                req.getPlatformId(), req.getChannelId(), UserHolderUtils.getCurrentUsername());
        return Result.success();
    }
}
