package com.solo.api.share;

import com.solo.aop.OperationLog;
import com.solo.aop.RequirePermission;
import com.solo.api.share.dto.ShareComposeRequest;
import com.solo.api.share.dto.ShareQueryRequest;
import com.solo.api.share.dto.ShareView;
import com.solo.common.PageResult;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.common.utils.UserHolderUtils;
import com.solo.domain.share.model.OutShare;
import com.solo.domain.share.service.ShareComposeService;
import com.solo.domain.share.service.ShareQueryService;
import com.solo.domain.share.service.ShareReconcileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 共享总览（4.2 / 3.9.6）。
 *
 * <p>这个页面回答两个问题："上级现在应该看到什么" 与 "为什么没看到"。
 * 前者是共享项列表与平台汇总，后者是剔除、撤销中、推送失败三种状态。</p>
 *
 * <p><b>撤销与剔除是两种操作，别混</b>：剔除保留派生关系（规则还在，只是这条不可见），
 * 撤销把行置死（要恢复得重新勾选）。两个按钮必须分开，且撤销后到 DELETE 推送成功前，
 * 上级点播/控制会被直接拒绝（C12 第 3 步）。</p>
 */
@Tag(name = "共享总览", description = "平台汇总、共享项、剔除/撤销、对账与派生重算")
@RestController
@RequestMapping("/api/share/overview")
@RequiredArgsConstructor
public class ShareOverviewController {

    private final ShareComposeService shareComposeService;
    private final ShareQueryService shareQueryService;
    private final ShareReconcileService shareReconcileService;

    @Operation(summary = "平台汇总（通道数 / 规则数 / 剔除数 / 最差状态 / 待推数 / 最近一轮）")
    @GetMapping("/platforms")
    @RequirePermission({"share:overview:list"})
    public Result<List<ShareView.Target>> platforms() {
        return Result.success(shareQueryService.listTargets());
    }

    @Operation(summary = "共享项分页（含资源名 / 组名 / 平台名）")
    @PostMapping("/items/page")
    @RequirePermission({"share:overview:list"})
    public Result<PageResult<OutShare>> items(@RequestBody ShareQueryRequest.Item req) {
        return Result.success(shareQueryService.pageItems(req));
    }

    @Operation(summary = "剔除通道（保留派生关系）")
    @OperationLog(module = "资源共享", type = BusinessType.EDIT, description = "剔除共享通道")
    @PostMapping("/exclude")
    @RequirePermission({"share:overview:edit"})
    public Result<Void> exclude(@RequestBody ShareComposeRequest.ChannelOp req) {
        shareComposeService.excludeChannel(
                req.getPlatformId(), req.getChannelId(), UserHolderUtils.getCurrentUsername());
        return Result.success();
    }

    @Operation(summary = "取消剔除")
    @OperationLog(module = "资源共享", type = BusinessType.EDIT, description = "恢复被剔除的通道")
    @PostMapping("/restore")
    @RequirePermission({"share:overview:edit"})
    public Result<Void> restore(@RequestBody ShareComposeRequest.ChannelOp req) {
        shareComposeService.restoreChannel(
                req.getPlatformId(), req.getChannelId(), UserHolderUtils.getCurrentUsername());
        return Result.success();
    }

    @Operation(summary = "撤销单条共享（置死 + 推 DELETE，不删行）")
    @OperationLog(module = "资源共享", type = BusinessType.DELETE, description = "撤销单条共享")
    @PostMapping("/revoke")
    @RequirePermission({"share:overview:edit"})
    public Result<Void> revoke(@RequestBody ShareComposeRequest.ChannelOp req) {
        shareComposeService.revokeChannel(
                req.getPlatformId(), req.getChannelId(), UserHolderUtils.getCurrentUsername());
        return Result.success();
    }

    @Operation(summary = "撤销整个上级的共享（清树 + 一条 REFRESH）")
    @OperationLog(module = "资源共享", type = BusinessType.DELETE, description = "撤销整个上级的共享")
    @PostMapping("/revoke-platform/{platformId}")
    @RequirePermission({"share:overview:edit"})
    public Result<Void> revokePlatform(
            @Parameter(description = "目标上级平台 id") @PathVariable("platformId") Long platformId) {
        shareComposeService.revokePlatform(platformId, UserHolderUtils.getCurrentUsername());
        return Result.success();
    }

    @Operation(summary = "重算派生（组织移动 / 通道新入后手工触发）")
    @OperationLog(module = "资源共享", type = BusinessType.OTHER, description = "重算派生共享")
    @PostMapping("/rebuild/{platformId}")
    @RequirePermission({"share:overview:edit"})
    public Result<ShareComposeService.DeriveResult> rebuild(
            @Parameter(description = "目标上级平台 id") @PathVariable("platformId") Long platformId) {
        return Result.success(shareComposeService.rebuildDerived(platformId));
    }

    @Operation(summary = "对账（只补差异：漏派生 / 该推未推 / 撤销中未推 DELETE）")
    @OperationLog(module = "资源共享", type = BusinessType.OTHER, description = "共享对账")
    @PostMapping("/reconcile/{platformId}")
    @RequirePermission({"share:overview:edit"})
    public Result<ShareReconcileService.ReconcileResult> reconcile(
            @Parameter(description = "目标上级平台 id") @PathVariable("platformId") Long platformId) {
        return Result.success(shareReconcileService.reconcile(platformId));
    }
}
