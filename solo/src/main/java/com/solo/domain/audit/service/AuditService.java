package com.solo.domain.audit.service;

import com.solo.common.utils.UserHolderUtils;
import com.solo.domain.audit.model.AuditLog;
import com.solo.infra.mapper.AuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.RejectedExecutionException;

/**
 * 审计日志（旁路写入，3.12.6 / 3.13.3）。
 *
 * <p><b>定位</b>：异步、可丢、可关。审计写失败绝不能让业务事务回滚——
 * 否则"审计库抖动"会升级成"共享配置保存不了"。这与 staging 正好相反：
 * staging 是主链路，写失败必须让整轮同步失败。</p>
 *
 * <p><b>上下文必须在提交线程取</b>：{@link UserHolderUtils} 依赖 HTTP 请求上下文，
 * 到了异步线程里只剩 null 或直接抛异常。所以操作人和 IP 在方法入口同步取好，
 * 再随任务带过去。</p>
 */
@Slf4j
@Service
public class AuditService {

    /** 与 audit_log.detail 列宽一致；超长截断而不是报错，审计不该因为一句备注写不进去 */
    private static final int DETAIL_MAX = 1024;

    private final AuditLogMapper auditLogMapper;
    private final ThreadPoolTaskExecutor logExecutor;
    private final boolean enabled;

    public AuditService(AuditLogMapper auditLogMapper,
                        @Qualifier("logExecutor") ThreadPoolTaskExecutor logExecutor,
                        @Value("${audit.enabled:true}") boolean enabled) {
        this.auditLogMapper = auditLogMapper;
        this.logExecutor = logExecutor;
        this.enabled = enabled;
    }

    /**
     * 记录一条审计。
     *
     * @param action     动作编码，如 SAVE_SHARE / DELETE_RESOURCE
     * @param targetType 对象类型，见 {@link AuditLog} 的 TARGET_* 常量
     * @param targetId   对象标识（编码或 id 字符串）
     * @param detail     关键字段快照，禁止写凭证与报文原文
     */
    public void record(String action, String targetType, String targetId, String detail) {
        if (!enabled) {
            return;
        }
        if (action == null || action.isBlank() || targetType == null || targetType.isBlank()) {
            log.warn("[审计] 缺少 action 或 targetType，跳过：action={} targetType={}", action, targetType);
            return;
        }

        AuditLog entry = new AuditLog();
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(truncate(targetId, 64));
        entry.setDetail(truncate(detail, DETAIL_MAX));
        entry.setOperatorId(safeUserId());
        entry.setOperatorName(truncate(safeUsername(), 64));
        entry.setClientIp(truncate(currentClientIp(), 64));

        try {
            logExecutor.execute(() -> flush(entry));
        } catch (RejectedExecutionException e) {
            // 队列满时丢弃：审计可丢，业务不可等
            log.warn("[审计] 线程池拒绝，本条丢弃：action={} target={}/{}", action, targetType, targetId);
        }
    }

    private void flush(AuditLog entry) {
        try {
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("[审计] 写入失败（旁路，不影响业务）：action={} target={}/{} 原因={}",
                    entry.getAction(), entry.getTargetType(), entry.getTargetId(), e.getMessage());
        }
    }

    private static Long safeUserId() {
        try {
            return UserHolderUtils.getCurrentUserId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeUsername() {
        try {
            return UserHolderUtils.getCurrentUsername();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String currentClientIp() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return null;
        }
        HttpServletRequest request = servletAttrs.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 代理链里第一个才是客户端
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
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
