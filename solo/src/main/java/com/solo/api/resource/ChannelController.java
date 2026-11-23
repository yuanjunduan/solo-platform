package com.solo.api.resource;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solo.aop.OperationLog;
import com.solo.aop.RateLimiter;
import com.solo.aop.RequirePermission;
import com.solo.aop.ResourceWrite;
import com.solo.api.resource.dto.CanonicalBindRequest;
import com.solo.api.resource.dto.CanonicalUnbindRequest;
import com.solo.api.resource.dto.ChannelListItem;
import com.solo.api.resource.dto.ChannelSaveRequest;
import com.solo.api.resource.dto.ChannelSourceGroup;
import com.solo.api.resource.dto.ResourcePageRequest;
import com.solo.common.PageResult;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.domain.audit.model.AuditLog;
import com.solo.domain.audit.service.AuditService;
import com.solo.domain.platform.model.Platform;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResourceKind;
import com.solo.domain.resource.service.CanonicalChannelService;
import com.solo.domain.resource.service.ChannelService;
import com.solo.infra.mapper.PlatformMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 资源通道管理。
 */
@Tag(name = "资源通道", description = "统一资源模型下的通道（本地自建 + 下级镜像）")
@RestController
@RequestMapping("/api/res/channel")
@RequiredArgsConstructor
public class ChannelController {

    /** 导出上限：超过这个量应走离线抽取，而不是把浏览器和数据库一起拖死 */
    private static final int EXPORT_LIMIT = 50_000;
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String[] EXPORT_HEADER = {
            "通道编码", "名称", "本地别名", "所属组织", "所属设备", "码流类型", "在线", "来源", "状态", "能力"
    };

    private final ChannelService channelService;
    private final CanonicalChannelService canonicalChannelService;
    private final PlatformMapper platformMapper;
    private final AuditService auditService;

    @Operation(summary = "分页查询通道（附设备名 / 组织名）")
    @PostMapping("/page")
    @RequirePermission({"res:channel:list"})
    public Result<PageResult<ChannelListItem>> page(@RequestBody ResourcePageRequest req) {
        Page<ChannelListItem> page = channelService.pageList(req);
        return Result.success(new PageResult<>(page.getTotal(), page.getRecords()));
    }

    @Operation(summary = "通道详情")
    @GetMapping("/{id}")
    @RequirePermission({"res:channel:list"})
    public Result<ResChannel> detail(@PathVariable("id") Long id) {
        return Result.success(channelService.detail(id));
    }

    @Operation(summary = "新建通道")
    @OperationLog(module = "资源通道", type = BusinessType.ADD, description = "新建资源通道")
    @PostMapping
    @RequirePermission({"res:channel:add"})
    public Result<ResChannel> create(@Valid @RequestBody ChannelSaveRequest req) {
        ResChannel created = channelService.create(req);
        auditService.record("SAVE_RESOURCE", AuditLog.TARGET_CHANNEL, String.valueOf(created.getId()),
                "新建通道 code=" + created.getCode());
        return Result.success(created);
    }

    @Operation(summary = "编辑通道")
    @OperationLog(module = "资源通道", type = BusinessType.EDIT, description = "修改资源通道")
    @PutMapping("/{id}")
    @RequirePermission({"res:channel:edit"})
    @ResourceWrite(value = ResourceKind.CHANNEL, scene = "修改通道")
    public Result<Void> update(@PathVariable("id") Long id,
                              @Valid @RequestBody ChannelSaveRequest req) {
        channelService.update(id, req);
        auditService.record("UPDATE_RESOURCE", AuditLog.TARGET_CHANNEL, String.valueOf(id), "修改通道 id=" + id);
        return Result.success();
    }

    @Operation(summary = "删除通道")
    @OperationLog(module = "资源通道", type = BusinessType.DELETE, description = "删除资源通道")
    @DeleteMapping("/{id}")
    @RequirePermission({"res:channel:delete"})
    @ResourceWrite(value = ResourceKind.CHANNEL, scene = "删除通道")
    public Result<Void> delete(@PathVariable("id") Long id) {
        channelService.delete(id);
        auditService.record("DELETE_RESOURCE", AuditLog.TARGET_CHANNEL, String.valueOf(id), "删除通道 id=" + id);
        return Result.success();
    }

    /**
     * 导出通道（M6，CSV 带 BOM）。
     * <p>复用列表的分页查询，把 pageSize 顶到 5 万行上限；再大的量应走离线抽取。</p>
     * <p>导出会同时压数据库与浏览器，按用户限流（3.12.5）并留审计痕迹（3.12.6：
     * 导出属"必审"动作，{@code @OperationLog} 只进操作日志，审计是另一条独立证据链）。</p>
     */
    @Operation(summary = "导出通道（CSV，最多 5 万行）")
    @OperationLog(module = "资源通道", type = BusinessType.EXPORT, description = "导出资源通道")
    @RateLimiter(key = "rate_limit:export:channel:", maxCount = 5, time = 1,
            timeUnit = TimeUnit.MINUTES, limitType = RateLimiter.LimitType.USER,
            message = "导出过于频繁，请稍后再试")
    @GetMapping("/export")
    @RequirePermission({"res:channel:export"})
    public void export(ResourcePageRequest query, HttpServletResponse response) throws IOException {
        query.setPage(1L);
        query.setPageSize((long) EXPORT_LIMIT);
        List<ChannelListItem> rows = channelService.pageList(query).getRecords();
        auditService.record("EXPORT_CHANNEL", AuditLog.TARGET_CHANNEL, null,
                "导出通道 " + rows.size() + " 行（上限 " + EXPORT_LIMIT + "）");

        String filename = "res-channel-" + LocalDateTime.now().format(FILE_TS) + ".csv";
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        OutputStream out = response.getOutputStream();
        // Excel 只有看到 BOM 才按 UTF-8 解码，否则中文列名变乱码
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        StringBuilder sb = new StringBuilder();
        appendLine(sb, EXPORT_HEADER);
        for (ChannelListItem item : rows) {
            ResChannel c = item.getChannel();
            appendLine(sb, new String[]{
                    c.getCode(),
                    c.getName(),
                    c.getLocalName(),
                    item.getOrgName(),
                    item.getDeviceName(),
                    c.getStreamType(),
                    "ON".equals(c.getOnlineStatus()) ? "在线" : "离线",
                    "INBOUND".equals(c.getSourceType()) ? "入站" : "本地",
                    c.getStatus(),
                    c.getCapability()
            });
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void appendLine(StringBuilder sb, String[] cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCsv(cells[i]));
        }
        sb.append("\r\n");
    }

    /** CSV 转义：含逗号 / 引号 / 换行的字段整体加引号，内部引号翻倍 */
    private static String escapeCsv(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        boolean needQuote = raw.indexOf(',') >= 0 || raw.indexOf('"') >= 0
                || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0;
        return needQuote ? '"' + raw.replace("\"", "\"\"") + '"' : raw;
    }

    /* ==================== 同源组（3.10.3） ==================== */

    @Operation(summary = "同源组明细", description = "返回该行所在同源组的组主与全部成员；未关联时只有它自己")
    @GetMapping("/{id}/sources")
    @RequirePermission({"res:channel:list"})
    public Result<ChannelSourceGroup> sources(@PathVariable("id") Long id) {
        return Result.success(toView(canonicalChannelService.group(id)));
    }

    @Operation(summary = "同源组一致性核对",
            description = "统计自指 / 孤儿 / 两级指针 / 组主已消失四类异常；只报不改，修复走人工解除或重绑")
    @GetMapping("/sources/integrity")
    @RequirePermission({"res:channel:list"})
    public Result<CanonicalChannelService.Integrity> integrity() {
        return Result.success(canonicalChannelService.checkIntegrity());
    }

    /**
     * 同源绑定：只允许新建组。
     *
     * <p>刻意<b>不挂 {@code @ResourceWrite}</b>：入站行本来就不能本地改，但"关联"改的是
     * 本地侧字段 {@code canonical_id}，与"修改入站镜像"无关——只读守卫拦的是前者那类写入。</p>
     */
    @Operation(summary = "同源绑定", description = "把 2 条及以上互不隶属的通道关联成同一实体组")
    @OperationLog(module = "资源通道", type = BusinessType.OTHER, description = "同源绑定")
    @PostMapping("/sources/bind")
    @RequirePermission({"res:channel:bind"})
    public Result<ChannelSourceGroup> bind(@Valid @RequestBody CanonicalBindRequest req) {
        return Result.success(toView(canonicalChannelService.bind(req.getIds())));
    }

    @Operation(summary = "解除同源关联", description = "解除单行；解除的是组主时自动从剩余成员提升新组主")
    @OperationLog(module = "资源通道", type = BusinessType.OTHER, description = "解除同源关联")
    @PostMapping("/sources/unbind")
    @RequirePermission({"res:channel:bind"})
    public Result<Void> unbind(@Valid @RequestBody CanonicalUnbindRequest req) {
        canonicalChannelService.unbind(req.getId());
        return Result.success();
    }

    /** 成员视图：补上平台名（本地行 sourcePlatformId=0，名称留空由前端显示"本地"） */
    private ChannelSourceGroup toView(CanonicalChannelService.SourceGroup group) {
        ChannelSourceGroup view = new ChannelSourceGroup();
        view.setLeaderId(group.leaderId());

        Set<Long> platformIds = group.members().stream()
                .map(ResChannel::getSourcePlatformId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        Map<Long, String> names = platformIds.isEmpty() ? Map.of()
                : platformMapper.selectBatchIds(platformIds).stream()
                        .collect(Collectors.toMap(Platform::getId, Platform::getName, (a, b) -> a));

        List<ChannelSourceGroup.Member> members = group.members().stream().map(c -> {
            ChannelSourceGroup.Member member = new ChannelSourceGroup.Member();
            member.setId(c.getId());
            member.setCode(c.getCode());
            member.setName(c.getName());
            member.setSourceType(c.getSourceType());
            member.setSourcePlatformId(c.getSourcePlatformId());
            member.setSourcePlatformName(c.getSourcePlatformId() == null ? null
                    : names.get(c.getSourcePlatformId()));
            member.setOnlineStatus(c.getOnlineStatus());
            member.setHopCount(c.getHopCount());
            member.setStatus(c.getStatus());
            member.setLeader(c.getId().equals(group.leaderId()));
            return member;
        }).toList();
        view.setMembers(members);
        return view;
    }
}
