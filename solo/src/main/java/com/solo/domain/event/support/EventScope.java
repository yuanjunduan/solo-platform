package com.solo.domain.event.support;

import com.solo.domain.event.model.ChangeEvent;
import com.solo.domain.resource.support.ResourcePath;

/**
 * 订阅 scope（Q10 的两级过滤）。
 *
 * <p>为什么推送前还要再过一遍：订阅时校验的是"建订阅那一刻"的结论，
 * 而事件本身带着最终落库的 {@code platform_id / org_path}——两个来源不同，
 * 只认其一都会越权（3.12.1 "每个入口单独校验，不继承上一道关卡的结论"）。</p>
 *
 * <p>两级语义：</p>
 * <ul>
 *   <li>{@code platformId} 为空 = 所有来源平台；不为空则事件的平台必须相同
 *       （事件的平台为空表示"全局刷新"，对谁都发，宁多勿漏）；</li>
 *   <li>{@code orgPath} 为空 = 整租户；不为空则按物化路径前缀匹配子树。
 *       {@code REFRESH_ALL} 不携带位置，一律放行——它只说"去拉快照"，
 *       而快照接口自己也要按 scope 裁剪，泄露不了数据。</li>
 * </ul>
 */
public record EventScope(Long platformId, String orgPath) {

    public static EventScope of(Long platformId, String orgPath) {
        String normalized = orgPath == null || orgPath.isBlank() ? null : ResourcePath.normalize(orgPath);
        return new EventScope(platformId, normalized);
    }

    public boolean accepts(ChangeEvent event) {
        if (platformId != null && event.getPlatformId() != null
                && !platformId.equals(event.getPlatformId())) {
            return false;
        }
        if (orgPath == null) {
            return true;
        }
        if (ChangeEvent.SHAPE_REFRESH.equals(event.getShape())) {
            return true;
        }
        String eventPath = event.getOrgPath();
        if (eventPath == null || eventPath.isBlank()) {
            // 定位不明的事件不能因为"看起来没越权"就发出去
            return false;
        }
        return ResourcePath.normalize(eventPath).startsWith(orgPath);
    }

    /** 事件自带的 scope 标签，形态对齐文档示例的 {@code platform:5} */
    public static String labelOf(ChangeEvent event) {
        if (event.getPlatformId() != null) {
            return "platform:" + event.getPlatformId();
        }
        if (event.getOrgPath() != null && !event.getOrgPath().isBlank()) {
            return "org:" + event.getOrgPath();
        }
        return "all";
    }

    /** 订阅自己的标签，写进 ready 帧方便对接方自检 */
    public String label() {
        if (platformId != null) {
            return "platform:" + platformId;
        }
        if (orgPath != null) {
            return "org:" + orgPath;
        }
        return "all";
    }
}
