package com.solo.api.share;

import com.solo.aop.OperationLog;
import com.solo.aop.RequirePermission;
import com.solo.api.share.dto.ShareQueryRequest;
import com.solo.api.share.dto.ShareRetryRequest;
import com.solo.api.share.dto.ShareView;
import com.solo.common.PageResult;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.domain.share.model.OutboxEvent;
import com.solo.domain.share.model.PublishRound;
import com.solo.domain.share.publish.OutboxDispatchService;
import com.solo.domain.share.service.ShareQueryService;
import com.solo.domain.share.service.ShareRepublishService;
import com.solo.infra.mapper.projection.OutboxStatusCount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 推送记录（4.2 / 3.9.5）。
 *
 * <p>两条线分开看：{@code outbox} 是"一条报文一条记录"，{@code publish_round} 是"一次推送一轮台账"。
 * 排查顺序永远是先看轮次（哪一轮降级了、失败几条），再按轮次号筛出站事件。</p>
 *
 * <p>"立即推送"是同步调用：它会真的走 SIP 把队列发完，页面转圈到本轮结束。
 * 不做异步是因为用户点这个按钮就是要一个结果（发了几条、失败几条），
 * 异步入队只会让页面显示"已提交"，下次刷新才知道没发出去。</p>
 */
@Tag(name = "推送记录", description = "出站事件队列与推送轮次台账")
@RestController
@RequestMapping("/api/share/publish")
@RequiredArgsConstructor
public class SharePublishController {

    private final ShareQueryService shareQueryService;
    private final ShareRepublishService shareRepublishService;
    private final OutboxDispatchService outboxDispatchService;

    @Operation(summary = "目标上级平台（发布页筛选用，仅需推送查看权限）")
    @GetMapping("/platforms")
    @RequirePermission({"share:publish:list"})
    public Result<List<ShareView.Target>> platforms() {
        return Result.success(shareQueryService.listTargets());
    }

    @Operation(summary = "出站事件分页（按平台 + 序号倒序）")
    @PostMapping("/outbox/page")
    @RequirePermission({"share:publish:list"})
    public Result<PageResult<OutboxEvent>> outbox(@RequestBody ShareQueryRequest.Outbox req) {
        return Result.success(shareQueryService.pageOutbox(req));
    }

    @Operation(summary = "出站事件按状态计数")
    @GetMapping("/outbox/counts")
    @RequirePermission({"share:publish:list"})
    public Result<List<OutboxStatusCount>> outboxCounts(
            @Parameter(description = "目标上级平台；空 = 全部") @RequestParam(required = false) Long platformId) {
        return Result.success(shareQueryService.outboxCounts(platformId));
    }

    @Operation(summary = "推送轮次分页")
    @PostMapping("/round/page")
    @RequirePermission({"share:publish:list"})
    public Result<PageResult<PublishRound>> rounds(@RequestBody ShareQueryRequest.Round req) {
        return Result.success(shareQueryService.pageRounds(req));
    }

    @Operation(summary = "推送轮次详情")
    @GetMapping("/round/{id}")
    @RequirePermission({"share:publish:list"})
    public Result<PublishRound> round(@PathVariable("id") Long id) {
        return Result.success(shareQueryService.roundDetail(id));
    }

    @Operation(summary = "重试失败/死亡事件（放回待推队列，序号不变）")
    @OperationLog(module = "资源共享", type = BusinessType.OTHER, description = "重试出站事件")
    @PostMapping("/retry")
    @RequirePermission({"share:publish:retry"})
    public Result<Integer> retry(@RequestBody ShareRetryRequest req) {
        return Result.success(shareRepublishService.retry(req.getIds()));
    }

    @Operation(summary = "立即推送该平台的积压（同步执行一轮）")
    @OperationLog(module = "资源共享", type = BusinessType.OTHER, description = "立即推送出站队列")
    @PostMapping("/dispatch/{platformId}")
    @RequirePermission({"share:publish:retry"})
    public Result<OutboxDispatchService.RoundResult> dispatch(
            @Parameter(description = "目标上级平台 id") @PathVariable("platformId") Long platformId) {
        return Result.success(outboxDispatchService.dispatchPlatform(platformId, PublishRound.TRIGGER_MANUAL));
    }
}
