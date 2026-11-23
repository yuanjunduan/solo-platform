package com.solo.api.resource;

import com.solo.aop.OperationLog;
import com.solo.aop.RateLimiter;
import com.solo.aop.RequirePermission;
import com.solo.api.resource.dto.PlayRecordQueryRequest;
import com.solo.common.PageResult;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.domain.audit.model.AuditLog;
import com.solo.domain.audit.service.AuditService;
import com.solo.domain.resource.model.ResPlayRecord;
import com.solo.domain.resource.service.PlayRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 播放记录（M6，3.11.6）。
 *
 * <p>查询与导出共用一套条件：按人 / 通道 / 时间 / 动作 / 结果。导出走 CSV（带 BOM，Excel 直接打开不乱码），
 * 上限 5 万行；再大的量应走离线抽取，而不是把浏览器和数据库一起拖死。</p>
 */
@Tag(name = "播放记录", description = "实时点播与回放的请求级记录（谁、哪条通道、成功失败、多久）")
@RestController
@RequestMapping("/api/res/play-record")
@RequiredArgsConstructor
public class PlayRecordController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String[] HEADER = {
            "开始时间", "结束时间", "时长(秒)", "动作", "结果",
            "通道编码", "通道名称", "来源类型", "来源平台ID",
            "操作者ID", "操作者", "客户端IP", "流ID", "失败原因"
    };

    /** 导出上限：与 Service 端 listForExport 的截断值一致（3.11.6） */
    private static final int EXPORT_LIMIT = 50_000;

    private final PlayRecordService playRecordService;
    private final AuditService auditService;

    @Operation(summary = "分页查询播放记录")
    @GetMapping("/page")
    @RequirePermission({"res:play-record:list"})
    public Result<PageResult<ResPlayRecord>> page(PlayRecordQueryRequest query) {
        return Result.success(playRecordService.page(query));
    }

    @Operation(summary = "导出播放记录（CSV，最多 5 万行）")
    @OperationLog(module = "播放记录", type = BusinessType.EXPORT, description = "导出播放记录")
    // 播放记录含"谁看了哪路视频"，属最敏感的一类导出：按用户限流（3.12.5）+ 审计留痕（3.12.6）
    @RateLimiter(key = "rate_limit:export:play-record:", maxCount = 5, time = 1,
            timeUnit = TimeUnit.MINUTES, limitType = RateLimiter.LimitType.USER,
            message = "导出过于频繁，请稍后再试")
    @GetMapping("/export")
    @RequirePermission({"res:play-record:export"})
    public void export(PlayRecordQueryRequest query, HttpServletResponse response) throws IOException {
        List<ResPlayRecord> rows = playRecordService.listForExport(query, 0);
        auditService.record("EXPORT_PLAY_RECORD", AuditLog.TARGET_PLAY_RECORD, null,
                "导出播放记录 " + rows.size() + " 行（上限 " + EXPORT_LIMIT + "）");

        String filename = "play-record-" + LocalDateTime.now().format(FILE_TS) + ".csv";
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        OutputStream out = response.getOutputStream();
        // Excel 只有看到 BOM 才按 UTF-8 解码，否则中文列名变乱码
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        StringBuilder sb = new StringBuilder();
        appendLine(sb, HEADER);
        for (ResPlayRecord r : rows) {
            appendLine(sb, new String[]{
                    r.getBeginTime() == null ? "" : r.getBeginTime().format(TS),
                    r.getEndTime() == null ? "" : r.getEndTime().format(TS),
                    r.getDurationSec() == null ? "" : String.valueOf(r.getDurationSec()),
                    r.getAction(),
                    r.getResult(),
                    r.getChannelCode(),
                    r.getChannelName(),
                    r.getSourceType(),
                    r.getSourcePlatformId() == null ? "" : String.valueOf(r.getSourcePlatformId()),
                    r.getActorId(),
                    r.getActorName(),
                    r.getClientIp(),
                    r.getStreamId(),
                    r.getFailReason()
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
            sb.append(escape(cells[i]));
        }
        sb.append("\r\n");
    }

    /** CSV 转义：含逗号 / 引号 / 换行的字段整体加引号，内部引号翻倍 */
    private static String escape(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        boolean needQuote = raw.indexOf(',') >= 0 || raw.indexOf('"') >= 0
                || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0;
        if (!needQuote) {
            return raw;
        }
        return '"' + raw.replace("\"", "\"\"") + '"';
    }
}
