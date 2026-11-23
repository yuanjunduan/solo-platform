package com.solo.domain.resource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solo.api.resource.dto.PlayRecordQueryRequest;
import com.solo.common.PageResult;
import com.solo.common.utils.UserHolderUtils;
import com.solo.common.utils.WebUtils;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResPlayRecord;
import com.solo.domain.resource.service.PlayRecordService;
import com.solo.infra.mapper.ResPlayRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 播放记录读写（M6）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayRecordServiceImpl implements PlayRecordService {

    /** 导出口径上限：再大就不是"导出"而是"导出事故" */
    private static final int EXPORT_MAX = 50_000;
    /** 分页上限，防止前端传 100000 把库拖死 */
    private static final int PAGE_MAX = 200;
    /** 失败原因列宽 */
    private static final int REASON_MAX = 256;

    private final ResPlayRecordMapper recordMapper;

    @Override
    public void recordSuccess(ResChannel row, String action, String streamId) {
        save(row, action, streamId, ResPlayRecord.RESULT_SUCCESS, null);
    }

    @Override
    public void recordFailed(ResChannel row, String action, String failReason) {
        save(row, action, null, ResPlayRecord.RESULT_FAILED, failReason);
    }

    private void save(ResChannel row, String action, String streamId, String result, String failReason) {
        if (row == null || action == null) {
            return;
        }
        try {
            ResPlayRecord record = new ResPlayRecord();
            record.setChannelRowId(row.getId());
            record.setChannelCode(row.getCode());
            record.setChannelName(displayName(row));
            record.setSourceType(row.getSourceType());
            record.setSourcePlatformId(row.getSourcePlatformId() == null ? 0L : row.getSourcePlatformId());
            record.setAction(action);
            record.setStreamId(streamId);
            record.setActorId(safeActorId());
            record.setActorName(safeActorName());
            record.setClientIp(safeClientIp());
            record.setResult(result);
            record.setFailReason(truncate(failReason, REASON_MAX));
            record.setBeginTime(LocalDateTime.now());
            if (ResPlayRecord.RESULT_FAILED.equals(result)) {
                // 失败即闭合：没有"停止"可等
                record.close(record.getBeginTime());
            }
            recordMapper.insert(record);
        } catch (Exception e) {
            log.warn("[播放记录] 写入失败（不影响播放）：通道={} 动作={} 结果={} 原因={}",
                    row.getId(), action, result, e.getMessage());
        }
    }

    @Override
    public int closeByStreamId(String streamId) {
        if (streamId == null || streamId.isBlank()) {
            return 0;
        }
        List<ResPlayRecord> open = recordMapper.selectList(new LambdaQueryWrapper<ResPlayRecord>()
                .eq(ResPlayRecord::getStreamId, streamId)
                .eq(ResPlayRecord::getResult, ResPlayRecord.RESULT_SUCCESS)
                .isNull(ResPlayRecord::getEndTime));
        LocalDateTime now = LocalDateTime.now();
        for (ResPlayRecord record : open) {
            ResPlayRecord update = new ResPlayRecord();
            update.setId(record.getId());
            record.close(now);
            update.setEndTime(record.getEndTime());
            update.setDurationSec(record.getDurationSec());
            recordMapper.updateById(update);
        }
        if (!open.isEmpty()) {
            log.info("[播放记录] 按流闭合 流ID:{} 条数:{}", streamId, open.size());
        }
        return open.size();
    }

    @Override
    public PageResult<ResPlayRecord> page(PlayRecordQueryRequest query) {
        int pageNo = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int size = query.getSize() == null || query.getSize() < 1 ? 20 : Math.min(query.getSize(), PAGE_MAX);
        IPage<ResPlayRecord> result = recordMapper.selectPage(new Page<>(pageNo, size), wrapper(query, true));
        return new PageResult<>(result.getTotal(), result.getRecords());
    }

    @Override
    public List<ResPlayRecord> listForExport(PlayRecordQueryRequest query, int limit) {
        int cap = limit <= 0 ? EXPORT_MAX : Math.min(limit, EXPORT_MAX);
        return recordMapper.selectList(wrapper(query, false).last("limit " + cap));
    }

    private LambdaQueryWrapper<ResPlayRecord> wrapper(PlayRecordQueryRequest q, boolean desc) {
        LambdaQueryWrapper<ResPlayRecord> w = new LambdaQueryWrapper<>();
        if (q.getChannelRowId() != null) {
            w.eq(ResPlayRecord::getChannelRowId, q.getChannelRowId());
        }
        if (q.getChannelCode() != null && !q.getChannelCode().isBlank()) {
            w.like(ResPlayRecord::getChannelCode, q.getChannelCode().trim());
        }
        if (q.getActorId() != null && !q.getActorId().isBlank()) {
            w.eq(ResPlayRecord::getActorId, q.getActorId().trim());
        }
        if (q.getAction() != null && !q.getAction().isBlank()) {
            w.eq(ResPlayRecord::getAction, q.getAction().trim().toUpperCase());
        }
        if (q.getResult() != null && !q.getResult().isBlank()) {
            w.eq(ResPlayRecord::getResult, q.getResult().trim().toUpperCase());
        }
        if (q.getBegin() != null) {
            w.ge(ResPlayRecord::getBeginTime, q.getBegin());
        }
        if (q.getEnd() != null) {
            w.le(ResPlayRecord::getBeginTime, q.getEnd());
        }
        if (desc) {
            w.orderByDesc(ResPlayRecord::getBeginTime);
        } else {
            w.orderByAsc(ResPlayRecord::getBeginTime);
        }
        return w;
    }

    private static String displayName(ResChannel row) {
        if (row.getLocalName() != null && !row.getLocalName().isBlank()) {
            return row.getLocalName();
        }
        return row.getName();
    }

    private static String safeActorId() {
        try {
            Long id = UserHolderUtils.getCurrentUserId();
            return id == null ? null : String.valueOf(id);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeActorName() {
        try {
            return UserHolderUtils.getCurrentUsername();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeClientIp() {
        try {
            return WebUtils.getClientIp();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= max ? t : t.substring(0, max);
    }
}
