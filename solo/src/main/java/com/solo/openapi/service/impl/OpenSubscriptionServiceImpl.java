package com.solo.openapi.service.impl;

import com.solo.common.PageResult;
import com.solo.common.ServiceException;
import com.solo.domain.event.model.EventSubscription;
import com.solo.domain.resource.support.ResourcePath;
import com.solo.infra.mapper.EventSubscriptionMapper;
import com.solo.infra.mapper.PlatformMapper;
import com.solo.openapi.request.OpenSubscriptionPageRequest;
import com.solo.openapi.request.OpenSubscriptionSaveRequest;
import com.solo.openapi.service.OpenSubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订阅管理端实现。
 *
 * <p>所有写操作都用 Mapper 里的显式 SET 语句，不用 MP 的 {@code updateById}：
 * 后者按非空字段更新，"把平台 scope 清空"这种最需要生效的编辑会被静默丢掉。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenSubscriptionServiceImpl implements OpenSubscriptionService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final EventSubscriptionMapper subscriptionMapper;
    private final PlatformMapper platformMapper;

    @Override
    public PageResult<EventSubscription> page(OpenSubscriptionPageRequest req) {
        int page = req.getPage() == null || req.getPage() < 1 ? 1 : req.getPage().intValue();
        int size = req.getPageSize() == null || req.getPageSize() < 1
                ? DEFAULT_PAGE_SIZE
                : (int) Math.min(req.getPageSize(), MAX_PAGE_SIZE);
        String appKey = StringUtils.hasText(req.getAppKey()) ? req.getAppKey().trim() : null;

        long total = subscriptionMapper.countByFilter(appKey, req.getStatus());
        List<EventSubscription> rows = total == 0
                ? List.of()
                : subscriptionMapper.pageWithApp(appKey, req.getStatus(), (page - 1) * size, size);
        return new PageResult<>(total, rows);
    }

    @Override
    @Transactional
    public void updateScope(OpenSubscriptionSaveRequest req) {
        EventSubscription exists = subscriptionMapper.selectById(req.getId());
        if (exists == null) {
            throw new ServiceException("订阅不存在");
        }

        int status = req.getStatus() == null ? EventSubscription.STATUS_ENABLED : req.getStatus();
        if (status != EventSubscription.STATUS_ENABLED && status != EventSubscription.STATUS_DISABLED) {
            throw new ServiceException("状态只能是 0（停用）或 1（启用）");
        }

        Long platformId = req.getPlatformId();
        if (platformId != null && platformMapper.selectById(platformId) == null) {
            throw new ServiceException("来源平台不存在");
        }
        String orgPath = normalizeOrgPath(req.getOrgPath());

        subscriptionMapper.updateScope(req.getId(), platformId, orgPath, status,
                trimToNull(req.getRemark()), LocalDateTime.now());

        // scope 放宽（平台由指定变全部、子树变整租户）意味着订户可能漏掉了新纳入范围的变更，
        // 这段日志就是给运维排查"为什么某平台的数据要等下一次变更才出现"留的证据链。
        if (isWidened(exists.getPlatformId(), platformId) || isPathWidened(exists.getOrgPath(), orgPath)) {
            log.warn("[订阅] scope 放宽 id={} appKey={} platform {} -> {}, orgPath {} -> {}，"
                            + "订户应重拉快照后重连",
                    exists.getId(), exists.getAppKey(), exists.getPlatformId(), platformId,
                    exists.getOrgPath(), orgPath);
        }
        log.info("[订阅] 编辑 scope id={} appKey={} platformId={} orgPath={} status={}",
                req.getId(), exists.getAppKey(), platformId, orgPath, status);
    }

    @Override
    public void resetCursor(Long id) {
        EventSubscription exists = subscriptionMapper.selectById(id);
        if (exists == null) {
            throw new ServiceException("订阅不存在");
        }
        subscriptionMapper.resetCursor(id, LocalDateTime.now());
        log.info("[订阅] 游标重置 id={} appKey={} 原游标={}", id, exists.getAppKey(), exists.getLastEventId());
    }

    /** 空 / "/" 都表示整租户，统一存 null，让 SQL 里的 {@code <if>} 直接跳过这一维 */
    private String normalizeOrgPath(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String normalized = ResourcePath.normalize(raw);
        return "/".equals(normalized) ? null : normalized;
    }

    private String trimToNull(String raw) {
        return StringUtils.hasText(raw) ? raw.trim() : null;
    }

    /** 由"某个平台"改成"全部平台"算放宽；反向收紧、或换个平台都不算 */
    private boolean isWidened(Long before, Long after) {
        return before != null && after == null;
    }

    /**
     * 新路径不在旧子树里就算放宽：{@code /1/5/} → {@code /1/} 会多看见兄弟节点。
     * <p>用前缀比较而不是长度比较，{@code /1/50/} → {@code /1/5/} 这种换子树的情况
     * 长度更短但并不是放宽，靠 {@code startsWith} 判得更准。</p>
     */
    private boolean isPathWidened(String before, String after) {
        if (before == null) {
            return false;
        }
        if (after == null) {
            return true;
        }
        return !after.startsWith(before);
    }
}
