package com.solo.domain.event.service;

import com.solo.domain.event.model.EventSubscription;
import com.solo.domain.event.model.ResourceSnapshotPage;
import com.solo.domain.event.support.EventScope;
import com.solo.domain.event.support.EventScopeDeniedException;
import com.solo.domain.resource.support.ResourcePath;
import com.solo.infra.mapper.ChangeEventMapper;
import com.solo.infra.mapper.ResourceSnapshotMapper;
import com.solo.infra.mapper.projection.SnapshotRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 全量快照（6.4 "全量快照接口"）：SSE 的初始态与断线重来的兜底路径。
 *
 * <p><b>它同时是越权防线</b>：订阅的 scope 是"上限"，请求参数只能在其中缩小。
 * 请求了超出范围的平台/组织直接 403，而不是悄悄裁剪成空结果——
 * 静默裁剪会让对接方以为"这个平台没资源"，把排查方向带偏（3.12.5）。</p>
 *
 * <p><b>为什么返回 {@code cursor}</b>：快照是"某一瞬间"的切片。不把这一刻的全局游标
 * 带回去，对接方就没法把"快照 + 增量"拼成连续的状态，只能整份重拉。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceSnapshotService {

    /** 单页上限：与 4.9.3 的单页 20 行不同，这里是机器消费，给到 500 减少往返 */
    public static final int MAX_SIZE = 500;
    public static final int DEFAULT_SIZE = 100;

    private final ResourceSnapshotMapper snapshotMapper;
    private final ChangeEventMapper eventMapper;

    public ResourceSnapshotPage page(EventSubscription subscription, SnapshotType target,
                                     Long platformId, String orgPath,
                                     Integer page, Integer size) {
        EventScope scope = resolveScope(subscription, platformId, orgPath);
        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        int offset = (safePage - 1) * safeSize;

        long total = count(target, scope);
        // 越界页不再打库：total 已经算出来了，直接给空页
        List<SnapshotRow> items = offset >= total ? List.of() : query(target, scope, offset, safeSize);
        long cursor = eventMapper.selectMaxSeq(tenantOf(subscription));

        log.info("[资源事件] 快照查询 appKey={} type={} scope={} page={}/{} 条数={}/{} cursor={}",
                subscription.getAppKey(), target, scope.label(), safePage, safeSize, items.size(), total, cursor);
        return new ResourceSnapshotPage(target.name(), scope.platformId(), scope.orgPath(),
                safePage, safeSize, total, offset + items.size() < total, cursor, items);
    }

    /**
     * 订阅 scope 是上限，请求参数只能收紧不能放宽。
     * <p>两者都为空（订阅不裁剪）时，请求参数就是生效 scope。</p>
     */
    private EventScope resolveScope(EventSubscription subscription, Long platformId, String orgPath) {
        Long effectivePlatform = platformId;
        if (subscription.getPlatformId() != null) {
            if (platformId != null && !subscription.getPlatformId().equals(platformId)) {
                throw new EventScopeDeniedException("订阅 scope 限定平台 "
                        + subscription.getPlatformId() + "，不能查询平台 " + platformId);
            }
            effectivePlatform = subscription.getPlatformId();
        }

        String effectiveOrg = orgPath == null || orgPath.isBlank() ? null : ResourcePath.normalize(orgPath);
        String boundOrg = subscription.getOrgPath() == null || subscription.getOrgPath().isBlank()
                ? null : ResourcePath.normalize(subscription.getOrgPath());
        if (boundOrg != null) {
            if (effectiveOrg == null) {
                effectiveOrg = boundOrg;
            } else if (!effectiveOrg.startsWith(boundOrg)) {
                throw new EventScopeDeniedException("订阅 scope 限定组织子树 " + boundOrg
                        + "，不能查询 " + effectiveOrg);
            }
            // 请求已比订阅更窄时保留请求值：收紧是允许的，别把它悄悄放宽回订阅上限
        }
        return EventScope.of(effectivePlatform, effectiveOrg);
    }

    private long count(SnapshotType type, EventScope scope) {
        return switch (type) {
            case ORG -> snapshotMapper.countOrgs(scope.platformId(), scope.orgPath());
            case DEVICE -> snapshotMapper.countDevices(scope.platformId(), scope.orgPath());
            case CHANNEL -> snapshotMapper.countChannels(scope.platformId(), scope.orgPath());
        };
    }

    private List<SnapshotRow> query(SnapshotType type, EventScope scope, int offset, int limit) {
        return switch (type) {
            case ORG -> snapshotMapper.pageOrgs(scope.platformId(), scope.orgPath(), offset, limit);
            case DEVICE -> snapshotMapper.pageDevices(scope.platformId(), scope.orgPath(), offset, limit);
            case CHANNEL -> snapshotMapper.pageChannels(scope.platformId(), scope.orgPath(), offset, limit);
        };
    }

    private static long tenantOf(EventSubscription subscription) {
        return subscription.getTenantId() == null
                ? EventSubscriptionService.DEFAULT_TENANT_ID : subscription.getTenantId();
    }

    /** 快照类型：与事件里的 {@code item_type} 同一套词，省一次映射 */
    public enum SnapshotType {
        ORG,
        DEVICE,
        CHANNEL;

        public static SnapshotType of(String raw) {
            if (raw == null || raw.isBlank()) {
                // 默认通道：集成方 90% 的诉求是"给我通道列表"
                return CHANNEL;
            }
            return valueOf(raw.trim().toUpperCase());
        }
    }
}
